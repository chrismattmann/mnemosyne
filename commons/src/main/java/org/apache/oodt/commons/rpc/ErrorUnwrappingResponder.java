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
package org.apache.oodt.commons.rpc;

import java.io.IOException;

import org.apache.avro.AvroRemoteException;
import org.apache.avro.Schema;
import org.apache.avro.io.Encoder;
import org.apache.avro.ipc.specific.SpecificResponder;
import org.apache.avro.specific.SpecificExceptionBase;

/**
 * A {@link SpecificResponder} that writes the value carried by an
 * {@link AvroRemoteException} rather than the exception object itself.
 *
 * <p>
 * When a handler throws, {@code Responder.respond} hands the caught exception
 * straight to {@code writeError}, and {@code SpecificResponder.writeError}
 * passes it to the datum writer unchanged. None of these protocols declare an
 * error type, so the error schema is the implicit <code>["string"]</code> and
 * an {@code AvroRemoteException} matches nothing in it. Avro then fails while
 * reporting the failure:
 *
 * <pre>
 * AvroRuntimeException: Unknown datum type org.apache.avro.AvroRemoteException:
 *   org.apache.avro.AvroRemoteException: Failed to read '16' bytes from file ...
 * </pre>
 *
 * The server-side message survives inside that text, but the caller sees a
 * serialization complaint rather than the fault, and the top line points at
 * Avro rather than at what actually went wrong.
 * {@link AvroRemoteException#getValue()} is the string the server meant to
 * send, and that does match <code>["string"]</code>.
 *
 * <p>
 * This does not make the original exception type catchable again. Doing that
 * means declaring error records in the {@code .avdl} protocols and having the
 * client map them back, which is a much larger change; this makes the message
 * arrive at all.
 */
public class ErrorUnwrappingResponder extends SpecificResponder {

    public ErrorUnwrappingResponder(Class<?> iface, Object impl) {
        super(iface, impl);
    }

    @Override
    public void writeError(Schema schema, Object error, Encoder out)
            throws IOException {
        super.writeError(schema, datumFor(error), out);
    }

    /**
     * A declared error is written as itself: the protocol has a schema for it,
     * which is the whole point of declaring it, and unwrapping it here would
     * hand the writer the record's {@code value} instead of the record.
     * Anything else is an undeclared failure whose only writable form is the
     * string inside it.
     */
    private static Object datumFor(Object error) {
        if (error instanceof SpecificExceptionBase) {
            return error;
        }
        if (error instanceof AvroRemoteException) {
            return ((AvroRemoteException) error).getValue();
        }
        return error;
    }
}
