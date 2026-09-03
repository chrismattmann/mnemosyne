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
import java.util.ArrayList;
import java.util.List;
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

   /**
    * Gobbler flush and ExecUtils close used to log the same stdout twice.
    */
   public void testFlushAndCloseLogOnce() throws Exception {
      final List<LogRecord> records = new ArrayList<LogRecord>();
      Logger logger = Logger.getLogger(TestLoggerOutputStream.class.getName()
            + ".flushClose");
      logger.setUseParentHandlers(false);
      logger.addHandler(new Handler() {
         @Override
         public void close() throws SecurityException {}
         @Override
         public void flush() {}
         @Override
         public void publish(LogRecord record) {
            synchronized (records) {
               records.add(record);
            }
         }
      });
      final LoggerOutputStream los = new LoggerOutputStream(logger, 1024, Level.INFO);
      los.write("Num Missed    : [0]\n".getBytes("UTF-8"));
      Thread flusher = new Thread(new Runnable() {
         @Override
         public void run() {
            los.flush();
         }
      });
      Thread closer = new Thread(new Runnable() {
         @Override
         public void run() {
            try {
               los.close();
            } catch (IOException e) {
               fail(e.getMessage());
            }
         }
      });
      flusher.start();
      closer.start();
      flusher.join();
      closer.join();
      synchronized (records) {
         int hits = 0;
         for (LogRecord record : records) {
            if (record.getMessage() != null
                  && record.getMessage().contains("Num Missed")) {
               hits++;
            }
         }
         assertEquals(1, hits);
      }
   }
}
