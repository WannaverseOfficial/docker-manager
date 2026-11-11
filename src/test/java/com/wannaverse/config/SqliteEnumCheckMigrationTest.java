package com.wannaverse.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SqliteEnumCheckMigrationTest {

    @Test
    void removesOnlyEnumChecks() {
        String sql =
                "CREATE TABLE docker_operations_t (id varchar(255) not null, operation_type"
                        + " varchar(255) not null check ((operation_type in"
                        + " ('CONTAINER_CREATE','CONTAINER_DELETE'))), level integer check (level"
                        + " between 0 and 3), size bigint check (size > 0), primary key (id))";
        String out = SqliteEnumCheckMigration.ENUM_CHECK.matcher(sql).replaceAll("");
        assertFalse(out.contains("CONTAINER_CREATE"));
        assertFalse(out.contains("between"));
        assertTrue(out.contains("check (size > 0)"), out);
        assertTrue(out.contains("operation_type varchar(255) not null,"), out);
        assertTrue(out.endsWith("primary key (id))"), out);
    }
}
