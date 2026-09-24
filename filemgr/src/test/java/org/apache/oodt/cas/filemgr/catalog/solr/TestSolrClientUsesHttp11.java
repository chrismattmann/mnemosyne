/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.oodt.cas.filemgr.catalog.solr;

import junit.framework.TestCase;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;

import java.lang.reflect.Field;

/** Verifies the transport setting required by native Windows Solr updates. */
public class TestSolrClientUsesHttp11 extends TestCase {

	public void testHttp11IsForced() throws Exception {
		SolrClient client = new SolrClient("http://localhost:8983/solr/test");
		try {
			Field serverField = SolrClient.class.getDeclaredField("server");
			serverField.setAccessible(true);
			HttpJdkSolrClient server =
					(HttpJdkSolrClient) serverField.get(client);

			Field forceField = HttpJdkSolrClient.class
					.getDeclaredField("forceHttp11");
			forceField.setAccessible(true);
			assertTrue("Solr updates must use HTTP/1.1",
					forceField.getBoolean(server));
		} finally {
			client.close();
		}
	}
}
