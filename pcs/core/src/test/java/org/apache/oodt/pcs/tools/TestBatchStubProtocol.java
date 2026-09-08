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

import org.apache.avro.specific.SpecificData;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroIntrBatchmgr;
import org.apache.oodt.cas.resource.system.extern.AvroRpcBatchStub;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
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
