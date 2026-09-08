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
package org.apache.oodt.pcs.tools;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.specific.SpecificData;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroIntrBatchmgr;
import org.apache.oodt.cas.resource.system.extern.AvroRpcBatchStub;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Which class names the batch stub's protocol.
 *
 * <p>The health monitor asked Avro for a client of {@link AvroRpcBatchStub},
 * which is the server implementation and not a generated protocol interface.
 * Avro answers that with an unchecked {@code AvroRuntimeException}, so the
 * health report returned 500 rather than reporting a node down: the PCS
 * status on the OPSUI front page and the resources view both went blank as
 * soon as a batch stub existed to check.</p>
 */
public class TestBatchStubProtocol {

  @Test
  public void testTheProtocolInterfaceIsUsableAsAnAvroClient() {
    assertNotNull("AvroIntrBatchmgr must be a Specific protocol",
        SpecificData.get().getProtocol(AvroIntrBatchmgr.class));
  }

  @Test
  public void testTheProtocolDeclaresIsAlive() throws Exception {
    assertNotNull("the health check calls isAlive over this protocol",
        AvroIntrBatchmgr.class.getMethod("isAlive"));
  }

  @Test
  public void testAvroCloseStillReleasesTheSharedFactory() throws Exception {
    // Why the monitor must not call close(). In avro-ipc 1.8.2 both close
    // paths end in ChannelFactory.releaseExternalResources(), so closing one
    // connection tears down a factory shared with every later check: the
    // first node answered and every node after it reported down. If a future
    // Avro stops doing this, this test fails and the workaround can go.
    assertNotNull("close(boolean) is the path the monitor used to take",
        NettyTransceiver.class.getMethod("close", boolean.class));
  }

  @Test
  public void testTheMonitorClosesWithoutReleasingTheSharedFactory()
      throws Exception {
    // A behavioural test would need two live stubs, so this pins the call
    // instead: the monitor shares one NioClientSocketChannelFactory across
    // every node it checks, and an ordinary close destroys it.
    String source = new String(Files.readAllBytes(Paths.get(
        "src/main/java/org/apache/oodt/pcs/tools/PCSHealthMonitor.java")),
        "UTF-8");
    assertTrue("the health check must close with closeSharing",
        source.contains("AvroTransceivers.closeSharing(client)"));
    assertFalse("an ordinary close releases the shared factory",
        source.contains("client.close("));
  }

  @Test
  public void testTheServerImplementationIsNotAProtocol() {
    // The mistake this guards against. If this ever stops throwing, Avro's
    // rules changed and the health monitor's choice deserves a fresh look.
    try {
      SpecificData.get().getProtocol(AvroRpcBatchStub.class);
      fail("expected AvroRpcBatchStub to be rejected as a protocol");
    } catch (RuntimeException expected) {
      // what the monitor used to let escape as a 500
    }
  }
}
