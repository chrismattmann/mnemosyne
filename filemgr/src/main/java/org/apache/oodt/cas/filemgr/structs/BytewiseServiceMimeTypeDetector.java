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
package org.apache.oodt.cas.filemgr.structs;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

/** Calls a warm localhost Bytewise process and falls back to Tika safely. */
public final class BytewiseServiceMimeTypeDetector implements ReferenceMimeTypeDetector {
    public static final String URL_PROPERTY = "org.apache.oodt.bytewise.url";
    public static final String TIMEOUT_PROPERTY = "org.apache.oodt.bytewise.timeoutMillis";
    private static final Logger LOG = Logger.getLogger(BytewiseServiceMimeTypeDetector.class.getName());
    private static final Pattern MIME = Pattern.compile("\\\"mime_type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private final ReferenceMimeTypeDetector fallback = new TikaRepositoryMimeTypeDetector();

    @Override
    public MimeType detect(String originalReference, MimeTypes repository) throws Exception {
        try {
            URI source = new URI(originalReference);
            String path = source.getScheme() == null ? originalReference : new File(source).getAbsolutePath();
            String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String base = System.getProperty(URL_PROPERTY, "http://127.0.0.1:8765").replaceAll("/$", "");
            int timeout = Integer.parseInt(System.getProperty(TIMEOUT_PROPERTY, "30000"));
            HttpURLConnection connection = (HttpURLConnection) new URI(base + "/detect/" + encoded).toURL().openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Bytewise returned HTTP " + connection.getResponseCode());
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) response.append(line);
            } finally {
                connection.disconnect();
            }
            Matcher matcher = MIME.matcher(response);
            if (!matcher.find()) throw new IllegalStateException("Bytewise response has no mime_type");
            return repository.forName(matcher.group(1));
        } catch (Exception failure) {
            FALLBACKS.incrementAndGet();
            LOG.log(Level.WARNING, "Bytewise MIME detection failed for [" + originalReference
                    + "]; using Tika repository fallback: " + failure.getMessage());
            return fallback.detect(originalReference, repository);
        }
    }

    public static long getFallbackCount() {
        return FALLBACKS.get();
    }

    public static void resetFallbackCount() {
        FALLBACKS.set(0L);
    }
}
