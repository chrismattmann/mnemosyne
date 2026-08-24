/**
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

package org.apache.oodt.cas.crawl.daemon;

//Avro imports
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;

//OODT imports
import org.apache.oodt.cas.crawl.structs.avrotypes.AvroCrawlDaemon;
import org.apache.oodt.cas.crawl.structs.exceptions.CrawlException;

//JDK imports
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Talks to an {@link AvroRpcCrawlDaemon}.
 *
 * The same six questions {@link CrawlDaemonController} asked over XML-RPC,
 * asked over Avro instead.
 *
 * Unlike its predecessor this is {@link Closeable}, because the transceiver
 * holds a socket. The XML-RPC client did not, so nothing in the older code
 * closed anything; a caller that constructs one of these should close it.
 *
 * @author mattmann
 */
public class AvroRpcCrawlDaemonController implements Closeable {

    private final Transceiver transceiver;

    private final AvroCrawlDaemon proxy;

    public AvroRpcCrawlDaemonController(String crawlUrlStr)
            throws InstantiationException {
        try {
            URL url = new URL(crawlUrlStr);
            this.transceiver = new NettyTransceiver(
                new InetSocketAddress(url.getHost(), url.getPort()));
            this.proxy = SpecificRequestor.getClient(AvroCrawlDaemon.class,
                this.transceiver);
        } catch (MalformedURLException e) {
            throw new InstantiationException(e.getMessage());
        } catch (IOException e) {
            throw new InstantiationException(e.getMessage());
        }
    }

    public double getAverageCrawlTime() throws CrawlException {
        try {
            return proxy.getAverageCrawlTime();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getMilisCrawling() throws CrawlException {
        try {
            return proxy.getMilisCrawling();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getNumCrawls() throws CrawlException {
        try {
            return proxy.getNumCrawls();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public int getWaitInterval() throws CrawlException {
        try {
            return proxy.getWaitInterval();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public boolean isRunning() throws CrawlException {
        try {
            return proxy.isRunning();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    public void stop() throws CrawlException {
        try {
            proxy.stop();
        } catch (Exception e) {
            throw new CrawlException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        if (this.transceiver != null) {
            this.transceiver.close();
        }
    }
}
