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

import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.Transceiver;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Closing an Avro client transport without taking the shared Netty threads
 * with it.
 *
 * Every one of these clients hands its transceiver a process-wide
 * ChannelFactory, so that N clients cost N sockets rather than N thread
 * pools. NettyTransceiver.close() does not know that: it calls
 * channelFactory.releaseExternalResources() in a finally block, so the first
 * client to close would shut down the I/O threads every other client is still
 * using. The alternative -- never closing -- is what leaked a socket per
 * client.
 *
 * What close() does either side of that release is set the stopping flag and
 * disconnect; both are private, so this reaches them by reflection and leaves
 * the factory alone. Ugly, and narrower than either of the two behaviours
 * Avro offers.
 */
public final class AvroTransceivers {

    private AvroTransceivers() {
    }

    /**
     * Close a transceiver, leaving the ChannelFactory it shares running.
     *
     * @param transceiver the transport to close; null is ignored
     * @throws IOException if the transport could not be closed
     */
    public static void closeSharing(Transceiver transceiver) throws IOException {
        if (transceiver == null) {
            return;
        }
        if (!(transceiver instanceof NettyTransceiver)) {
            // Nothing shared to protect: close it the ordinary way.
            transceiver.close();
            return;
        }

        try {
            Field stopping = NettyTransceiver.class.getDeclaredField("stopping");
            stopping.setAccessible(true);
            stopping.set(transceiver, Boolean.TRUE);

            Method disconnect = NettyTransceiver.class.getDeclaredMethod(
                "disconnect", boolean.class, boolean.class, Throwable.class);
            disconnect.setAccessible(true);
            disconnect.invoke(transceiver, true, true, null);
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException e) {
            throw new IOException("Unable to close shared Avro Netty transceiver", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("Unable to close shared Avro Netty transceiver", cause);
        }
    }
}
