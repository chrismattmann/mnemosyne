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
package org.apache.oodt.cas.workflow.util;

//JDK imports
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//OODT imports
import org.apache.oodt.cas.metadata.util.PathUtils;

/**
 * Configured paths, resolved and then checked.
 *
 * <p>A path in a properties file is usually written with the deployment's root
 * in it, as {@code [DRAT_HOME]/workflow/policy-wengine}. {@link PathUtils}
 * substitutes those from the environment, and when a name is not set it
 * deliberately leaves the token exactly as written rather than substituting the
 * four characters "null" — its own comment explains why, and that choice is
 * right: a path containing {@code [DRAT_HOME]} says which name was missing,
 * where {@code null} says only that something was.</p>
 *
 * <p>But the token then has to be noticed, and it was not. On 2026-11-04 a DRAT
 * audit could not start because its workflow manager was configured with</p>
 *
 * <pre>
 *   org.apache.oodt.cas.workflow.wengine.packagedRepo.dir.path
 *       = [DRAT_HOME]/workflow/policy-wengine
 * </pre>
 *
 * <p>which reached the filesystem verbatim. What the operator saw was
 * {@code Null engine}, then {@code Unable to create server} — naming neither
 * the property, nor the name that was unset, nor the fact that a path had not
 * been resolved at all. An hour went into diagnosing it as a service readiness
 * race.</p>
 *
 * <p>So this does two things a caller would otherwise forget: substitutes, and
 * then refuses a value still carrying a token, by name.</p>
 */
public final class ConfiguredPaths {

    /**
     * A name PathUtils left behind.
     *
     * <p>Deliberately the same shape BigTranslate's bin/bt-join-index refuses
     * in its own arguments, and the same shape its test suite blocks from
     * reaching the repository: a bracketed word that looks like a key. A
     * directory genuinely named "[2026] reports" is somebody's choice and is
     * not matched.</p>
     */
    private static final Pattern UNSUBSTITUTED =
        Pattern.compile("\\[([A-Za-z][A-Za-z0-9_.]*)\\]");

    private ConfiguredPaths() {
    }

    /**
     * The value of a property, with its environment names substituted.
     *
     * @param property the property name, used in the message when a token
     *     survives so the reader knows which setting to go and look at
     * @param value the configured value, or null when the property is unset
     * @return the resolved value, or null if {@code value} was null
     * @throws IllegalStateException if a bracketed name survived substitution,
     *     naming both the property and the name that was never set
     */
    public static String resolve(String property, String value) {
        if (value == null) {
            return null;
        }
        String resolved = PathUtils.replaceEnvVariables(value);
        Matcher left = UNSUBSTITUTED.matcher(resolved);
        if (left.find()) {
            throw new IllegalStateException(describe(property, value, resolved,
                left.group(1)));
        }
        return resolved;
    }

    /**
     * The same check without substituting, for a value a caller has already
     * resolved or obtained some other way.
     */
    public static void refuseUnsubstituted(String property, String value) {
        if (value == null) {
            return;
        }
        Matcher left = UNSUBSTITUTED.matcher(value);
        if (left.find()) {
            throw new IllegalStateException(describe(property, value, value,
                left.group(1)));
        }
    }

    /** Whether a value still carries a bracketed name. */
    public static boolean isUnsubstituted(String value) {
        return value != null && UNSUBSTITUTED.matcher(value).find();
    }

    private static String describe(String property, String original,
            String resolved, String name) {
        StringBuilder message = new StringBuilder();
        message.append('[').append(name).append("] was never substituted in ")
               .append(property).append(", so the path is being used verbatim: ")
               .append('[').append(resolved).append(']').append('.');
        if (!resolved.equals(original)) {
            message.append(" The configured value was [").append(original)
                   .append("].");
        }
        message.append(" Set ").append(name)
               .append(" in the environment of the process that reads this "
                   + "property, or write the path out in full.");
        return message.toString();
    }
}
