// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tools;

import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.hbase.async.DeleteRequest;
import org.hbase.async.HBaseClient;
import org.hbase.async.KeyValue;
import org.hbase.async.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Const;
import net.opentsdb.core.IllegalDataException;
import net.opentsdb.core.Internal;
import net.opentsdb.core.Internal.Cell;
import net.opentsdb.core.Query;
import net.opentsdb.core.TSDB;
import net.opentsdb.meta.Annotation;
import net.opentsdb.utils.Config;

/**
 * Tool to dump the data straight from HBase.
 * Useful for debugging data induced problems.
 */
final class DumpSeries {

  /** Prints usage and exits with the given retval. */
  private static void usage(final ArgP argp, final String errmsg,
                            final int retval) {
    System.err.println(errmsg);
    System.err.println("Usage: scan"
        + " [--delete|--import] START-DATE [END-DATE] query [queries...]\n"
        + " [--batch-delete-older] END-DATE meter-name-prefix\n"
        + "To see the format in which queries should be written, see the help"
        + " of the 'query' command.\n"
        + "The --import flag changes the format in which the output is printed"
        + " to use a format suiteable for the 'import' command instead of the"
        + " default output format, which better represents how the data is"
        + " stored in HBase.\n"
        + "The --delete flag will delete every row matched by the query."
        + "  This flag implies --import.");
    System.err.print(argp.usage());
    System.exit(retval);
  }

  public static void main(String[] args) throws Exception {
    ArgP argp = new ArgP();
    CliOptions.addCommon(argp);
    argp.addOption("--import", "Prints the rows in a format suitable for"
                   + " the 'import' command.");
    argp.addOption("--delete", "Deletes rows as they are scanned.");
    argp.addOption("--batch-delete-older", "Scans meters matching a prefix and delete.");
    args = CliOptions.parse(argp, args);

    // get a config object
    Config config = CliOptions.getConfig(argp);
    
    final TSDB tsdb = new TSDB(config);
    tsdb.checkNecessaryTablesExist().joinUninterruptibly();
    final byte[] table = config.getString("tsd.storage.hbase.data_table").getBytes();
    final boolean delete = argp.has("--delete");
    final boolean importformat = delete || argp.has("--import");
    final boolean batch_delete_older = argp.has("--batch-delete-older");
    argp = null;

    if (args == null) {
      usage(argp, "Invalid usage.", 1);
    } else if (batch_delete_older) {
      if (args.length != 2) {
        usage(argp, "Wrong number of arguments with option --batch-delete-older.", 2);
      }
    } else if (args.length < 3) {
      usage(argp, "Not enough arguments.", 2);
    }

    try {
      if (batch_delete_older) {
        batchDelete(tsdb, table, args[0], args[1]);
      } else {
        doDump(tsdb, tsdb.getClient(), table, delete, importformat, false, args);
      }
    } finally {
      tsdb.shutdown().joinUninterruptibly();
    }
  }

  private static void batchDelete(final TSDB tsdb, final byte[] table, final String endTime, final String meterPrefix) throws Exception {
    final List<String> meters = tsdb.suggestMetrics(meterPrefix, Integer.MAX_VALUE);
    final int totalMeterCnt = meters.size();
    final AtomicInteger metersProcessed = new AtomicInteger();
    
    final int delete_threads = 16;
    final Thread[] threads = new Thread[delete_threads];
    
    for (int t = 0; t < delete_threads; t++) {
        final int threadNum = t;

        threads[threadNum] = new Thread(new Runnable() {
            public void run() {
                try {
                    while (true) {
                        String meter;
                        synchronized (meters) {
                            if (meters.isEmpty()) {
                                return;
                            }
                            meter = meters.remove(0);
                            System.out.println("[" + threadNum + "] Issue batch delete for meter: " + meter + "... (" + metersProcessed.incrementAndGet() + "/" + totalMeterCnt + ")");
                        }
                        long t0 = System.currentTimeMillis();
                        long cnt = doDump(tsdb, tsdb.getClient(), table, true, false, true, new String[] { "0", endTime, "sum", meter });
                        System.out.println("[" + threadNum + "] Done batch delete for meter: " + meter + ". Touched " + cnt + " rows in " + (System.currentTimeMillis() - t0) + "ms");
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        
        UncaughtExceptionHandler exceptionHandler = new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                System.err.println("Thread [" + threadNum + "] died with: " + throwable.getMessage());
                throwable.printStackTrace(System.err);
            }
        };

        threads[threadNum].setUncaughtExceptionHandler(exceptionHandler);
        threads[threadNum].start();
    }
    
    for (int t = 0; t < delete_threads; t++) {
        try {
            threads[t].join();
        } catch (Exception e) {
            System.err.println("Error joining Thread [" + t + "] died with: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
  }

  private static long doDump(final TSDB tsdb,
                             final HBaseClient client,
                             final byte[] table,
                             final boolean delete,
                             final boolean importformat,
                             final boolean quiet,
                             final String[] args) throws Exception {
    final ArrayList<Query> queries = new ArrayList<Query>();
    CliQuery.parseCommandLineQuery(args, tsdb, queries, null, null);

    long rowCnt = 0;
    long tickTime = System.currentTimeMillis() + 60000;
    long tickCnt = 0;
    
    for (final Query query : queries) {
      final Scanner scanner = Internal.getScanner(query);

      ArrayList<ArrayList<KeyValue>> rows;
      while ((rows = scanner.nextRows().joinUninterruptibly()) != null) {
        for (final ArrayList<KeyValue> row : rows) {
          rowCnt++;
          final byte[] key = row.get(0).key();
          
          if (!quiet) {
            writeOutout(tsdb, importformat, row, key);
          }

          if (delete) {
              if (System.currentTimeMillis() > tickTime) {
                  System.out.println("Still (" + (++tickCnt) + ") deleting " + args[3] + " rows touched = " + rowCnt);
                  tickTime = System.currentTimeMillis() + 60000;
              }

            final DeleteRequest del = new DeleteRequest(table, key);
            client.delete(del);
          }
        }
      }
    }
    
    return rowCnt;
  }

  private static void writeOutout(final TSDB tsdb, final boolean importformat, final ArrayList<KeyValue> row, final byte[] key) {
    final StringBuilder buf = new StringBuilder();
    final long base_time = Internal.baseTime(tsdb, key);
    final String metric = Internal.metricName(tsdb, key);

    // Print the row key.
    if (!importformat) {
      buf.append(Arrays.toString(key))
        .append(' ')
        .append(metric)
        .append(' ')
        .append(base_time)
        .append(" (").append(date(base_time)).append(") ");
      try {
        buf.append(Internal.getTags(tsdb, key));
      } catch (RuntimeException e) {
        buf.append(e.getClass().getName() + ": " + e.getMessage());
      }
      buf.append('\n');
      System.out.print(buf);
    }

    // Print individual cells.
    buf.setLength(0);
    if (!importformat) {
      buf.append("  ");
    }
    for (final KeyValue kv : row) {
      // Discard everything or keep initial spaces.
      buf.setLength(importformat ? 0 : 2);
      formatKeyValue(buf, tsdb, importformat, kv, base_time, metric);
      if (buf.length() > 0) {
        buf.append('\n');
        System.out.print(buf);
      }
    }
  }

  static void formatKeyValue(final StringBuilder buf,
                             final TSDB tsdb,
                             final KeyValue kv,
                             final long base_time) {
    formatKeyValue(buf, tsdb, true, kv, base_time,
                   Internal.metricName(tsdb, kv.key()));
  }

  private static void formatKeyValue(final StringBuilder buf,
      final TSDB tsdb,
      final boolean importformat,
      final KeyValue kv,
      final long base_time,
      final String metric) {
        
    final String tags;
    if (importformat) {
      final StringBuilder tagsbuf = new StringBuilder();
      for (final Map.Entry<String, String> tag
           : Internal.getTags(tsdb, kv.key()).entrySet()) {
        tagsbuf.append(' ').append(tag.getKey())
          .append('=').append(tag.getValue());
      }
      tags = tagsbuf.toString();
    } else {
      tags = null;
    }
    
    final byte[] qualifier = kv.qualifier();
    final byte[] value = kv.value();
    final int q_len = qualifier.length;

    if (q_len % 2 != 0) {
      if (!importformat) {
        // custom data object, not a data point
        if (kv.qualifier()[0] == Annotation.PREFIX()) {
          appendAnnotation(buf, kv, base_time);
        } else {
          buf.append(Arrays.toString(value))
            .append("\t[Not a data point]");
        }
      }
    } else if (q_len == 2 || q_len == 4 && Internal.inMilliseconds(qualifier)) {
      // regular data point
      final Cell cell = Internal.parseSingleValue(kv);
      if (cell == null) {
        throw new IllegalDataException("Unable to parse row: " + kv);
      }
      if (!importformat) {
        appendRawCell(buf, cell, base_time);
      } else {
        buf.append(metric).append(' ');
        appendImportCell(buf, cell, base_time, tags);
      }
    } else {
      // compacted column
      final ArrayList<Cell> cells = Internal.extractDataPoints(kv);
      if (!importformat) {
        buf.append(Arrays.toString(kv.qualifier()))
           .append('\t')
           .append(Arrays.toString(kv.value()))
           .append(" = ")
           .append(cells.size())
           .append(" values:");
      }
      
      int i = 0;
      for (Cell cell : cells) {
        if (!importformat) {
          buf.append("\n    ");
          appendRawCell(buf, cell, base_time);
        } else {
          buf.append(metric).append(' ');
          appendImportCell(buf, cell, base_time, tags);
          if (i < cells.size() - 1) {
            buf.append("\n");
          }
        }
        i++;
      }
    }
  }
  
  static void appendRawCell(final StringBuilder buf, final Cell cell, 
      final long base_time) {
    final long timestamp = cell.absoluteTimestamp(base_time);
    buf.append(Arrays.toString(cell.qualifier()))
    .append("\t")
    .append(Arrays.toString(cell.value()))
    .append("\t");
    if ((timestamp & Const.SECOND_MASK) != 0) {
      buf.append(Internal.getOffsetFromQualifier(cell.qualifier()));
    } else {
      buf.append(Internal.getOffsetFromQualifier(cell.qualifier()) / 1000);
    }
    buf.append("\t")
    .append(cell.isInteger() ? "l" : "f")
    .append("\t")
    .append(timestamp)
    .append("\t")
    .append("(")
    .append(date(timestamp))
    .append(")");
  }
  
  static void appendImportCell(final StringBuilder buf, final Cell cell, 
      final long base_time, final String tags) {
    buf.append(cell.absoluteTimestamp(base_time))
    .append(" ")
    .append(cell.parseValue())
    .append(tags);
  }
  
  static void appendAnnotation(final StringBuilder buf, final KeyValue kv, 
      final long base_time) {
    final long timestamp = 
        Internal.getTimestampFromQualifier(kv.qualifier(), base_time);
    buf.append(Arrays.toString(kv.qualifier()))
    .append("\t")
    .append(Arrays.toString(kv.value()))
    .append("\t")
    .append(Internal.getOffsetFromQualifier(kv.qualifier(), 1) / 1000)
    .append("\t")
    .append(new String(kv.value(), Charset.forName("ISO-8859-1")))
    .append("\t")
    .append(timestamp)
    .append("\t")
    .append("(")
    .append(date(timestamp))
    .append(")");
  }
  
  /** Transforms a UNIX timestamp into a human readable date.  */
  static String date(final long timestamp) {
    if ((timestamp & Const.SECOND_MASK) != 0) {
      return new Date(timestamp).toString();
    } else {
      return new Date(timestamp * 1000).toString();
    }
  }

}
