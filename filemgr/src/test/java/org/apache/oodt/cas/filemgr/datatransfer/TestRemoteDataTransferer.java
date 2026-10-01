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
package org.apache.oodt.cas.filemgr.datatransfer;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.DataTransferException;
import org.apache.oodt.cas.filemgr.system.FileManagerClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Vector;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether a remote transfer moves the bytes, and says so when it does not.
 *
 * <p>This transferer had no tests. It is the one the file manager offers
 * for moving a product to a machine that cannot see the source, and every
 * other transferer in this package is covered.</p>
 */
public class TestRemoteDataTransferer {

  private File source;
  private ByteArrayOutputStream received;
  private FileManagerClient client;
  private RemoteDataTransferer transferer;
  /** Every destination string handed to the far end, in order. */
  private Vector<String> destinationsGiven;
  /** What the overwrite was told to remove. */
  private String removedPath;

  private String firstDestinationGiven() {
    assertTrue("nothing was sent to the far end at all",
        !destinationsGiven.isEmpty());
    return destinationsGiven.get(0);
  }

  /** What the far end ended up with, assembled as the server would. */
  private byte[] delivered() {
    return received.toByteArray();
  }

  private void writeSource(int bytes) throws Exception {
    source = File.createTempFile("remote-transfer", ".bin");
    byte[] content = new byte[bytes];
    for (int i = 0; i < bytes; i++) {
      content[i] = (byte) (i % 251);
    }
    FileOutputStream out = new FileOutputStream(source);
    out.write(content);
    out.close();
  }

  private Product productFor(File file) {
    Product product = new Product();
    product.setProductName(file.getName());
    Reference reference = new Reference();
    reference.setOrigReference(file.toURI().toString());
    reference.setDataStoreReference(
        new File(file.getParentFile(), "delivered-" + file.getName())
            .toURI().toString());
    Vector<Reference> references = new Vector<Reference>();
    references.add(reference);
    product.setProductReferences(references);
    return product;
  }

  @Before
  public void setUp() throws Exception {
    received = new ByteArrayOutputStream();
    destinationsGiven = new Vector<String>();
    removedPath = null;
    client = mock(FileManagerClient.class);
    doAnswer(new Answer<Boolean>() {
      public Boolean answer(InvocationOnMock call) {
        removedPath = (String) call.getArguments()[0];
        return Boolean.TRUE;
      }
    }).when(client).removeFile(anyString());
    // The server appends what it is given, which is what the real one does
    // when the destination already exists.
    doAnswer(new Answer<Void>() {
      public Void answer(InvocationOnMock call) {
        destinationsGiven.add((String) call.getArguments()[0]);
        byte[] data = (byte[]) call.getArguments()[1];
        int offset = (Integer) call.getArguments()[2];
        int numBytes = (Integer) call.getArguments()[3];
        received.write(data, offset, numBytes);
        return null;
      }
    }).when(client).transferFile(anyString(), any(byte[].class), anyInt(),
        anyInt());

    transferer = new RemoteDataTransferer(1024);
    transferer.setFileManagerUrl(new URL("http://localhost:9000"));
    transferer.setFileManagerClient(client);
  }

  @After
  public void tearDown() {
    if (source != null) {
      source.delete();
    }
  }

  /**
   * What travels as the destination.
   *
   * <p>A Windows node sent the destination as destFile.getAbsolutePath(), a
   * path in its own syntax, to a File Manager on macOS. On the receiver
   * "C:\\Users\\...\\chunk-00001.json" is not absolute, so it resolved against
   * the File Manager's working directory and 231485 bytes landed in a file
   * named after the whole foreign path, under filemgr/bin. Every check in the
   * sequence passed and the product was catalogued RECEIVED.</p>
   *
   * <p>The destination belongs to the File Manager, so it travels as the
   * reference it already is and only the File Manager resolves it.</p>
   */
  @Test
  public void testTheDestinationTravelsAsAReferenceNotANativePath()
      throws Exception {
    writeSource(400);
    Product product = productFor(source);
    String expected = product.getProductReferences().get(0)
        .getDataStoreReference();

    transferer.transferProduct(product);

    assertEquals("the data store reference is what the far end is given",
        expected, firstDestinationGiven());
    assertTrue("a reference, not a path: it carries its scheme",
        firstDestinationGiven().startsWith("file:"));
  }

  @Test
  public void testTheOverwriteTargetsTheSameReference() throws Exception {
    writeSource(400);
    Product product = productFor(source);
    String expected = product.getProductReferences().get(0)
        .getDataStoreReference();

    transferer.transferProduct(product);

    // removeFile is the overwrite, and it has to name the same thing the
    // write names. Sent as a native path it named a different file on a
    // receiver whose syntax differed.
    assertEquals(expected, removedPath);
  }

  /**
   * A refused destination is not a completed transfer.
   *
   * <p>FileManagerClient declares transferFile void, so the server's boolean
   * had nowhere to go: RemoteDataTransferer could not see a refusal and
   * reported the product transferred. The client raises now, and the raise has
   * to reach the caller rather than being logged and swallowed.</p>
   */
  @Test
  public void testARefusalByTheFarEndIsNotSwallowed() throws Exception {
    writeSource(400);
    doThrow(new DataTransferException("refused")).when(client)
        .transferFile(anyString(), any(byte[].class), anyInt(), anyInt());
    try {
      transferer.transferProduct(productFor(source));
      fail("a refused transfer was reported as a successful one");
    } catch (DataTransferException expected) {
      // what the caller needs: the product did not transfer
    }
  }

  @Test
  public void testAFileSmallerThanAChunkArrivesWhole() throws Exception {
    writeSource(400);
    transferer.transferProduct(productFor(source));
    assertEquals(400, delivered().length);
  }

  @Test
  public void testAFileOfExactlyOneChunkArrivesWhole() throws Exception {
    writeSource(1024);
    transferer.transferProduct(productFor(source));
    assertEquals(1024, delivered().length);
  }

  @Test
  public void testAFileOfManyChunksArrivesInOrderAndIntact() throws Exception {
    // The case a single chunk cannot show: that the parts are concatenated
    // rather than each written over the last.
    writeSource(1024 * 7 + 13);
    byte[] expected = new byte[1024 * 7 + 13];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = (byte) (i % 251);
    }
    transferer.transferProduct(productFor(source));
    assertEquals(expected.length, delivered().length);
    assertArrayEquals("the chunks did not reassemble into the original",
        expected, delivered());
  }

  @Test
  public void testAnEmptyFileIsNotAnError() throws Exception {
    writeSource(0);
    transferer.transferProduct(productFor(source));
    assertEquals(0, delivered().length);
  }

  @Test
  public void testAFailedChunkIsReportedRatherThanPassedOver()
      throws Exception {
    // The whole point. A transfer that could not write must not report
    // itself complete: the product is catalogued on the strength of that
    // report, and a file that never arrived then looks transferred.
    writeSource(1024 * 4);
    doThrow(new DataTransferException("disk full"))
        .when(client).transferFile(anyString(), any(byte[].class), anyInt(),
            anyInt());
    try {
      transferer.transferProduct(productFor(source));
      fail("a failed transfer reported success");
    } catch (DataTransferException expected) {
      assertTrue("the message should name the file",
          expected.getMessage().contains(source.getName())
              || expected.getMessage().contains("disk full"));
    }
  }

  @Test
  public void testAnUnreadableSourceIsReportedRatherThanPassedOver()
      throws Exception {
    writeSource(1024);
    Product product = productFor(source);
    // The source disappears between cataloguing and transfer.
    assertTrue(source.delete());
    try {
      transferer.transferProduct(product);
      fail("a transfer of a file that is not there reported success");
    } catch (Exception expected) {
      assertTrue(expected instanceof DataTransferException
          || expected instanceof java.io.IOException);
    }
    source = null;
  }

  @Test
  public void testADirectoryReferenceIsSkippedNotTransferred()
      throws Exception {
    File directory = File.createTempFile("remote-transfer-dir", "");
    assertTrue(directory.delete());
    assertTrue(directory.mkdirs());
    try {
      transferer.transferProduct(productFor(directory));
      assertEquals("a directory has no bytes to send", 0,
          delivered().length);
    } finally {
      directory.delete();
    }
  }
}
