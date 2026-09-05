/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.system.AvroFileManagerClient;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.system.AvroRpcResourceManagerClient;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.system.AvroRpcWorkflowManagerClient;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

/** Checks the generated Compose stack using its public RPC and HTTP APIs. */
public final class SmokeTest {
    private static final String WEB_URL = "http://opsui:8080";
    private static final String PCS_URL = WEB_URL + "/pcs/services";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.setProperty("org.apache.oodt.avro.client.requestTimeoutMillis", "5000");
        System.setProperty("org.apache.oodt.rpc.connect.attempts", "1");
        if (args.length == 3 && "health".equals(args[0])) {
            health(args[1], new URL(args[2]));
        } else if (args.length == 1 && "smoke".equals(args[0])) {
            smoke();
        } else if (args.length == 1 && "crawler".equals(args[0])) {
            crawler();
        } else {
            throw new IllegalArgumentException("Usage: SmokeTest health <filemgr|workflow|resmgr> <url> | smoke | crawler");
        }
    }

    private static void health(String component, URL url) throws Exception {
        boolean alive;
        switch (component) {
            case "filemgr":
                try (AvroFileManagerClient client = new AvroFileManagerClient(url, false, false)) {
                    alive = client.isAlive();
                }
                break;
            case "workflow":
                try (AvroRpcWorkflowManagerClient client = new AvroRpcWorkflowManagerClient(url)) {
                    alive = client.isAlive();
                }
                break;
            case "resmgr":
                try (AvroRpcResourceManagerClient client = new AvroRpcResourceManagerClient(url)) {
                    alive = client.isAlive();
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown component: " + component);
        }
        require(alive, component + " did not answer its Avro health call at " + url);
        System.out.println("PASS: " + component + " Avro health");
    }

    private static void smoke() throws Exception {
        waitForServices();

        String name = "radix-smoke-" + UUID.randomUUID();
        byte[] payload = (name + "\n").getBytes(StandardCharsets.UTF_8);
        Path working = Files.createDirectory(Path.of("/oodt/data", name));
        Path input = working.resolve("input.txt");
        Path output = working.resolve("output.txt");
        Path archived = Path.of("/oodt/data/archive", name, "input.txt");
        Files.write(input, payload);

        try (AvroFileManagerClient filemgr = new AvroFileManagerClient(
                    new URL("http://filemgr:9000"), false, false);
             AvroRpcWorkflowManagerClient workflow = new AvroRpcWorkflowManagerClient(
                    new URL("http://workflow:9002"))) {
            Product product = null;
            String workflowId = null;
            boolean workflowFinished = false;
            try {
                ProductType type = filemgr.getProductTypeByName("GenericFile");
                require(type != null, "GenericFile product type is missing");
                require(Path.of(URI.create(type.getProductRepositoryPath())).equals(archived.getParent().getParent()),
                        "GenericFile must use /oodt/data/archive for the Compose smoke test");
                product = new Product(name, type, Product.STRUCTURE_FLAT, Product.STATUS_TRANSFER,
                        List.of(new Reference(input.toUri().toString(), "", payload.length)));
                Metadata metadata = new Metadata();
                metadata.addMetadata("Filename", "input.txt");
                metadata.addMetadata("DataVersion", name);
                String productId = filemgr.ingestProduct(product, metadata, false);
                require(productId != null && !productId.isEmpty(), "Ingest returned no product ID");
                product.setProductId(productId);

                Product stored = filemgr.getProductById(productId);
                require(stored != null && name.equals(stored.getProductName()), "Ingested product was not stored");
                require(Product.STATUS_RECEIVED.equals(stored.getTransferStatus()), "Product transfer is incomplete");
                require(name.equals(filemgr.getMetadata(stored).getMetadata("DataVersion")),
                        "Stored metadata differs from the input");
                List<Reference> refs = filemgr.getProductReferences(stored);
                require(refs.size() == 1 && archived.equals(Path.of(URI.create(refs.get(0).getDataStoreReference()))),
                        "Stored file reference is incorrect");
                require(Arrays.equals(payload, Files.readAllBytes(archived)), "Archived bytes differ from the input");
                Query query = new Query();
                query.addCriterion(new TermQueryCriteria("DataVersion", name));
                List<Product> matches = filemgr.query(query, type);
                require(matches.size() == 1 && productId.equals(matches.get(0).getProductId()),
                        "Catalog query did not return the ingested product");
                JsonNode pcsProduct = json(PCS_URL + "/catalog/products/" + productId).path("product");
                require(productId.equals(pcsProduct.path("id").asText()), "PCS cannot read the ingested product");
                System.out.println("PASS: ingest, transfer, metadata, catalog query, and PCS product " + productId);

                // The example task runs /usr/bin/env /bin/cp through ExternScriptTaskInstance.
                // Check the copied file as well as FINISHED: task exceptions may be swallowed by an engine.
                Metadata context = new Metadata();
                context.addMetadata("Args", List.of(archived.toString(), output.toString()));
                workflowId = workflow.executeDynamicWorkflow(List.of("urn:radix:smoke"), context);
                require(workflowId != null && !workflowId.isEmpty(), "Workflow returned no instance ID");
                long deadline = System.nanoTime() + 90_000_000_000L;
                String lastStatus = "unknown";
                while (System.nanoTime() < deadline) {
                    WorkflowInstance instance = workflow.getWorkflowInstanceById(workflowId);
                    if (instance != null) {
                        lastStatus = instance.getStatus();
                        require(!WorkflowStatus.ERROR.equals(lastStatus), "Workflow failed: " + workflowId);
                        if (WorkflowStatus.FINISHED.equals(lastStatus)) {
                            workflowFinished = true;
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
                require(workflowFinished, "Workflow timed out: " + workflowId + " (" + lastStatus + ")");
                require(Files.exists(output) && Arrays.equals(payload, Files.readAllBytes(output)),
                        "Workflow finished but did not produce the expected output file");
                JsonNode pcsInstance = json(PCS_URL + "/workflow/instances/" + workflowId).path("instance");
                require(workflowId.equals(pcsInstance.path("id").asText())
                                && WorkflowStatus.FINISHED.equals(pcsInstance.path("status").asText()),
                        "PCS cannot read the finished workflow");
                System.out.println("PASS: workflow execution, output bytes, and PCS workflow " + workflowId);
                // The API has no single-instance delete; keep this workflow record for inspection in OPSUI.
            } finally {
                try {
                    if (workflowId != null && !workflowFinished) {
                        workflow.stopWorkflowInstance(workflowId);
                    }
                } finally {
                    if (product != null && product.getProductId() != null) {
                        require(filemgr.removeProduct(product), "Could not remove the smoke-test catalog record");
                    }
                }
            }
        } finally {
            // Only remove paths created by this UUID. Never clear the catalog or the shared volume.
            Files.deleteIfExists(archived);
            Files.deleteIfExists(archived.getParent());
            Files.deleteIfExists(output);
            Files.deleteIfExists(input);
            Files.deleteIfExists(working);
        }
        System.out.println("PASS: RADiX Compose smoke test");
    }

    private static void crawler() throws Exception {
        String name = "radix-crawler-" + UUID.randomUUID() + ".txt";
        byte[] payload = ("RADiX crawler test " + name + "\n").getBytes(StandardCharsets.UTF_8);
        Path staging = Path.of("/oodt/data", name);
        Path incoming = Path.of("/oodt/data/incoming", name);
        Path backup = Path.of("/oodt/data/backup", name);
        Path failure = Path.of("/oodt/data/failure", name);
        Path archived = Path.of("/oodt/data/archive", name, name);
        Files.write(staging, payload);
        // Publish a complete file so that the running crawler cannot read a partial write.
        Files.move(staging, incoming, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        try (AvroFileManagerClient filemgr = new AvroFileManagerClient(
                new URL("http://filemgr:9000"), false, false)) {
            Product product = null;
            try {
                long deadline = System.nanoTime() + 90_000_000_000L;
                while (System.nanoTime() < deadline) {
                    require(!Files.exists(failure), "Crawler moved its test file to the failure directory");
                    if (filemgr.hasProduct(name) && Files.exists(backup)) {
                        product = filemgr.getProductByName(name);
                        break;
                    }
                    Thread.sleep(1000);
                }
                require(product != null, "Crawler did not ingest and back up " + name + " within 90 seconds");
                Metadata metadata = filemgr.getMetadata(product);
                require(name.equals(metadata.getMetadata("Filename")), "Crawler did not save the filename metadata");
                List<Reference> refs = filemgr.getProductReferences(product);
                require(refs.size() == 1 && archived.equals(Path.of(URI.create(refs.get(0).getDataStoreReference()))),
                        "Crawler stored an incorrect file reference");
                require(Arrays.equals(payload, filemgr.retrieveFile(archived.toString(), 0, payload.length)),
                        "Crawler archive bytes differ from the incoming file");
                require(!Files.exists(incoming) && Arrays.equals(payload, Files.readAllBytes(backup)),
                        "Crawler did not move the original file to backup after ingest");
                Query query = new Query();
                query.addCriterion(new TermQueryCriteria("Filename", name));
                List<Product> matches = filemgr.query(query, filemgr.getProductTypeByName("GenericFile"));
                require(matches.size() == 1 && product.getProductId().equals(matches.get(0).getProductId()),
                        "Crawler product is absent from the catalog query");
                System.out.println("PASS: Crawler detected incoming text, extracted metadata, ingested bytes, "
                        + "and moved the original to backup: " + product.getProductId());
            } finally {
                if (product == null && filemgr.hasProduct(name)) {
                    product = filemgr.getProductByName(name);
                }
                if (product != null) {
                    require(filemgr.removeProduct(product), "Could not remove the crawler test catalog record");
                }
            }
        } finally {
            Files.deleteIfExists(incoming);
            Files.deleteIfExists(staging);
            Files.deleteIfExists(backup);
            Files.deleteIfExists(failure);
            Files.deleteIfExists(archived);
            Files.deleteIfExists(archived.getParent());
        }
    }

    private static void waitForServices() throws Exception {
        // A listening Tomcat port does not imply that both WAR files are ready.
        long deadline = System.nanoTime() + 120_000_000_000L;
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                health("filemgr", new URL("http://filemgr:9000"));
                health("workflow", new URL("http://workflow:9002"));
                health("resmgr", new URL("http://resmgr:9001"));
                checkWebServices();
                return;
            } catch (Exception failure) {
                lastFailure = failure;
                System.out.println("Waiting for services: " + failure.getMessage());
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("RADiX services did not become ready within 120 seconds", lastFailure);
    }

    private static void checkWebServices() throws Exception {
        String html = http(WEB_URL + "/opsui/");
        require(html.contains("id=\"app\"") && html.contains("<script"), "OPSUI did not return its application page");
        JsonNode report = json(PCS_URL + "/health/report").path("report");
        for (String service : List.of("fm", "wm", "rm")) {
            require("UP".equals(report.path("daemonStatus").path(service).path("status").asText()),
                    "PCS health reports " + service + " down: " + report);
        }
        JsonNode types = json(PCS_URL + "/catalog/types").path("types");
        boolean genericFile = false;
        for (JsonNode type : types) {
            genericFile |= "GenericFile".equals(type.path("name").asText());
        }
        require(genericFile, "PCS does not list the GenericFile product type");
        require(json(PCS_URL + "/workflow/definitions").path("workflows").isArray(),
                "PCS did not return workflow definitions");
        JsonNode resources = json(PCS_URL + "/resource/overview").path("resource");
        require(resources.path("alive").asBoolean() && !resources.has("error"),
                "PCS cannot reach the Resource Manager: " + resources);
        System.out.println("PASS: OPSUI page and PCS health, catalog, workflow, and resource APIs");
    }

    private static JsonNode json(String url) throws Exception {
        return JSON.readTree(http(url));
    }

    private static String http(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        try {
            require(connection.getResponseCode() == 200, "HTTP " + connection.getResponseCode() + " from " + url);
            try (var input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
