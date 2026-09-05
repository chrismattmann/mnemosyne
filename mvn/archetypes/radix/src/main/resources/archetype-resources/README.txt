# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

######################################################################################
# Mnemosyne RADiX

This project was generated for Mnemosyne ${oodt}.

Required tools
--------------
* JDK 21 (check both `java -version` and `mvn -version`).
* Maven 3.9 or newer.
* Docker Engine or Docker Desktop, with Docker Compose v2 or newer.
* A POSIX shell to run the verification script.

Node.js and npm are needed when building Mnemosyne itself. This generated
project consumes the already-built OPSUI WAR, so it does not need Node.js.
The current archetype is installed locally from the Mnemosyne source tree.

Build and verify
----------------
From this directory:

    mvn clean package
    sh docker/verify.sh

The script builds the images, starts the services, and waits for readiness.
It then checks all three Avro services, ingests and queries a small product,
checks the archived bytes, runs a workflow that copies a file, and checks
OPSUI and the PCS APIs. It then starts the optional Crawler and checks automatic
ingestion of an incoming text file. A failed check gives a non-zero exit status.

docker/SmokeTest.java is included so you can verify this deployment after setup
or configuration changes. It uses the optional Compose test profile and is not
part of the normal service runtime. The checks assume the starter's default
policies and shared data paths; update the checks if you change those defaults.

Open http://localhost:8080/opsui/ after verification. The stack stays running.
Port 8080 is bound to localhost. Avro ports are available only inside Compose.
Stop the stack with:

    docker compose --profile '*' down

The named data volume preserves the catalog, workflow records, and files.
`docker compose --profile '*' down --volumes` also deletes that data; use it only for a
disposable test deployment.

Deployment choices
------------------
The default stack uses Lucene, Java 21, and Tomcat 9. OPSUI and PCS run in the
same Tomcat instance. Workflow tasks execute locally in the Workflow Manager
container. Resource Manager is available for configuration and inspection;
remote worker execution needs a separately configured worker.

Policy and configuration files are copied into the images. Change the files
under each module's src/main/resources, rebuild with Maven, then run Compose
with --build to apply them. Add Java tasks to the extensions module.

The optional Crawler scans /oodt/data/incoming on the shared volume:

    docker compose --profile crawler up --build -d crawler

The old Solr Maven profile and Kubernetes manifests are retained for migration.
They are not part of this verified starter and still need separate upgrades.
Use `docker compose build`, not the old `mvn -Pdocker` image build profile.

The distribution/target archive also contains the assembled deployment files.
The supported local verification path is the Compose command above.
