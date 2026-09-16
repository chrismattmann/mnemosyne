# Releasing Mnemosyne

Two things are published from this repository, and they are not the same
thing.

## A release

Immutable, published by hand, and a couple of hours before it resolves.
This is what applications depend on.

```bash
# 1. drop the -SNAPSHOT: 1.13.3-SNAPSHOT releases as 1.13.3.
#    A larger jump is a choice, not arithmetic -- 1.14.0 if the release
#    adds API, 2.0.0 if it breaks any.
mvn versions:set -DnewVersion=1.13.3        # see the note below
git commit -am "Mnemosyne 1.13.3" && open a PR

# 2. after it merges, prime the gpg agent from a shell you can type into.
#    A cold agent cannot launch pinentry from a backgrounded mvn and fails
#    with "No pinentry" rather than prompting.
gpg --detach-sign --local-user 3A05BD3E7BCE0893 -o /tmp/x.sig /tmp/x

# 3. deploy: builds, signs, uploads, and stops
mvn -B clean deploy -Prelease

# 4. publish by hand at https://central.sonatype.com/publishing/deployments
#    autoPublish is false on purpose. A published version is immutable.

# 5. tag the commit that was released
git tag -a 1.13.3 <sha> -m "Mnemosyne 1.13.3" && git push origin 1.13.3

# 6. move master to the next snapshot, so the next fix is testable
mvn versions:set -DnewVersion=1.13.4-SNAPSHOT
```

`versions:set` refuses to run from the root: the `oodt` aggregator inherits
its version from `oodt-core` and the plugin says *"Project version is
inherited from parent."* Change each project's own `<version>` and every
`<parent>` carrying the `ai.mattmann.mnemosyne` groupId, and leave the
archetype resource templates alone -- their versions are `${...}`
placeholders filled at generation time.

Central sync takes hours, not minutes. 1.13.0 took about 2h20m from publish
to resolvable. Check `repo1.maven.org`, never `search.maven.org` -- the
search index lags publication by far longer and reported zero artifacts for
this groupId while all fifteen were live.

## A snapshot

Mutable, published in about four minutes, resolvable immediately. This is
for testing a fix against an application before committing to a release.

```bash
# master sits on a -SNAPSHOT between releases, so this just works
mvn -B clean deploy -Prelease
```

The publishing plugin routes `-SNAPSHOT` versions to Central's snapshot
repository instead of the staging flow, so there is nothing to click and no
sync to wait for. Re-push as often as it takes.

An application picks it up by naming it:

```xml
<oodt.version>1.13.4-SNAPSHOT</oodt.version>
```

having declared the repository:

```xml
<repository>
  <id>central-snapshots</id>
  <url>https://central.sonatype.com/repository/maven-snapshots/</url>
  <releases><enabled>false</enabled></releases>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```

Only branches may do this. A snapshot is mutable, so a green build against
one does not stay green, and the applications refuse a snapshot in a pull
request targeting their default branch. Cut the release before merging.

## What is published

Fifteen artifacts. Seven are excluded because they are aggregators or
archetypes rather than libraries -- `oodt`, `oodt-webapps`,
`workflow-services`, `maven-cas-install-plugin`, `archetypes`,
`radix-archetype`, `opsui-archetype` -- listed in `excludeArtifacts` in
`core/pom.xml` and kept in step with the `maven.deploy.skip` properties in
those modules.
