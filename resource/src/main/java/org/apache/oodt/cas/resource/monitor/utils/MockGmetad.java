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

package org.apache.oodt.cas.resource.monitor.utils;

//JDK imports
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author rajith
 * @author mattmann
 * @version $Revision$
 *
 * Ganglia meta daemon mock server
 */
public class MockGmetad implements Runnable {

    private int socket;
    private File fakeXMLDump;
    private volatile boolean testFinished;

    /** Counted down once the port is actually bound. */
    private final CountDownLatch listening = new CountDownLatch(1);

    private volatile ServerSocket serverSocket;

    public MockGmetad(int socket, String filePath){
        this.socket = socket;
        this.fakeXMLDump = new File(filePath);
        this.testFinished = false;
    }

    /**
     * Waits until the server is accepting connections.
     *
     * The socket is bound on the server's own thread, so starting that thread
     * and connecting straight away is a race: whoever begins the conversation
     * first wins. It is a race an idle machine reliably wins and a loaded one
     * does not, which is the sort that passes everywhere except CI.
     *
     * @param timeoutMillis
     *          How long to wait.
     * @return True if the server is listening.
     */
    public boolean awaitListening(long timeoutMillis) {
        try {
            return listening.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops the server and releases the port.
     *
     * Setting the flag alone never stopped anything: the loop spends its life
     * blocked in accept and does not look at the flag again until a connection
     * arrives. Closing the socket is what unblocks it. Without this every test
     * left a thread holding the port, and the ones that followed failed to
     * bind and silently gave up, so a whole suite depended on the first
     * server it ever started.
     */
    public void close() {
        testFinished = true;
        ServerSocket toClose = this.serverSocket;
        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the port is going away regardless.
            }
        }
    }

    public void stop(){
        close();
    }

    @Override
    public void run() {
        try {
            this.serverSocket = new ServerSocket(socket);
            listening.countDown();
            FileInputStream fis = null;
            OutputStream os = null;


            while (!testFinished) {
                Socket sock = serverSocket.accept();
                try {
                    byte[] xmlByteArray = new byte[1024];
                    fis = new FileInputStream(fakeXMLDump);
                    os = sock.getOutputStream();

                    int count;
                    while ((count = fis.read(xmlByteArray)) >= 0) {
                        os.write(xmlByteArray, 0, count);
                    }
                    os.flush();
                } finally {
                    // Guarded rather than asserted. Assertions are off by
                    // default, so a stream that failed to open left these
                    // throwing a NullPointerException over whatever had
                    // actually gone wrong.
                    if (fis != null) {
                        fis.close();
                    }
                    if (os != null) {
                        os.close();
                    }
                    sock.close();
                }
            }
        } catch (FileNotFoundException ignored) {
            //Exception ignored
        } catch (IOException ignored) {
            //Exception ignored
        } finally {
            // So a caller waiting to be told the server is up is not left
            // waiting out its whole timeout when the bind failed.
            listening.countDown();
        }

    }
    
    public static void main(String [] args){
    	String xmlPath;
    	int serverPort;
    	final String usage = "java MockGmetad <xml path> <port>\n";
    	
    	if (args.length != 2){
    		System.err.println(usage);
    		System.exit(1);
    	}
    	
    	xmlPath = args[0];
    	serverPort = Integer.valueOf(args[1]);
    	
    	MockGmetad gmetad = new MockGmetad(serverPort, xmlPath);
    	ThreadLocal<MockGmetad> mockGmetad = new ThreadLocal<MockGmetad>();
    	mockGmetad.set(gmetad);
    	Thread mockGmetadServer = new Thread(mockGmetad.get());
        mockGmetadServer.start();
    }
}
