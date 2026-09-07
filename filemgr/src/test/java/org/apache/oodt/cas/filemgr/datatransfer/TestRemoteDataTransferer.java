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
    client = mock(FileManagerClient.class);
    when(client.removeFile(anyString())).thenReturn(true);
    // The server appends what it is given, which is what the real one does
    // when the destination already exists.
    doAnswer(new Answer<Void>() {
      public Void answer(InvocationOnMock call) {
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
