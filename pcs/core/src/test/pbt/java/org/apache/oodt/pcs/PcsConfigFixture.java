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

package org.apache.oodt.pcs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.oodt.pcs.input.PGEConfigFileWriter;
import org.apache.oodt.pcs.input.PGEConfigurationFile;
import org.w3c.dom.Document;

/**
 * Shared fixture for the PCS property tests that need a configuration file on
 * disk.
 *
 * <p>Several PCS classes are reachable only through a file path. Rather than
 * check in a static sample, each property builds the {@link
 * PGEConfigurationFile} it wants, serialises it with the production writer into
 * a directory of its own, and deletes the directory afterwards. Using the
 * production writer keeps the fixture honest: the bytes under test are the
 * bytes PCS itself would produce.
 */
public final class PcsConfigFixture {

  private PcsConfigFixture() {}

  /** A directory nothing else is using, for one property invocation. */
  public static File freshDir() throws IOException {
    return Files.createTempDirectory("pcs-pbt").toFile();
  }

  /** Serialises the configuration into {@code dir} under {@code name}. */
  public static File write(PGEConfigurationFile conf, File dir, String name) throws Exception {
    File out = new File(dir, name);
    Document document = new PGEConfigFileWriter(conf).getConfigFileXml();
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    OutputStream stream = new FileOutputStream(out);
    try {
      transformer.transform(new DOMSource(document), new StreamResult(stream));
    } finally {
      stream.close();
    }
    return out;
  }

  /** Removes a directory and everything in it. */
  public static void delete(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    if (!dir.delete()) {
      dir.deleteOnExit();
    }
  }
}
