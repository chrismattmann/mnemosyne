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

import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.util.ContentStreamBase;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Solr transport for {@link SolrCatalog}.
 *
 * Same client SolrIndexer uses: SolrJ 10 {@link HttpJdkSolrClient}. The
 * previous implementation posted hand-built XML over Apache HttpClient and
 * parsed XML responses. Solr 4 defaulted to XML; Solr 10 defaults to JSON,
 * so getNumProducts failed with "Content is not allowed in prolog".
 *
 * Updates still send the XML documents {@link DefaultProductSerializer}
 * already produces (atomic set/add/null). Queries go through SolrJ and
 * come back as SolrDocuments, not XML.
 */
public class SolrClient {

	private final org.apache.solr.client.solrj.SolrClient server;
	private final ProductSerializer productSerializer;
	private final Logger LOG = Logger.getLogger(this.getClass().getName());

	public SolrClient(final String url) {
		this(url, new DefaultProductSerializer());
	}

	public SolrClient(final String url, ProductSerializer productSerializer) {
		this.productSerializer = productSerializer;
		try {
			new URI(url).toURL();
			this.server = new HttpJdkSolrClient.Builder(url).build();
		} catch (MalformedURLException | URISyntaxException
				| IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"Could not connect to Solr server " + url, e);
		}
	}

	public String index(List<String> docs, boolean commit, String mimeType)
			throws CatalogException {
		try {
			StringBuilder message = new StringBuilder("<add>");
			for (String doc : docs) {
				message.append(doc);
			}
			message.append("</add>");
			ContentStreamUpdateRequest req = new ContentStreamUpdateRequest(
					"/update");
			req.addContentStream(new ContentStreamBase.StringStream(
					message.toString(), "application/xml"));
			if (commit) {
				req.setParam("commit", "true");
			}
			server.request(req);
			return "";
		} catch (SolrServerException | IOException e) {
			LOG.log(Level.SEVERE, e.getMessage(), e);
			throw new CatalogException(e.getMessage(), e);
		}
	}

	public String delete(String id, boolean commit) throws CatalogException {
		try {
			server.deleteById(id);
			if (commit) {
				server.commit();
			}
			return "";
		} catch (SolrServerException | IOException e) {
			LOG.log(Level.SEVERE, e.getMessage(), e);
			throw new CatalogException(e.getMessage(), e);
		}
	}

	public QueryResponse queryProductById(String id) throws CatalogException {
		ConcurrentHashMap<String, String[]> params = new ConcurrentHashMap<String, String[]>();
		params.put("q", new String[] { Parameters.PRODUCT_ID + ":" + id });
		return query(params);
	}

	public QueryResponse queryProductByName(String name) throws CatalogException {
		ConcurrentHashMap<String, String[]> params = new ConcurrentHashMap<String, String[]>();
		params.put("q", new String[] {
				Parameters.PRODUCT_NAME + ":" + ClientUtils.escapeQueryChars(name) });
		return query(params);
	}

	public QueryResponse queryProductsByDate(int n) throws CatalogException {
		ConcurrentHashMap<String, String[]> params = new ConcurrentHashMap<String, String[]>();
		params.put("q", new String[] { "*:*" });
		params.put("rows", new String[] { "" + n });
		params.put("sort", new String[] {
				Parameters.PRODUCT_RECEIVED_TIME + " desc" });
		return query(params);
	}

	public QueryResponse queryProductsByDateAndType(int n, ProductType type)
			throws CatalogException {
		ConcurrentHashMap<String, String[]> params = new ConcurrentHashMap<String, String[]>();
		params.put("q", new String[] {
				Parameters.PRODUCT_TYPE_NAME + ":" + type.getName() });
		params.put("rows", new String[] { "" + n });
		params.put("sort", new String[] {
				Parameters.PRODUCT_RECEIVED_TIME + " desc" });
		return query(params);
	}

	public QueryResponse query(Map<String, String[]> parameters)
			throws CatalogException {
		try {
			SolrQuery solrQuery = toSolrQuery(parameters);
			org.apache.solr.client.solrj.response.QueryResponse rsp = server
					.query(solrQuery);
			return productSerializer.deserialize(rsp);
		} catch (SolrServerException | IOException e) {
			LOG.log(Level.SEVERE, e.getMessage(), e);
			throw new CatalogException(e.getMessage(), e);
		}
	}

	void close() {
		try {
			server.close();
		} catch (IOException e) {
			LOG.warning("Unable to close the Solr client: " + e.getMessage());
		}
	}

	private SolrQuery toSolrQuery(Map<String, String[]> parameters) {
		SolrQuery query = new SolrQuery();
		String[] q = parameters.get("q");
		query.setQuery(q != null && q.length > 0 ? q[0] : "*:*");
		String[] fq = parameters.get("fq");
		if (fq != null) {
			query.setFilterQueries(fq);
		}
		String[] rows = parameters.get("rows");
		if (rows != null && rows.length > 0) {
			query.setRows(Integer.parseInt(rows[0]));
		}
		String[] start = parameters.get("start");
		if (start != null && start.length > 0) {
			query.setStart(Integer.parseInt(start[0]));
		}
		String[] fl = parameters.get("fl");
		if (fl != null && fl.length > 0) {
			query.setFields(fl);
		}
		String[] sort = parameters.get("sort");
		if (sort != null && sort.length > 0) {
			String spec = sort[0].trim();
			int space = spec.lastIndexOf(' ');
			if (space > 0) {
				String field = spec.substring(0, space).trim();
				String dir = spec.substring(space + 1).trim();
				query.setSort(field, "desc".equalsIgnoreCase(dir)
						? SolrQuery.ORDER.desc : SolrQuery.ORDER.asc);
			} else {
				query.setSort(spec, SolrQuery.ORDER.asc);
			}
		}
		return query;
	}
}
