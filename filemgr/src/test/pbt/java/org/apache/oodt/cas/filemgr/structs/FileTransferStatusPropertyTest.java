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

package org.apache.oodt.cas.filemgr.structs;

import static dev.hegel.Generators.longs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;

/**
 * Properties for {@link FileTransferStatus#computePctTransferred()}, the number
 * a client polling an in-flight ingest turns into a progress report.
 *
 * <p>The method's javadoc calls it "the percentage of the file that has been
 * transferred so far". Whatever scale it is on, the caller's use of it is the
 * same in every case: multiply, round, and show it to somebody. That only works
 * if the value is a real number that grows with the bytes transferred and stops
 * at whole.
 */
class FileTransferStatusPropertyTest {

  private static FileTransferStatus status(long fileSize, long bytesTransferred) {
    Reference ref = new Reference();
    ref.setOrigReference("file:/data/f.dat");
    ref.setDataStoreReference("file:/archive/f.dat");
    ref.setFileSize(fileSize);
    Product parent = Product.getDefaultFlatProduct("f.dat", "type-1");
    return new FileTransferStatus(ref, fileSize, bytesTransferred, parent);
  }

  /**
   * A partially transferred file is somewhere between not started and finished.
   *
   * <p>Anything outside that range is a progress bar that runs backwards or off
   * the end of its track.
   */
  @HegelTest
  void progressStaysWithinItsRange(TestCase tc) {
    long fileSize = tc.draw(longs().min(1).max(1_000_000_000L), "fileSize");
    long bytesTransferred = tc.draw(longs().min(0).max(fileSize), "bytesTransferred");

    double pct = status(fileSize, bytesTransferred).computePctTransferred();
    tc.note("pct = " + pct);

    assertTrue(pct >= 0.0, "progress is negative: " + pct);
    assertTrue(pct <= 1.0, "progress exceeds the whole file: " + pct);
  }

  /** Transferring more bytes of the same file must never report less progress. */
  @HegelTest
  void progressNeverGoesBackwards(TestCase tc) {
    long fileSize = tc.draw(longs().min(1).max(1_000_000_000L), "fileSize");
    long fewer = tc.draw(longs().min(0).max(fileSize), "fewer");
    long more = tc.draw(longs().min(fewer).max(fileSize), "more");

    double before = status(fileSize, fewer).computePctTransferred();
    double after = status(fileSize, more).computePctTransferred();

    assertTrue(after >= before, "progress fell from " + before + " to " + after);
  }

  /** A file whose every byte has arrived is finished. */
  @HegelTest
  void aCompletedTransferReportsWhole(TestCase tc) {
    long fileSize = tc.draw(longs().min(1).max(1_000_000_000L), "fileSize");

    assertEquals(1.0, status(fileSize, fileSize).computePctTransferred(), 1e-9);
  }

  /**
   * An empty file must report a number, not a non-number.
   *
   * <p>Zero-byte files are ordinary — a touched placeholder, a flag file
   * alongside a data granule, a failed producer run that still wrote its
   * output. Such a file has nothing left to transfer, so its progress is
   * whole; what a caller must not be handed is {@code NaN}, which compares
   * false against every threshold it is tested against and so makes a poll
   * loop that waits for completion wait forever.
   */
  @HegelTest
  void anEmptyFileDoesNotProduceANonNumber(TestCase tc) {
    long bytesTransferred = tc.draw(longs().min(0).max(0), "bytesTransferred");

    double pct = status(0L, bytesTransferred).computePctTransferred();
    tc.note("pct = " + pct);

    assertTrue(
        !Double.isNaN(pct) && !Double.isInfinite(pct),
        "an empty file reported its progress as " + pct);
  }
}
