/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oodt.commons.io;

//JDK imports
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

//JUnit imports
import junit.framework.TestCase;

/**
 * Test class for {@link LoggerOutputStream}.
 *
 * @author bfoster (Brian Foster)
 */
public class TestLoggerOutputStream extends TestCase {

   public void testLogging() throws InstantiationException, IOException {
      final List<LogRecord> records = new ArrayList<LogRecord>();
      Logger logger = Logger.getLogger(TestLoggerOutputStream.class.getName());
      logger.addHandler(new Handler() {
         @Override
         public void close() throws SecurityException {}
         @Override
         public void flush() {}
         @Override
         public void publish(LogRecord record) {
            records.add(record);
         }
      });
      LoggerOutputStream los = new LoggerOutputStream(logger, 10, Level.INFO);
      los.write("This is a test write to a log file".getBytes());
      los.close();
      assertEquals("This is a ", records.get(0).getMessage());
      assertEquals("test write", records.get(1).getMessage());
      assertEquals(" to a log ", records.get(2).getMessage());
      assertEquals("file", records.get(3).getMessage());
   }

   /** Collects what a logger was told, for one test. */
   private static List<LogRecord> recordsOf(Logger logger) {
      final List<LogRecord> records =
            Collections.synchronizedList(new ArrayList<LogRecord>());
      logger.setUseParentHandlers(false);
      logger.addHandler(new Handler() {
         @Override
         public void close() throws SecurityException {}
         @Override
         public void flush() {}
         @Override
         public void publish(LogRecord record) {
            records.add(record);
         }
      });
      return records;
   }

   private static int timesLogged(List<LogRecord> records, String text) {
      int hits = 0;
      synchronized (records) {
         for (LogRecord record : records) {
            if (record.getMessage() != null
                  && record.getMessage().contains(text)) {
               hits++;
            }
         }
      }
      return hits;
   }

   /**
    * Gobbler flush and ExecUtils close used to log the same stdout twice.
    *
    * <p>
    * Raced on a barrier and repeated, because the two threads have to
    * overlap for the fault to show at all: started one after the other the
    * flush is usually finished before the close begins, and a single pass
    * passes against the unfixed stream about two times in three. Over this
    * many rounds the unfixed stream duplicates something over a hundred
    * times, which is the difference between a test that reports the bug and
    * one that happens to miss it.
    * </p>
    */
   public void testFlushAndCloseLogOnce() throws Exception {
      final String line = "Num Missed    : [0]";

      for (int round = 0; round < 400; round++) {
         Logger logger = Logger.getLogger(TestLoggerOutputStream.class.getName()
               + ".flushClose" + round);
         List<LogRecord> records = recordsOf(logger);

         final LoggerOutputStream los =
               new LoggerOutputStream(logger, 1024, Level.INFO);
         los.write((line + "\n").getBytes("UTF-8"));

         final CyclicBarrier gate = new CyclicBarrier(2);
         final List<Throwable> failures =
               Collections.synchronizedList(new ArrayList<Throwable>());

         Thread flusher = new Thread(new Runnable() {
            @Override
            public void run() {
               try {
                  gate.await();
                  los.flush();
               } catch (Throwable t) {
                  failures.add(t);
               }
            }
         });
         Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
               try {
                  gate.await();
                  los.close();
               } catch (Throwable t) {
                  failures.add(t);
               }
            }
         });

         flusher.start();
         closer.start();
         flusher.join();
         closer.join();

         assertTrue("flush or close threw: " + failures, failures.isEmpty());
         assertEquals("round " + round + " logged the same line more than once",
               1, timesLogged(records, line));
      }
   }

   /**
    * What a gobbler writes after close is still logged.
    *
    * <p>
    * ExecUtils never joins its gobblers: it asks them to stop, closes the
    * process streams and returns, so the last of a pge's stdout is routinely
    * pushed through after close has run. A stream that refuses those writes
    * does not report that to anybody either -- StreamGobbler writes through
    * a PrintWriter, which swallows IOException and only sets checkError,
    * which nothing reads -- so the output just goes missing. This is the
    * tail of a pge's own account of itself, which is the part worth having.
    * </p>
    */
   public void testOutputWrittenAfterCloseIsStillLogged() throws Exception {
      Logger logger = Logger.getLogger(TestLoggerOutputStream.class.getName()
            + ".afterClose");
      List<LogRecord> records = recordsOf(logger);

      LoggerOutputStream los = new LoggerOutputStream(logger, 512, Level.INFO);
      PrintWriter gobbler = new PrintWriter(los);

      los.close();

      gobbler.println("OUTPUT: Num Missed    : [0]");
      gobbler.flush();

      assertFalse("the stream refused what the gobbler wrote after close",
            gobbler.checkError());
      assertEquals("the tail of the pge's stdout was dropped",
            1, timesLogged(records, "Num Missed"));
   }

   /** Closing twice is not an error, and does not log anything twice. */
   public void testClosingTwiceLogsOnce() throws Exception {
      Logger logger = Logger.getLogger(TestLoggerOutputStream.class.getName()
            + ".twice");
      List<LogRecord> records = recordsOf(logger);

      LoggerOutputStream los = new LoggerOutputStream(logger, 512, Level.INFO);
      los.write("Num Missed    : [0]\n".getBytes("UTF-8"));
      los.close();
      los.close();

      assertEquals(1, timesLogged(records, "Num Missed"));
   }
}
