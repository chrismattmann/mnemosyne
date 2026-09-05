# Upgrading from Apache OODT

Everything here is for an existing Apache OODT deployment moving to Mnemosyne.
A new pipeline needs none of it; see the [README](README.md).

## Migrating from Apache OODT

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

## Do not keep both on the classpath

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

## Upgrading from Apache OODT: your indexes must be rebuilt

**Mnemosyne 1.12.0 cannot read a Lucene index written by Apache OODT.** Not
slowly, not with reduced functionality — it will not open it.

Lucene reads indexes written by one major version back. OODT shipped Lucene
6; Mnemosyne is on Lucene 10. That is four majors, so the File Manager
catalog and the Workflow Manager instance repository both have to be built
again from their sources:

- **`LuceneCatalog`** — the File Manager product catalog. Re-ingest, or
  re-index from whichever catalog holds the authority.
- **`LuceneWorkflowInstanceRepository`** — workflow instance history. This is
  usually operational history rather than a record of record, and is
  ordinarily fine to start empty.

Check before you upgrade whether your catalog is the only copy of anything.
If it is, export it first. For a system whose purpose is keeping an unbroken
record of every data product, a catalog you cannot open is the worst thing
that can happen to you, and no upgrade is worth it.

**If you would rather not rebuild**, the archived
[`apache/oodt`](https://github.com/apache/oodt) still exists, still builds,
and still reads those indexes. It is not maintained, but it has not stopped
working. Mnemosyne is a continuation, not an obligation.

Solr is a separate matter and is unaffected: Mnemosyne talks to Solr over the
network as a client, so your Solr can be upgraded, or not, on its own
schedule.

## Upgrading from Apache OODT: logging is quieter by default

Stock OODT shipped `.level = ALL` in every component's `logging.properties`,
and `<Root level="debug">` in most `log4j2.xml` files. A default deployment
therefore logged at FINE and below, which is what
[OODT-991](https://issues.apache.org/jira/browse/OODT-991) complained about in
2018. Mnemosyne ships `INFO` instead, and sends only errors to the console
while the file appenders keep the full record.

If you depended on `FINE` output from a stock deployment, set it back
deliberately in `etc/logging.properties` or `etc/log4j2.xml`. Both files are
still shipped to `etc/` and are still what the start scripts point at.

What changed alongside it is worth knowing if you script the CLIs: those two
files used to be packaged **inside** some of the library jars as well, so any
application with, say, `cas-workflow` on its classpath silently adopted its
logging configuration. That is how the File Manager's own command line tools
came to log DEBUG to standard output, which corrupts the output of any tool
whose stdout is piped — `query-tool ... | DeleteProduct --read` fed a log line
in place of a product id. Library jars no longer carry logging configuration,
and the console appenders write to standard error rather than standard output.
