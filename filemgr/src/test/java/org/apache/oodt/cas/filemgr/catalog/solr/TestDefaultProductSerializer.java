package org.apache.oodt.cas.filemgr.catalog.solr;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;

import junit.framework.TestCase;

/**
 * SolrJ document mapping for SolrCatalog. No Solr server.
 */
public class TestDefaultProductSerializer extends TestCase {

	public void testDeserializeSolrDocumentCoreFields() {
		SolrDocument doc = new SolrDocument();
		doc.setField("id", "abc-123");
		doc.setField(Parameters.PRODUCT_ID, "abc-123");
		doc.setField(Parameters.PRODUCT_NAME, "filelist_chunk_0.txt");
		doc.setField(Parameters.PRODUCT_TYPE_NAME, "ChunkList");
		doc.setField(Parameters.PRODUCT_TYPE_ID, "urn:oodt:ChunkList");
		doc.addField(Parameters.REFERENCE_ORIGINAL, "file:/data/a.txt");
		doc.addField(Parameters.REFERENCE_ORIGINAL, "file:/data/b.txt");

		CompleteProduct cp = new DefaultProductSerializer().deserialize(doc);
		Product product = cp.getProduct();
		assertEquals("abc-123", product.getProductId());
		assertEquals("filelist_chunk_0.txt", product.getProductName());
		assertEquals("ChunkList", product.getProductType().getName());
		assertEquals(2, product.getProductReferences().size());
		assertEquals("file:/data/a.txt",
				product.getProductReferences().get(0).getOrigReference());
	}

	public void testDeserializeSolrJQueryResponseNumFound() throws Exception {
		SolrDocumentList list = new SolrDocumentList();
		list.setNumFound(0);
		list.setStart(0);
		NamedList<Object> body = new NamedList<Object>();
		body.add("response", list);
		QueryResponse rsp = new QueryResponse();
		rsp.setResponse(body);
		org.apache.oodt.cas.filemgr.catalog.solr.QueryResponse mapped =
				new DefaultProductSerializer().deserialize(rsp);
		assertEquals(0, mapped.getNumFound());
		assertTrue(mapped.getCompleteProducts().isEmpty());
	}
}
