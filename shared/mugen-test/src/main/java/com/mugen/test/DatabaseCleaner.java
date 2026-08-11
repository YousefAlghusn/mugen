package com.mugen.test;

import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Empties every table except Flyway's own, so a test class starts from a known
 * database even though the container is shared with every other class.
 * <p>
 * Needed because one context means one database. A test class that deliberately
 * commits — {@code AuthFlowTest} must, since a rolled-back revocation is not a
 * revocation — would otherwise leave its rows visible to whatever runs next, and any
 * assertion phrased as a total silently starts counting someone else's data.
 */
public class DatabaseCleaner {

    /** Flyway's history is the schema itself; deleting it would strand an already-migrated database. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private final ObjectProvider<DataSource> dataSource;

    public DatabaseCleaner(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /** No-op when the service has no relational database, so this is safe to wire everywhere. */
    public void clean() {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            return;
        }
        try (Connection connection = source.getConnection()) {
            deleteAll(connection, tablesIn(connection));
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not clean the test database", ex);
        }
    }

    /**
     * Restricted to the connection's own schema — {@code dbo} on SQL Server,
     * {@code public} on Postgres. Asking for every schema in the catalog also returns
     * the server's internal ones, and those cannot be emptied or usefully attempted.
     */
    private static List<String> tablesIn(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String schema = connection.getSchema();
        List<String> tables = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                String owner = rows.getString("TABLE_SCHEM");
                if (FLYWAY_HISTORY.equalsIgnoreCase(name) || isSystemSchema(owner)) {
                    continue;
                }
                tables.add(owner == null ? quote(name) : quote(owner) + "." + quote(name));
            }
        }
        return tables;
    }

    /** Belt and braces, for a driver whose {@code getSchema()} returns null. */
    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        return schema.equalsIgnoreCase("sys")
                || schema.equalsIgnoreCase("INFORMATION_SCHEMA")
                || schema.startsWith("pg_");
    }

    /**
     * Deletes in passes rather than working out the foreign-key order: a delete that a
     * child row still references fails, so it is retried on the next pass once that
     * child is gone. Terminates because a pass that clears nothing cannot be a cycle
     * the next pass would resolve either.
     */
    private static void deleteAll(Connection connection, List<String> tables) throws SQLException {
        List<String> remaining = new ArrayList<>(tables);

        while (!remaining.isEmpty()) {
            int before = remaining.size();
            Iterator<String> pass = remaining.iterator();

            while (pass.hasNext()) {
                String table = pass.next();
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM " + table);
                    pass.remove();
                } catch (SQLException stillReferenced) {
                    // Left for the next pass.
                }
            }

            if (remaining.size() == before) {
                throw new IllegalStateException("Could not empty " + remaining + " (a foreign key cycle?)");
            }
        }
    }

    /** The SQL standard's quoting, which SQL Server and Postgres both accept. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
