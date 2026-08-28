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

//JDK imports
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

//JAX-RS imports
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

//JSON imports
import net.sf.json.JSONObject;

//OODT imports
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.pcs.metadata.PCSConfigMetadata;
import org.apache.oodt.pcs.pedigree.Pedigree;
import org.apache.oodt.pcs.pedigree.PedigreeTree;
import org.apache.oodt.pcs.pedigree.PedigreeTreeNode;
import org.apache.oodt.pcs.util.FileManagerUtils;

/**
 * 
 * Exposes the {@link Pedigree} API of the PCS to provide an upstream and
 * downstream lineage of a particular OODT {@link Product} using JAX-RS.
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
@Path("pedigree")
public class PedigreeResource extends PCSService {

  private static final long serialVersionUID = 4851623546718112205L;

  private Pedigree trace;

  private FileManagerUtils fm;

  public PedigreeResource() throws MalformedURLException {
    this.fm = new FileManagerUtils(PCSService.conf.getFmUrl());
    this.trace = new Pedigree(this.fm, PCSService.conf
        .isTraceNotCatalogedFiles(), Arrays.asList(PCSService.conf
        .getTraceProductTypeExcludeList().split(",")));
  }

  @GET
  @Path("report/{filename}")
  @Produces("text/plain")
  public String generatePedigree(@PathParam("filename") String filename) {
    Product product = productOrMissing(filename);
    PedigreeTree upstreamTree = this.trace.doPedigree(product, true);
    PedigreeTree downstreamTree = this.trace.doPedigree(product, false);
    return this.encodePedigreeAsJson(upstreamTree, downstreamTree);
  }

  @GET
  @Path("report/{filename}/upstream")
  @Produces("text/plain")
  public String generateUpstreamPedigree(@PathParam("filename") String filename) {
    PedigreeTree upstreamTree = this.trace.doPedigree(productOrMissing(filename), true);
    return this.encodePedigreeAsJson(upstreamTree, null);
  }

  @GET
  @Path("report/{filename}/downstream")
  @Produces("text/plain")
  public String generateDownstreamPedigree(
      @PathParam("filename") String filename) {
    PedigreeTree downstreamTree = this.trace.doPedigree(
        productOrMissing(filename), false);
    return this.encodePedigreeAsJson(null, downstreamTree);
  }

  private Product productOrMissing(String filename) {
    Product product = this.fm.safeGetProductByName(filename);
    if (!isCataloged(product)) {
      throw new ResourceNotFoundException("No product named [" + filename + "]");
    }
    return product;
  }

  private String encodePedigreeAsJson(PedigreeTree up, PedigreeTree down) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    if (up != null) {
      output.put("upstream", this.encodePedigreeTreeAsJson(up.getRoot()));
    }
    if (down != null) {
      output.put("downstream", this.encodePedigreeTreeAsJson(down.getRoot()));
    }
    JSONObject json = new JSONObject();
    json.put("pedigree", output);
    return json.toString();
  }

  private Object encodePedigreeTreeAsJson(PedigreeTreeNode node) {
    if (node == null || !isCataloged(node.getNodeProduct())) {
      return "";
    }
    List<Object> list = new Vector<Object>();
    for (int i = 0; i < node.getNumChildren(); i++) {
      Object child = this.encodePedigreeTreeAsJson(node.getChildAt(i));
      if (child != null && !"".equals(child)) {
        list.add(child);
      }
    }
    String name = node.getNodeProduct().getProductName();
    if (list.isEmpty()) {
      return name;
    }
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    map.put(name, list);
    return map;
  }

  /**
   * Pedigree can invent UNKNOWN placeholders for InputFiles that were
   * never ingested. Those must not show up as children of a TSV.
   */
  static boolean isCataloged(Product product) {
    if (product == null || product.getProductName() == null
        || product.getProductName().length() == 0) {
      return false;
    }
    if (product.getProductType() != null) {
      String typeName = product.getProductType().getName();
      String typeId = product.getProductType().getProductTypeId();
      if (PCSConfigMetadata.UNKNOWN.equals(typeName)
          || PCSConfigMetadata.UNKNOWN.equals(typeId)) {
        return false;
      }
    }
    return true;
  }
}
