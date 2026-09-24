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

/**
 * Verifies the transport setting required by native Windows Solr updates.
 *
 * Solr's h2c path resets update requests on native Windows with
 * RST_STREAM, so the client is built with useHttp1_1. SolrJ exposes no
 * public accessor for the resulting transport, so the only way to assert
 * the setting took effect is to read the private field the builder sets.
 *
 * That couples this test to SolrJ's internals. The coupling is deliberate
 * and the failure it produces is handled below: if a SolrJ upgrade renames
 * or removes the field, a bare NoSuchFieldException says nothing about
 * what changed or what to do, so it is caught and re-reported as the
 * question a reader actually needs answered.
 */
public class TestSolrClientUsesHttp11 extends TestCase {

	private static final String FORCE_HTTP11_FIELD = "forceHttp11";

	public void testHttp11IsForced() throws Exception {
		SolrClient client = new SolrClient("http://localhost:8983/solr/test");
		try {
			Field serverField = SolrClient.class.getDeclaredField("server");
			serverField.setAccessible(true);
			HttpJdkSolrClient server =
					(HttpJdkSolrClient) serverField.get(client);

			Field forceField;
			try {
				forceField = HttpJdkSolrClient.class
						.getDeclaredField(FORCE_HTTP11_FIELD);
			} catch (NoSuchFieldException e) {
				fail("SolrJ no longer has HttpJdkSolrClient."
						+ FORCE_HTTP11_FIELD + ", so this test cannot see"
						+ " which transport the client was built with."
						+ " Check whether Builder.useHttp1_1 still forces"
						+ " HTTP/1.1 -- the h2c reset on native Windows is"
						+ " what it is there to prevent -- and reach the"
						+ " setting by whatever SolrJ exposes now.");
				return;
			}
			forceField.setAccessible(true);
			assertTrue("Solr updates must use HTTP/1.1",
					forceField.getBoolean(server));
		} finally {
			client.close();
		}
	}
}
