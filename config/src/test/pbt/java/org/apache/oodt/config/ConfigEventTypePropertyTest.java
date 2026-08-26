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

package org.apache.oodt.config;

import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Properties of the wire form of {@link ConfigEventType}.
 *
 * <p>This enum crosses a process boundary. The publisher writes
 * {@code type.toString()} into the notification ZNode and every listening
 * configuration manager reads those bytes back through {@code parse}. The two
 * halves are written independently, so the encoding and the decoding have to be
 * inverses, and the decoder has to survive bytes it does not recognise.
 */
class ConfigEventTypePropertyTest {

  private static final List<ConfigEventType> ALL = Arrays.asList(ConfigEventType.values());

  /**
   * What the publisher writes is what a manager reads. The bytes are produced
   * by {@code toString()} and consumed by {@code parse}, exactly as
   * {@code DistributedConfigurationPublisher.notifyConfigEvent} does it.
   */
  @HegelTest
  void whatThePublisherWritesIsWhatAManagerReads(TestCase tc) {
    ConfigEventType type = tc.draw(sampledFrom(ALL), "type");

    byte[] onTheWire = type.toString().getBytes(StandardCharsets.UTF_8);
    ConfigEventType read = ConfigEventType.parse(new String(onTheWire, StandardCharsets.UTF_8));

    assertNotNull(read, "a published event could not be read back");
    assertEquals(type, read);
  }

  /**
   * An unrecognised notification is reported as unrecognised rather than
   * mistaken for an event. A manager watching the notification node sees
   * whatever anyone wrote there, including the placeholder byte the node is
   * created with.
   */
  @HegelTest
  void anUnrecognisedNotificationIsNotMistakenForAnEvent(TestCase tc) {
    String written = tc.draw(text().maxSize(20), "written");

    ConfigEventType parsed = ConfigEventType.parse(written);

    boolean someoneOwnsIt = ALL.stream().anyMatch(t -> t.toString().equals(written));
    if (someoneOwnsIt) {
      assertNotNull(parsed);
      assertEquals(written, parsed.toString());
    } else {
      assertNull(parsed, "'" + written + "' was decoded as " + parsed);
    }
  }

  /** Two events never share a wire form, or a manager could not tell them apart. */
  @HegelTest
  void eventsNeverShareAWireForm(TestCase tc) {
    ConfigEventType a = tc.draw(sampledFrom(ALL), "a");
    ConfigEventType b = tc.draw(sampledFrom(ALL), "b");

    assertEquals(a == b, a.toString().equals(b.toString()), "two events share a wire form");
  }
}
