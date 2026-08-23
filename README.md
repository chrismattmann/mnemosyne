# Mnemosyne

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

## Why the name

Mnemosyne is the Titaness of memory and the mother of the Muses. Stripped to
essentials, that is what this software is. It is not primarily a scheduler or a
workflow engine — those are means. What it actually gives you is an unbroken
record: every data product, its lineage, the configuration that produced it, and
the ability to reproduce it years later. Provenance is the product.

That was the point at NASA JPL, where OODT was built to process data for
missions whose results had to remain defensible for decades. It is still the
point now.

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

The ASF retired the project because its community had wound down, not because
the software stopped working. `apache/oodt` has not moved since September 2019.
This repository carries that history forward, and continues it.

**Apache OODT is the legacy name.** If you arrived here searching for Apache
OODT, you are in the right place — this is the maintained descendant.

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

### Migrating from Apache OODT

Change the coordinates. Nothing else.

```diff
-<groupId>org.apache.oodt</groupId>
+<groupId>ai.mattmann.mnemosyne</groupId>
-<version>1.10-SNAPSHOT</version>
+<version>1.11.0</version>
```

Your `filemgr.properties`, policy XML, PGE configs, and launcher scripts do not
change, because they reference Java class names rather than Maven coordinates.

One thing to know if you are coming from a working OODT install: the 2020
`org.apache.oodt:cas-*:1.10-SNAPSHOT` build published to Apache snapshots
predates the JDK 21 and Avro work here. If your build resolved that artifact,
you were running the older code. A fixed release version removes the ambiguity.

### Do not keep both on the classpath

Mnemosyne keeps its Java packages as `org.apache.oodt.*`, so its classes share
fully-qualified names with the retired `org.apache.oodt` artifacts still
published on Maven Central — 249 of `cas-filemgr`'s classes, for instance, are
name-identical between `org.apache.oodt:cas-filemgr:1.9.1` and
`ai.mattmann.mnemosyne:cas-filemgr:1.11.0`.

Because the groupIds differ, **Maven treats them as unrelated artifacts and will
not collapse them.** If both reach one classpath, every duplicated class is
resolved by classpath order rather than by dependency mediation, and the failure
that follows looks nothing like its cause.

Mnemosyne's own build bans the retired coordinates outright. Consumers should do
the same, which turns a silent runtime hazard into a loud build failure:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.5.0</version>
  <executions>
    <execution>
      <id>enforce-no-retired-oodt</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <excludes>
              <exclude>org.apache.oodt:*</exclude>
            </excludes>
          </bannedDependencies>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

If a transitive dependency drags the old artifacts in, exclude them there rather
than relaxing the rule.

The usual fix for this — publishing a `<relocation>` POM under the old
coordinates — is unavailable, since that requires owning the `org.apache.oodt`
groupId on Maven Central.

## Components

| Module | Role |
|---|---|
| **File Manager** | Catalogs products and their metadata; the system of record |
| **Workflow Manager** | Orchestrates processing stages and their conditions |
| **Resource Manager** | Dispatches jobs across compute nodes |
| **Crawler** | Ingests products and triggers post-ingest workflows |
| **PGE** | Wraps arbitrary executables as pipeline stages |
| **CAS Metadata / CLI / Commons** | Shared metadata, command-line, and utility layers |

Pipelines are described in structured XML rather than accreted shell glue, so
they can be read, reviewed, and changed by people who did not write them.

## Build

Mnemosyne is Java, built with Maven 3, and runs on **JDK 21**.

```bash
mvn clean install
```

To skip tests and javadoc for a faster local install:

```bash
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

All source files are UTF-8. If you generate the site, set
`MAVEN_OPTS="-Dfile.encoding=UTF-8 -Xmx1g"` — documentation generation is
memory-hungry.

## RADiX

RADiX generates a complete, deployable Mnemosyne stack — File Manager, Workflow
Manager, Resource Manager, Crawler, Solr, and Tomcat, wired together — as a
starting point for a new pipeline. The archetypes live in `mvn/archetypes/` and
generate projects against the current coordinates.

[Apache DRAT](https://github.com/apache/drat) and
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

## Export control

This distribution includes cryptographic software. The country in which you
reside may restrict the import, possession, use, or re-export of encryption
software. Check your country's laws before using it. See
<https://www.wassenaar.org/> for more information.

The U.S. Department of Commerce, Bureau of Industry and Security (BIS) has
classified this software as Export Commodity Control Number (ECCN) 5D002.C.1,
which includes information security software using or performing cryptographic
functions with asymmetric algorithms. The form and manner of this distribution
makes it eligible for export under the License Exception ENC Technology Software
Unrestricted (TSU) exception (see BIS Export Administration Regulations, Section
740.13) for both object code and source code.

Mnemosyne uses Apache Tika, which uses the Bouncy Castle generic encryption
libraries to extract text and metadata from encrypted PDF files. See
<https://www.bouncycastle.org/> for details.
