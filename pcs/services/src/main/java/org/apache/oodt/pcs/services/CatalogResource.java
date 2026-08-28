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
package org.apache.oodt.pcs.services;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import net.sf.json.JSONObject;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.exceptions.QueryFormulationException;
import org.apache.oodt.cas.filemgr.structs.query.ComplexQuery;
import org.apache.oodt.cas.filemgr.structs.query.QueryResult;
import org.apache.oodt.cas.filemgr.util.SqlParser;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.pcs.util.FileManagerUtils;

/**
 * JSON catalog browse for the Vue OPSUI. The Wicket pages talked to File
 * Manager over Avro from the browser's server; this is that same data,
 * same-origin, for the SPA.
 */
@Path("catalog")
public class CatalogResource extends PCSService {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG = Logger.getLogger(CatalogResource.class.getName());
  private static final int MAX_QUERY_RESULTS = 200;

  private FileManagerUtils fm() throws MalformedURLException {
    return new FileManagerUtils(PCSService.conf.getFmUrl());
  }

  @GET
  @Path("types")
  @Produces("application/json")
  public String types() throws MalformedURLException, IOException {
    FileManagerUtils fm = fm();
    try {
      List<ProductType> types = fm.safeGetProductTypes();
      if (types == null) {
        types = Collections.emptyList();
      }
      Collections.sort(types, new Comparator<ProductType>() {
        public int compare(ProductType a, ProductType b) {
          String an = a.getName() == null ? "" : a.getName();
          String bn = b.getName() == null ? "" : b.getName();
          return an.compareToIgnoreCase(bn);
        }
      });
      List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < types.size(); i++) {
        ProductType type = types.get(i);
        Map<String, Object> row = encodeType(type);
        row.put("numProducts", Integer.valueOf(fm.safeGetNumProducts(type)));
        out.add(row);
      }
      return json("types", out);
    } finally {
      fm.close();
    }
  }

  @GET
  @Path("types/{name}/products")
  @Produces("application/json")
  public String productsForType(@PathParam("name") String name,
      @QueryParam("page") @DefaultValue("1") int pageNum)
      throws MalformedURLException, IOException {
    FileManagerUtils fm = fm();
    try {
      ProductType type = fm.safeGetProductTypeByName(name);
      if (type == null || type.getName() == null) {
        throw new ResourceNotFoundException("No product type named [" + name + "]");
      }
      if (pageNum < 1) {
        pageNum = 1;
      }
      ProductPage page = pageAt(fm, type, pageNum);
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      body.put("type", encodeType(type));
      body.put("page", Integer.valueOf(page == null ? pageNum : page.getPageNum()));
      body.put("totalPages", Integer.valueOf(page == null ? 0 : page.getTotalPages()));
      body.put("pageSize", Integer.valueOf(page == null ? 0 : page.getPageSize()));
      body.put("numProducts", Integer.valueOf(fm.safeGetNumProducts(type)));
      List<Map<String, Object>> products = new ArrayList<Map<String, Object>>();
      if (page != null && page.getPageProducts() != null) {
        List<Product> list = page.getPageProducts();
        for (int i = 0; i < list.size(); i++) {
          products.add(encodeProduct(list.get(i), fm));
        }
      }
      body.put("products", products);
      JSONObject response = new JSONObject();
      response.put("catalog", body);
      return response.toString();
    } finally {
      fm.close();
    }
  }

  @GET
  @Path("products/{id}")
  @Produces("application/json")
  public String product(@PathParam("id") String id) throws MalformedURLException, IOException {
    FileManagerUtils fm = fm();
    try {
      Product product = null;
      try {
        if (fm.getFmgrClient() != null) {
          product = fm.getFmgrClient().getProductById(id);
        }
      } catch (Exception e) {
        LOG.fine("No product id [" + id + "]: " + e.getLocalizedMessage());
      }
      if (product == null || product.getProductId() == null
          || product.getProductId().length() == 0) {
        product = fm.safeGetProductByName(id);
      }
      if (product == null || product.getProductId() == null
          || product.getProductId().length() == 0) {
        throw new ResourceNotFoundException("No product with id [" + id + "]");
      }
      Map<String, Object> body = encodeProduct(product, fm);
      body.put("metadata", encodeMetadata(fm.safeGetMetadata(product)));
      body.put("references", encodeReferences(fm.safeGetProductReferences(product)));
      JSONObject response = new JSONObject();
      response.put("product", body);
      return response.toString();
    } finally {
      fm.close();
    }
  }

  @GET
  @Path("query")
  @Produces("application/json")
  public String query(@QueryParam("sql") String sql) throws MalformedURLException, IOException {
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    String trimmed = sql == null ? "" : sql.trim();
    body.put("sql", trimmed);
    if (trimmed.length() == 0) {
      body.put("error", "Enter a SQL query, for example: SELECT Filename FROM EmploymentJob");
      body.put("results", Collections.emptyList());
      return queryJson(body);
    }
    FileManagerUtils fm = fm();
    try {
      if (fm.getFmgrClient() == null) {
        body.put("error", "File Manager is not reachable");
        body.put("results", Collections.emptyList());
        return queryJson(body);
      }
      ComplexQuery cq = SqlParser.parseSqlQuery(trimmed);
      List<QueryResult> found = fm.getFmgrClient().complexQuery(cq);
      List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
      int limit = found == null ? 0 : Math.min(found.size(), MAX_QUERY_RESULTS);
      for (int i = 0; i < limit; i++) {
        QueryResult qr = found.get(i);
        if (qr == null || qr.getProduct() == null) {
          continue;
        }
        Map<String, Object> row = encodeProduct(qr.getProduct(), fm);
        if (qr.getMetadata() != null) {
          row.put("metadata", encodeMetadata(qr.getMetadata()));
        }
        results.add(row);
      }
      body.put("results", results);
      body.put("truncated", Boolean.valueOf(found != null && found.size() > MAX_QUERY_RESULTS));
      return queryJson(body);
    } catch (QueryFormulationException e) {
      body.put("error", e.getMessage() == null ? "Could not parse SQL query" : e.getMessage());
      body.put("results", Collections.emptyList());
      return queryJson(body);
    } catch (Exception e) {
      LOG.warning("Catalog query failed: " + e.getLocalizedMessage());
      body.put("error", e.getMessage() == null ? "Query failed" : e.getMessage());
      body.put("results", Collections.emptyList());
      return queryJson(body);
    } finally {
      fm.close();
    }
  }

  private static String queryJson(Map<String, Object> body) {
    JSONObject response = new JSONObject();
    response.put("query", body);
    return response.toString();
  }

  static Map<String, Object> encodeType(ProductType type) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (type == null) {
      return row;
    }
    row.put("id", nullToEmpty(type.getProductTypeId()));
    row.put("name", nullToEmpty(type.getName()));
    row.put("description", nullToEmpty(type.getDescription()));
    return row;
  }

  static Map<String, Object> encodeProduct(Product product, FileManagerUtils fm) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    if (product == null) {
      return row;
    }
    row.put("id", nullToEmpty(product.getProductId()));
    row.put("name", nullToEmpty(product.getProductName()));
    row.put("structure", nullToEmpty(product.getProductStructure()));
    row.put("transferStatus", nullToEmpty(product.getTransferStatus()));
    if (product.getProductType() != null) {
      row.put("type", encodeType(product.getProductType()));
    }
    if (fm != null) {
      try {
        Metadata met = fm.safeGetMetadata(product);
        if (met != null) {
          row.put("receivedTime", met.getMetadata("CAS.ProductReceivedTime"));
        }
      } catch (Exception e) {
        LOG.fine("No received time for " + product.getProductId());
      }
    }
    return row;
  }

  static Map<String, Object> encodeMetadata(Metadata met) {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    if (met == null) {
      return map;
    }
    List<String> keys = met.getAllKeys();
    if (keys == null) {
      return map;
    }
    for (int i = 0; i < keys.size(); i++) {
      String key = keys.get(i);
      List<String> values = met.getAllMetadata(key);
      map.put(key, values == null ? Collections.emptyList() : values);
    }
    return map;
  }

  static List<Map<String, Object>> encodeReferences(List<Reference> refs) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (refs == null) {
      return out;
    }
    for (int i = 0; i < refs.size(); i++) {
      Reference ref = refs.get(i);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("orig", nullToEmpty(ref.getOrigReference()));
      row.put("dataStore", nullToEmpty(ref.getDataStoreReference()));
      row.put("fileSize", Long.valueOf(ref.getFileSize()));
      if (ref.getMimeType() != null) {
        row.put("mimeType", ref.getMimeType().getName());
      }
      out.add(row);
    }
    return out;
  }

  private ProductPage pageAt(FileManagerUtils fm, ProductType type, int pageNum) {
    ProductPage page = fm.safeFirstPage(type);
    if (page == null || fm.getFmgrClient() == null) {
      return page;
    }
    int current = page.getPageNum() <= 0 ? 1 : page.getPageNum();
    try {
      while (page != null && current < pageNum && !page.isLastPage()) {
        ProductPage next = fm.getFmgrClient().getNextPage(type, page);
        if (next == null || next.getPageNum() == page.getPageNum()) {
          break;
        }
        page = next;
        current = page.getPageNum() <= 0 ? current + 1 : page.getPageNum();
      }
    } catch (Exception e) {
      LOG.warning("Unable to page catalog for type [" + type.getName() + "]: "
          + e.getLocalizedMessage());
    }
    return page;
  }

  private static String json(String key, Object value) {
    JSONObject response = new JSONObject();
    response.put(key, value);
    return response.toString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
