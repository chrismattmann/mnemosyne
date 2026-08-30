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

package org.apache.oodt.cas.filemgr.catalog.solr;

import org.apache.oodt.cas.filemgr.catalog.Catalog;
import org.apache.oodt.cas.filemgr.catalog.LuceneCatalog;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Catalog.close defaults to doing nothing, which suits an implementation that
 * holds nothing between calls. SolrCatalog now keeps an HttpJdkSolrClient for
 * its lifetime, so it has to release it: FileManager shuts a catalog down
 * through closeQuietly(catalog), and without an override a restart would leak
 * the client's connection pool and executor.
 */
public class TestSolrCatalogReleasesItsClient {

  @Test
  public void solrCatalogOverridesClose() throws Exception {
    Method close = SolrCatalog.class.getDeclaredMethod("close");

    assertNotNull("SolrCatalog must release the client it holds", close);
    assertEquals("and it must be the Catalog contract, not a private helper",
        SolrCatalog.class, close.getDeclaringClass());
  }

  /** The catalog that already does this, as the precedent being followed. */
  @Test
  public void luceneCatalogAlreadyDoesTheSame() throws Exception {
    assertEquals(LuceneCatalog.class,
        LuceneCatalog.class.getDeclaredMethod("close").getDeclaringClass());
  }

  /**
   * An implementation holding no resources still need not override it, which
   * is what the default is for.
   */
  @Test
  public void theContractStillDefaultsToDoingNothing() throws Exception {
    Method close = Catalog.class.getMethod("close");

    assertNotNull(close);
    assertEquals(Catalog.class, close.getDeclaringClass());
  }
}
