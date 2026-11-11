package com.wannaverse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes the enum CHECK constraints Hibernate writes into SQLite tables, before Hibernate starts.
 *
 * <p>Hibernate creates enum columns as {@code status varchar check ((status in ('A','B')))} with
 * the values of the version that created the table, and {@code ddl-auto: update} never changes
 * them. A database from an older version therefore rejects every value added since: in 2.0, saving
 * a "container changed" history entry or a stacks permission failed on databases created by 1.x.
 * The application validates these values itself, so the constraints are dropped.
 *
 * <p>SQLite can't drop a constraint, so affected tables are rebuilt with the documented procedure
 * (create, copy, drop, rename) in one transaction, after a backup copy of the database is made.
 */
public final class SqliteEnumCheckMigration {
    private static final Logger log = LoggerFactory.getLogger(SqliteEnumCheckMigration.class);

    /** check ((col in ('A','B'))) and check (col between 0 and 3), as Hibernate writes them. */
    static final Pattern ENUM_CHECK =
            Pattern.compile(
                    "\\s+check\\s*\\(\\s*\\(?\\s*[\\w\"]+\\s+(?:in\\s*\\([^()]*\\)|between\\s+-?\\d+\\s+and\\s+-?\\d+)\\s*\\)?\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    private SqliteEnumCheckMigration() {}

    /** Runs the migration on the configured database file, if it exists. */
    public static void run() {
        Path db = databaseFile();
        if (db == null || !Files.exists(db)) return;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            Map<String, String> tables = new LinkedHashMap<>();
            try (Statement st = c.createStatement();
                    ResultSet rs =
                            st.executeQuery(
                                    "select name, sql from sqlite_master where type = 'table' and"
                                            + " sql is not null and name not like 'sqlite_%'")) {
                while (rs.next()) {
                    String sql = rs.getString(2);
                    if (ENUM_CHECK.matcher(sql).find()) tables.put(rs.getString(1), sql);
                }
            }
            if (tables.isEmpty()) return;

            Path backup = db.resolveSibling(db.getFileName() + ".before-2.0-migration");
            if (!Files.exists(backup)) {
                try (Statement st = c.createStatement()) {
                    st.execute("vacuum into '" + backup.toString().replace("'", "''") + "'");
                }
                log.info("Backed up the database to {} before migrating it", backup);
            }

            try (Statement st = c.createStatement()) {
                st.execute("pragma foreign_keys = off");
            }
            c.setAutoCommit(false);
            try {
                for (var t : tables.entrySet()) rebuild(c, t.getKey(), t.getValue());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
                try (Statement st = c.createStatement()) {
                    st.execute("pragma foreign_keys = on");
                }
            }
            log.info(
                    "Removed old enum constraints from {} table(s) so newer values can be saved",
                    tables.size());
        } catch (Exception e) {
            // Leave the database as it was; the app still starts and reports what fails.
            log.error("Could not migrate the database's enum constraints: {}", e.getMessage(), e);
        }
    }

    private static void rebuild(Connection c, String table, String sql) throws Exception {
        String temp = table + "__dm_migrate";
        String withoutChecks = ENUM_CHECK.matcher(sql).replaceAll("");
        // "CREATE TABLE name (" -> "CREATE TABLE name__dm_migrate ("
        int paren = withoutChecks.indexOf('(');
        String createTemp = "CREATE TABLE \"" + temp + "\" " + withoutChecks.substring(paren);

        List<String> extras = new ArrayList<>();
        try (var ps =
                c.prepareStatement(
                        "select sql from sqlite_master where tbl_name = ? and type in ('index',"
                                + " 'trigger') and sql is not null")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) extras.add(rs.getString(1));
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute(createTemp);
            st.execute("INSERT INTO \"" + temp + "\" SELECT * FROM \"" + table + "\"");
            st.execute("DROP TABLE \"" + table + "\"");
            st.execute("ALTER TABLE \"" + temp + "\" RENAME TO \"" + table + "\"");
            for (String extra : extras) st.execute(extra);
        }
    }

    /** The SQLite file from SPRING_DATASOURCE_URL, or the default under APP_DATA_DIR. */
    static Path databaseFile() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url != null && url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            return path.isBlank() ? null : Path.of(path);
        }
        String dir = System.getenv("APP_DATA_DIR");
        return Path.of(dir == null || dir.isBlank() ? "database" : dir, "data.db");
    }
}
