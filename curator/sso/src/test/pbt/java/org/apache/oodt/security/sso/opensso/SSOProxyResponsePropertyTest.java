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

package org.apache.oodt.security.sso.opensso;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sets;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Properties of the two response readers inside {@link SSOProxy}, which turn the
 * line-oriented body returned by the OpenSSO identity services into a
 * {@link UserDetails} or an {@link IdentityDetails}.
 *
 * <p>These are the only pure functions in the single sign-on module - everything
 * else opens an HTTP connection. They are reached here by reflection because
 * they are private; no property in this class makes a network call, and no
 * proxy method that would is ever invoked.
 *
 * <p>Two obligations. A response in the shape the service documents has to be
 * read correctly, since what comes out of it is the caller's identity and roles.
 * And a response in any other shape has to be reported, not crashed on: the body
 * is remote input, the callers of these readers declare
 * {@link SingleSignOnException} and {@link java.io.IOException} for exactly this
 * case, and an unchecked exception escaping past them reaches a servlet as a
 * 500 with no explanation.
 */
class SSOProxyResponsePropertyTest {

  private static Object parse(String methodName, String response) throws Exception {
    Method method = SSOProxy.class.getDeclaredMethod(methodName, String.class);
    method.setAccessible(true);
    try {
      return method.invoke(new SSOProxy(), response);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new IllegalStateException(cause);
    }
  }

  /** Words that cannot be mistaken for the '=' the readers split lines on. */
  private static Generator<String> words() {
    return text().minSize(1).maxSize(8).categories("Lu", "Ll", "Nd");
  }

  private static Generator<Set<Integer>> attributeKeys() {
    return sets(integers().min(0).max(3)).maxSize(4);
  }

  /**
   * A user-details response in the documented shape yields the token, every role
   * listed, and every attribute. This is the object a servlet turns into a
   * session, so a dropped role is a user silently losing a permission.
   */
  @HegelTest
  void aWellFormedUserDetailsResponseIsReadInFull(TestCase tc) throws Exception {
    String token = tc.draw(words(), "token");
    List<String> roles =
        tc.draw(lists(integers().min(0).max(3).map(i -> "role" + i)).maxSize(4), "roles");
    Set<Integer> attributes = tc.draw(attributeKeys(), "attributes");

    List<String> lines = new ArrayList<String>();
    lines.add(SSOMetKeys.USER_DETAILS_TOKEN + "=" + token);
    for (String role : roles) {
      lines.add(SSOMetKeys.USER_DETAILS_ROLE + "=" + role);
    }
    List<String> attributeValues = new ArrayList<String>();
    for (Integer key : new TreeSet<Integer>(attributes)) {
      String value = tc.draw(words(), "attributeValue." + key);
      attributeValues.add(value);
      lines.add(SSOMetKeys.USER_DETAILS_ATTR_NAME + "=attr" + key);
      lines.add(SSOMetKeys.USER_DETAILS_ATTR_VALUE + "=" + value);
    }
    String response = String.join("\n", lines);
    tc.note("response = " + response);

    UserDetails details = (UserDetails) parse("parseUserDetails", response);

    assertEquals(token, details.getToken(), "wrong token");
    assertEquals(roles, details.getRoles(), "wrong roles");
    int index = 0;
    for (Integer key : new TreeSet<Integer>(attributes)) {
      assertEquals(
          attributeValues.get(index++),
          details.getAttributes().getMetadata("attr" + key),
          "wrong value for attr" + key);
    }
  }

  /**
   * An identity-details response in the documented shape yields the name, type,
   * realm and every group. The realm is read by offset rather than by splitting
   * because a realm is a path; the other fields are read by splitting.
   */
  @HegelTest
  void aWellFormedIdentityDetailsResponseIsReadInFull(TestCase tc) throws Exception {
    String name = tc.draw(words(), "name");
    String type = tc.draw(words(), "type");
    String realm = tc.draw(words(), "realm");
    List<String> groups =
        tc.draw(lists(integers().min(0).max(3).map(i -> "group" + i)).maxSize(4), "groups");

    List<String> lines = new ArrayList<String>();
    lines.add(SSOMetKeys.IDENTITY_DETAILS_NAME + "=" + name);
    lines.add(SSOMetKeys.IDENTITY_DETAILS_TYPE + "=" + type);
    lines.add(SSOMetKeys.IDENTITY_DETAILS_REALM + "=/" + realm);
    for (String group : groups) {
      lines.add(SSOMetKeys.IDENTITY_DETAILS_GROUP + "=" + group);
    }
    String response = String.join("\n", lines);
    tc.note("response = " + response);

    IdentityDetails details = (IdentityDetails) parse("parseIdentityDetails", response);

    assertEquals(name, details.getName(), "wrong name");
    assertEquals(type, details.getType(), "wrong type");
    assertEquals("/" + realm, details.getRealm(), "wrong realm");
    assertEquals(groups, details.getGroups(), "wrong groups");
  }

  /**
   * A response that is not in the documented shape is reported through the
   * declared failure channel, not by throwing something the caller cannot have
   * anticipated. The body arrives from a remote service and a proxy, an outage
   * page or a truncated read all produce lines these readers were not written
   * for.
   */
  @HegelTest
  void anUnexpectedUserDetailsResponseDoesNotCrashTheCaller(TestCase tc) throws Exception {
    List<String> lines =
        tc.draw(
            lists(text().minSize(0).maxSize(10).categories("Lu", "Ll", "Nd")).maxSize(4), "lines");
    String response = String.join("\n", lines);
    tc.note("response = " + response);

    try {
      assertTrue(parse("parseUserDetails", response) instanceof UserDetails);
    } catch (SingleSignOnException expected) {
      // the declared failure channel
    } catch (RuntimeException e) {
      fail("reading the response threw " + e + " for lines " + lines);
    }
  }

  /** The same obligation for the identity-details reader. */
  @HegelTest
  void anUnexpectedIdentityDetailsResponseDoesNotCrashTheCaller(TestCase tc) throws Exception {
    List<String> lines =
        tc.draw(
            lists(text().minSize(0).maxSize(10).categories("Lu", "Ll", "Nd")).maxSize(4), "lines");
    String response = String.join("\n", lines);
    tc.note("response = " + response);

    try {
      assertTrue(parse("parseIdentityDetails", response) instanceof IdentityDetails);
    } catch (SingleSignOnException expected) {
      // the declared failure channel
    } catch (RuntimeException e) {
      fail("reading the response threw " + e + " for lines " + lines);
    }
  }
}
