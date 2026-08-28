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

package org.apache.oodt.cas.metadata.util;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Two small utility defects from #134.
 */
public class TestPropertyAndMimeTypeEdgeCases {

  private static final String UNSET = "org.apache.oodt.test.definitely.unset";

  @After
  public void clearProperty() {
    System.clearProperty(UNSET);
  }

  /** getProperties returns a zero-length array, which getProperty indexed. */
  @Test
  public void anUnsetPropertyIsNullRatherThanAnIndexError() {
    assertNull(PropertiesUtils.getProperty(UNSET));
  }

  @Test
  public void aSetPropertyIsStillReturned() {
    System.setProperty(UNSET, "a value");
    assertEquals("a value", PropertiesUtils.getProperty(UNSET));
  }

  @Test
  public void aSetMultiValuedPropertyStillReturnsTheFirst() {
    System.setProperty(UNSET, "first,second");
    assertEquals("first", PropertiesUtils.getProperty(UNSET));
  }

  /**
   * String.split drops trailing empty tokens, so a mime type ending in the
   * separator came back uncleaned -- defeating the comparison the method exists
   * to make possible.
   */
  @Test
  public void aTrailingSeparatorIsRemoved() {
    assertEquals("text/aa", MimeTypeUtils.cleanMimeType("text/aa;"));
  }

  @Test
  public void parametersAfterTheSeparatorAreStillRemoved() {
    assertEquals("text/aa", MimeTypeUtils.cleanMimeType("text/aa; charset=utf-8"));
  }

  @Test
  public void aTypeWithNoSeparatorIsUnchanged() {
    assertEquals("text/aa", MimeTypeUtils.cleanMimeType("text/aa"));
  }

  @Test
  public void aNullTypeIsStillNull() {
    assertNull(MimeTypeUtils.cleanMimeType(null));
  }
}
