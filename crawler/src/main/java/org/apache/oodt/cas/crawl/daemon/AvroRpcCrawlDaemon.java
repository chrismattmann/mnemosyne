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
import org.apache.avro.ipc.NettyServer;
import org.apache.avro.ipc.Server;
import org.apache.avro.ipc.specific.SpecificResponder;

//OODT imports
import org.apache.oodt.cas.crawl.ProductCrawler;
import org.apache.oodt.cas.crawl.structs.avrotypes.AvroCrawlDaemon;

//JDK imports
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The crawl daemon, speaking Avro.
 *
 * The same daemon as {@link CrawlDaemon} over a transport that is still
 * maintained. Apache XML-RPC has had no release in over a decade, and it is
 * the last thing keeping commons-httpclient 3.x, and its unfixed
 * CVE-2012-5783, on the classpath.
 *
 * The remote surface is unchanged: the same six calls the controller has
 * always made. Porting a transport is not the moment to redesign the thing
 * being transported.
 *
 * @author mattmann
 */
public class AvroRpcCrawlDaemon implements AvroCrawlDaemon {

    private static final Logger LOG = Logger
        .getLogger(AvroRpcCrawlDaemon.class.getName());

    public static final double MILLIS_PER_SECOND = 1000.0;

    private boolean running = true;

    private int waitInterval = -1;

    private int numCrawls = 0;

    private long milisCrawling = 0L;

    private ProductCrawler crawler = null;

    private int daemonPort = 9999;

    private Server server;

    public AvroRpcCrawlDaemon(int wait, ProductCrawler crawler, int port) {
        this.waitInterval = wait;
        this.crawler = crawler;
        this.daemonPort = port;
    }

    public void startCrawling() {
        this.server = new NettyServer(
            new SpecificResponder(AvroCrawlDaemon.class, this),
            new InetSocketAddress(this.daemonPort));
        this.server.start();

        LOG.log(Level.INFO, "Crawl Daemon started by "
            + System.getProperty("user.name", "unknown"));

        while (running) {
            long timeBefore = System.currentTimeMillis();
            crawler.crawl();
            long timeAfter = System.currentTimeMillis();
            milisCrawling += (timeAfter - timeBefore);
            numCrawls++;

            LOG.log(Level.INFO, "Sleeping for: [" + waitInterval + "] seconds");
            try {
                Thread.sleep(waitInterval * 1000L);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOG.log(Level.INFO, "Crawl Daemon: Shutting down gracefully");
        LOG.log(Level.INFO, "Num Crawls: [" + this.numCrawls + "]");
        LOG.log(Level.INFO, "Total time spent crawling: ["
            + (this.milisCrawling / MILLIS_PER_SECOND) + "] seconds");
        LOG.log(Level.INFO, "Average Crawl Time: ["
            + (this.getAverageCrawlTime() / MILLIS_PER_SECOND) + "] seconds");
        this.server.close();
    }

    @Override
    public double getAverageCrawlTime() {
        // Guard against the first call arriving before any crawl has finished.
        // The XML-RPC daemon divided regardless and answered NaN.
        if (this.numCrawls == 0) {
            return 0.0;
        }
        return (this.milisCrawling * 1.0) / (this.numCrawls * 1.0);
    }

    @Override
    public int getMilisCrawling() {
        return (int) this.milisCrawling;
    }

    @Override
    public int getNumCrawls() {
        return this.numCrawls;
    }

    @Override
    public int getWaitInterval() {
        return this.waitInterval;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public boolean stop() {
        this.running = false;
        return true;
    }

    public ProductCrawler getCrawler() {
        return crawler;
    }

    public void setCrawler(ProductCrawler crawler) {
        this.crawler = crawler;
    }

    public void setMilisCrawling(long milisCrawling) {
        this.milisCrawling = milisCrawling;
    }

    public void setNumCrawls(int numCrawls) {
        this.numCrawls = numCrawls;
    }

    public void setWaitInterval(int waitInterval) {
        this.waitInterval = waitInterval;
    }
}
