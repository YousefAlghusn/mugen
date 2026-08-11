package com.mugen.test.integration;

import com.mugen.test.IntegrationTest;
import com.mugen.test.support.DatabaseCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The price of one shared database, and the thing that makes it payable.
 * <p>
 * A test class that deliberately commits — and some must, since a rolled-back revocation
 * is not a revocation — leaves its rows for whatever runs next. Per-class containers used
 * to hide that. {@link DatabaseCleaner} replaces the isolation they were providing.
 */
@IntegrationTest
class DatabaseCleanerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseCleaner cleaner;

    @Test
    @DisplayName("committed rows are gone, including from a table a foreign key points into")
    void emptiesEveryTableInDependencyOrder() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS parent (id INT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS child (
                    id INT PRIMARY KEY,
                    parent_id INT REFERENCES parent(id))
                """);
        jdbc.update("INSERT INTO parent (id) VALUES (1)");
        jdbc.update("INSERT INTO child (id, parent_id) VALUES (1, 1)");

        cleaner.clean();

        // parent cannot be emptied until child is, which is the ordering the cleaner
        // discovers by retrying rather than by reading the foreign keys.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parent", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM child", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Flyway's history is left alone — it is the schema, not test data")
    void sparesTheFlywayHistory() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS flyway_schema_history (installed_rank INT PRIMARY KEY)");
        jdbc.update("INSERT INTO flyway_schema_history (installed_rank) VALUES (1)");

        cleaner.clean();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isEqualTo(1);
    }
}
