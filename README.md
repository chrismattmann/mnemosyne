# Mnemosyne

[![Build](https://github.com/chrismattmann/mnemosyne/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/chrismattmann/mnemosyne/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.mattmann.mnemosyne/cas-filemgr.svg?label=Maven%20Central&color=6E4B8E)](https://central.sonatype.com/artifact/ai.mattmann.mnemosyne/cas-filemgr)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE.txt)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)

**Mnemosyne** is data pipeline and catalog middleware: it ingests data, runs it
through processing stages, and remembers everything about every product it
produces — where it came from, what generated it, and what it became.

It is the continuation of **Apache OODT** (Object Oriented Data Technology),
which the Apache Software Foundation
[retired to the Attic in April 2023](https://attic.apache.org/projects/oodt.html).

```xml
<dependency>
  <groupId>ai.mattmann.mnemosyne</groupId>
  <artifactId>cas-filemgr</artifactId>
  <version>1.11.0</version>
</dependency>
```

![Mnemosyne architecture](https://raw.githubusercontent.com/wiki/chrismattmann/mnemosyne/images/mnemosyne-architecture.png)

The **Crawler** brings products in. The **File Manager** is the system of record
— catalog, metadata, and lineage. **Workflow Manager**, **Resource Manager**, and
**PGE** process those products. **Solr** makes the catalog queryable.
**OPSUI** and the **CLI** are how you operate the stack.

## Why the name

Mnemosyne is the Titaness of memory. That is what this software is: not
primarily a scheduler or a workflow engine — those are means — but an unbroken
record of every data product, its lineage, the configuration that produced it,
and the ability to reproduce it years later. Provenance is the product.

That was the point at NASA JPL, where OODT processed data for missions whose
results had to stay defensible for decades. It still is.

## Heritage

OODT was created at NASA's Jet Propulsion Laboratory and became, in 2010, the
first NASA project ever contributed to the Apache Software Foundation. It was an
Apache Top Level Project until April 2023, and it ran real science:

- **NASA's Orbiting Carbon Observatory** and other Earth science missions,
  including **Seawinds/QuickSCAT**, **NPP Sounder PEATE**, and **Soil Moisture
  Active Passive (SMAP)**
- **NASA's Planetary Data System (PDS)** — the planetary data archive holding
  30+ years of collected data
- The **National Cancer Institute's Early Detection Research Network (EDRN)** —
  40+ institutions researching early biomarkers of disease
- **Apache DRAT**, a distributed release audit tool built on top of it

The ASF retired it because its community had wound down, not because the
software stopped working; `apache/oodt` has not moved since September 2019.
**Apache OODT is the legacy name** — if you arrived here searching for it, this
is the maintained descendant.

## What changed, and what did not

Mnemosyne is a rename plus continued maintenance, not a rewrite. The code is the
same lineage, several hundred commits further along.

| | Apache OODT | Mnemosyne |
|---|---|---|
| Maven groupId | `org.apache.oodt` | `ai.mattmann.mnemosyne` |
| Version | `1.10-SNAPSHOT` | `1.11.0` |
| Java packages | `org.apache.oodt.*` | **unchanged** |
| artifactIds | `cas-filemgr`, … | **unchanged** |
| RPC transport | XML-RPC | Avro |
| Runtime | Java 8 | JDK 21 |

**Java package names deliberately did not change.** Package names are
namespaces, not endorsements. Renaming them would ripple into every downstream
consumer's Spring policy XML and launcher scripts, where a fully-qualified class
name is a string with no compiler to check it — one downstream project alone
names 247 distinct classes from configuration. The cost is real and the benefit
is cosmetic.

`cas-*` artifactIds are also unchanged. "Catalog and Archive Service" is an
accurate description and carries fifteen years of citations.

**Coming from a working OODT install?** Changing the coordinates is the whole
migration, but there are three things that will bite you — the two artifacts
cannot share a classpath, Lucene indexes must be rebuilt, and logging is
quieter by default. [UPGRADING.md](UPGRADING.md) covers each.

## Components

| Module | Role |
|---|---|
| **File Manager** | Catalogs products and their metadata; the system of record |
| **Workflow Manager** | Orchestrates processing stages and their conditions |
| **Resource Manager** | Dispatches jobs across compute nodes |
| **Crawler** | Ingests products and triggers post-ingest workflows |
| **PGE** | Wraps arbitrary executables as pipeline stages |
| **OPSUI** | Vue 3 monitoring UI at `/opsui/` — catalog, lineage, workflow instances |
| **pcs-services** | JSON health, catalog, workflow, and pedigree APIs at `/pcs/services/` |
| **CAS Metadata / CLI / Commons** | Shared metadata, command-line, and utility layers |

## Build

Mnemosyne is Java, built with Maven 3, and runs on **JDK 21**.

```bash
mvn clean install
```

Tika is used for MIME detection, so only `tika-core` is a dependency. The
format parsers behind the two example metadata extractors are opt in:

```bash
mvn clean install -Ptika-parsers
```

To skip tests and javadoc for a faster local install:

```bash
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

Source is UTF-8; generating the site wants
`MAVEN_OPTS="-Dfile.encoding=UTF-8 -Xmx1g"`.

### Tests

```bash
mvn test                 # whole reactor
mvn -pl workflow test    # one module
```

Every push and pull request against `master` builds and tests the full reactor
on JDK 21, then generates and compiles a RADiX starter without Docker; the Build
badge above reports it. The separate **RADiX Docker Compose** workflow runs full
integration tests when started manually from GitHub Actions. Signing and deployment live in the
`release` profile and are not part of that run, so a pull request never needs a
GPG key to go green.

Some suites start real servers on fixed ports and write into temporary
directories, so they are sensitive to being run in parallel with each other. If
a run fails in CI, the surefire reports are attached to the failed job as an
artifact.

## RADiX

RADiX generates a complete, deployable Mnemosyne stack — File Manager, Workflow
Manager, Resource Manager, PCS, OPSUI and Tomcat, wired together — as a starting
point for a new pipeline. The archetypes live in `mvn/archetypes/`.

`mvn clean install` builds a generator carrying this repository's version; the
archetype is not published, so that install has to finish first.

```bash
RADIX_GENERATOR="$PWD/mvn/archetypes/radix/target/classes/bin/radix"
cd .. && sh "$RADIX_GENERATOR" -B -DgroupId=example -DartifactId=my-pipeline
cd my-pipeline && mvn clean package && sh docker/verify.sh
```

`verify.sh` brings the stack up under Docker Compose and checks it end to end;
OPSUI is then at `http://localhost:8080/opsui/`. The generated `README.txt`
covers configuration, persistence, the optional Crawler and Solr profiles, and
cleanup.

[DRAT](https://github.com/chrismattmann/drat) and
[BigTranslate](https://github.com/chrismattmann/bigtranslate) are both RADiX
projects, and are the best worked examples of what a real deployment looks like.

## Contributing

Issues and pull requests here on GitHub:
<https://github.com/chrismattmann/mnemosyne>

The Apache JIRA, `dev@oodt.apache.org` mailing list, Confluence wiki, and
`git-wip-us.apache.org` are all retired along with the Apache project and are no
longer monitored. Please do not file there.

## License

Apache License 2.0. See [LICENSE.txt](LICENSE.txt) and [NOTICE.txt](NOTICE.txt).

Collective work: Copyright 2010-2023 The Apache Software Foundation, and
subsequent contributors. Mnemosyne is an independent continuation and is not
endorsed by, affiliated with, or a product of the Apache Software Foundation.
"Apache", "Apache OODT", and the Apache feather logo are trademarks of the
Apache Software Foundation, used here only to describe this project's origin.

Mnemosyne includes subcomponents with separate copyright notices and license
terms; see LICENSE.txt.
