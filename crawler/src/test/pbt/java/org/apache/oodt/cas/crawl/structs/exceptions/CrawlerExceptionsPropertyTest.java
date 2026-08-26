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

package org.apache.oodt.cas.crawl.structs.exceptions;

import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;

/**
 * Properties of the crawler's two exception types.
 *
 * <p>The crawler catches broadly and reports through log messages built from
 * {@code getMessage()}, and it wraps lower-level failures rather than letting
 * them escape. So an operator reading the log depends on two things: the
 * message survives the constructor, and a wrapped cause is still reachable.
 */
class CrawlerExceptionsPropertyTest {

  private static Generator<String> message() {
    return text().minSize(1).maxSize(60);
  }

  /** A crawl failure keeps the message it was reported with. */
  @HegelTest
  void aCrawlFailureKeepsItsMessage(TestCase tc) {
    String message = tc.draw(message(), "message");

    assertEquals(message, new CrawlException(message).getMessage());
    assertNull(new CrawlException(message).getCause(), "a message-only failure invented a cause");
  }

  /** A wrapped crawl failure keeps both the message and the original failure. */
  @HegelTest
  void aWrappedCrawlFailureKeepsBothTheMessageAndTheCause(TestCase tc) {
    String message = tc.draw(message(), "message");
    String causeMessage = tc.draw(message(), "causeMessage");

    Throwable cause = new IllegalStateException(causeMessage);
    CrawlException wrapped = new CrawlException(message, cause);

    assertEquals(message, wrapped.getMessage());
    assertSame(cause, wrapped.getCause());
  }

  /**
   * Wrapping a failure with no message of its own still leads back to the
   * original. {@code CrawlerAction.validate} wraps whatever Commons Lang threw,
   * and the operator needs to be able to reach it.
   */
  @HegelTest
  void wrappingAFailureLeadsBackToTheOriginal(TestCase tc) {
    String causeMessage = tc.draw(message(), "causeMessage");

    Throwable cause = new IllegalArgumentException(causeMessage);
    CrawlerActionException wrapped = new CrawlerActionException(cause);

    assertSame(cause, wrapped.getCause());
    assertTrue(
        String.valueOf(wrapped.getMessage()).contains(causeMessage),
        "the wrapper hides the reason: " + wrapped.getMessage());
  }

  /** An action failure keeps the message it was reported with. */
  @HegelTest
  void anActionFailureKeepsItsMessage(TestCase tc) {
    String message = tc.draw(message(), "message");
    String causeMessage = tc.draw(message(), "causeMessage");

    Throwable cause = new IllegalStateException(causeMessage);

    assertEquals(message, new CrawlerActionException(message).getMessage());
    assertEquals(message, new CrawlerActionException(message, cause).getMessage());
    assertSame(cause, new CrawlerActionException(message, cause).getCause());
    assertNull(new CrawlerActionException().getMessage(), "an unexplained failure invented a message");
  }
}
