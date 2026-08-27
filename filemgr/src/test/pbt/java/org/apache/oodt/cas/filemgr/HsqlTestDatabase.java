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

package org.apache.oodt.cas.filemgr;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.oodt.commons.database.DatabaseConnectionBuilder;
import org.apache.oodt.commons.database.SqlScript;

/**
 * A throwaway HSQLDB instance in its own temporary directory, for the
 * property tests that exercise the JDBC-backed catalog, validation layer and
 * repository manager.
 *
 * <p>The setup mirrors {@code TestDataSourceCatalog}: a file-backed HSQLDB at
 * {@code jdbc:hsqldb:file:<tmp>/testCat;shutdown=true} reached through the
 * same {@link DatabaseConnectionBuilder} the production factories use. Each
 * instance gets its own directory so that one generated case cannot leave
 * rows, tables or file locks behind for the next one.
 *
 * <p>{@link #close()} shuts the database down, closes the pool and deletes the
 * directory; callers must call it from a {@code finally} block or HSQLDB's
 * file locks will break every later case in the run.
 */
public final class HsqlTestDatabase implements AutoCloseable {

  private final Path directory;
  private final String url;
  private final DataSource dataSource;

  private HsqlTestDatabase(Path directory, String url, DataSource dataSource) {
    this.directory = directory;
    this.url = url;
    this.dataSource = dataSource;
  }

  /** Creates an empty database in a fresh temporary directory. */
  public static HsqlTestDatabase create(String label) throws IOException {
    Path directory = Files.createTempDirectory("oodt-pbt-" + label + "-");
    String url =
        "jdbc:hsqldb:file:" + directory.toAbsolutePath() + "/testCat;shutdown=true";
    DataSource dataSource =
        DatabaseConnectionBuilder.buildDataSource("sa", "", "org.hsqldb.jdbcDriver", url);
    return new HsqlTestDatabase(directory, url, dataSource);
  }

  public DataSource dataSource() {
    return dataSource;
  }

  /** Runs a {@code .sql} script from the test classpath, as the existing suite does. */
  public void runScript(String classpathResource) throws IOException {
    URL url = HsqlTestDatabase.class.getResource(classpathResource);
    if (url == null) {
      throw new IOException("no such classpath resource: " + classpathResource);
    }
    SqlScript script = new SqlScript(new File(url.getFile()).getAbsolutePath(), dataSource);
    script.loadScript();
    script.execute();
  }

  /** Runs one DDL or DML statement. */
  public void execute(String sql) throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement statement = conn.createStatement()) {
      statement.execute(sql);
    }
  }

  /** Runs several DDL or DML statements in order. */
  public void executeAll(String... statements) throws SQLException {
    for (String sql : statements) {
      execute(sql);
    }
  }

  /** Reads a single {@code int} out of the first row of a query. */
  public int scalarInt(String sql) throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement statement = conn.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getInt(1) : -1;
    }
  }

  /** Reads one string column out of every row of a query. */
  public List<String> stringColumn(String sql) throws SQLException {
    List<String> values = new ArrayList<String>();
    try (Connection conn = dataSource.getConnection();
        Statement statement = conn.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  /**
   * Shuts the database down and removes it.
   *
   * <p>Order matters. An explicit {@code SHUTDOWN} issued while the connection
   * pool still holds idle connections open makes HSQLDB spin waiting for its
   * lock file. Closing the pool is enough: the URL carries
   * {@code shutdown=true}, so the database shuts down and drops its lock as
   * the last pooled connection goes, and the files can then be deleted.
   */
  @Override
  public void close() {
    if (dataSource instanceof AutoCloseable) {
      try {
        ((AutoCloseable) dataSource).close();
      } catch (Exception ignore) {
        // Nothing useful to do: the pool is being discarded either way.
      }
    }
    deleteRecursively(directory);
  }

  /** The JDBC URL this database is reachable at, for diagnostics. */
  public String url() {
    return url;
  }

  private static void deleteRecursively(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> ordered = new ArrayList<Path>(paths.toList());
      ordered.sort(Comparator.reverseOrder());
      for (Path path : ordered) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignore) {
          // Best effort: a leftover file in a temp directory is harmless.
        }
      }
    } catch (IOException ignore) {
      // Best effort.
    }
  }
}
