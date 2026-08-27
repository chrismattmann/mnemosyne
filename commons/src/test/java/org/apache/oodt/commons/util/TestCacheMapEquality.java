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
package org.apache.oodt.commons.util;

import junit.framework.TestCase;

/**
 * CacheMap implements Map, whose equals is specified over the mappings. It
 * compared only the LRU key list, so two caches holding different values for
 * the same key compared equal.
 */
public class TestCacheMapEquality extends TestCase {

    public void testCachesThatDisagreeAboutAValueAreNotEqual() {
        CacheMap a = new CacheMap(5);
        CacheMap b = new CacheMap(5);
        a.put("key-0", 0);
        b.put("key-0", 1);

        assertFalse("same keys, different values, compared equal", a.equals(b));
    }

    public void testCachesWithTheSameMappingsAreEqual() {
        CacheMap a = new CacheMap(5);
        CacheMap b = new CacheMap(5);
        a.put("key-0", 0);
        b.put("key-0", 0);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    public void testAnEmptyCacheEqualsAnotherEmptyCache() {
        assertEquals(new CacheMap(5), new CacheMap(5));
    }

    public void testDifferentKeysAreNotEqual() {
        CacheMap a = new CacheMap(5);
        CacheMap b = new CacheMap(5);
        a.put("key-0", 0);
        b.put("key-1", 0);
        assertFalse(a.equals(b));
    }
}
