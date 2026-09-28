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

/** Selects the reference detector without changing the default behavior. */
public final class ReferenceMimeTypeDetectors {
    public static final String DETECTOR_PROPERTY = "org.apache.oodt.mime.detector";

    private ReferenceMimeTypeDetectors() { }

    public static ReferenceMimeTypeDetector configured() {
        String name = System.getProperty(DETECTOR_PROPERTY, "tika").trim();
        if ("bytewise".equalsIgnoreCase(name)) return new BytewiseServiceMimeTypeDetector();
        if ("tika".equalsIgnoreCase(name)) return new TikaRepositoryMimeTypeDetector();
        throw new IllegalArgumentException("Unknown MIME detector: " + name);
    }
}
