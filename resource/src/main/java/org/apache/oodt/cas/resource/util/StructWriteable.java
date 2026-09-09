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


package org.apache.oodt.cas.resource.util;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * An interface requiring implementing classes to define how they serialize
 * themselves to and from a plain structure of maps, lists and strings.
 * </p>
 *
 * <p>
 * Named for XML-RPC until 2026 because that was the transport when it was
 * written. No XML-RPC transport remains -- nothing imports
 * org.apache.xmlrpc any more -- and the name outlived it badly: the Avro
 * conversion in AvroTypeFactory ignored this contract entirely and
 * special-cased one implementation instead, so a TaskJobInput crossed the
 * wire with every field unset and no workflow task could run on a remote
 * node at all. A name that reads as dead scaffolding gets treated as dead
 * scaffolding.
 * </p>
 */
public interface StructWriteable {

  /**
   * This method should define how to take an XML-RPC serializable
   * {@link Object} and load the internal data members of the implementing class
   * from the given input {@link Object}.
   * 
   * @param in
   *          The {@link Object} to read in and instantiate the implementation
   *          of this class with.
   */
  void read(Object in);

  /**
   * 
   * @return An XML-RPC safe serialization {@link Object} of the implementing
   *         class for this interface.
   */
  Object write();

}
