package com.safestitch.app

import com.safestitch.app.data.db.SqlDatabaseInspector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlDatabaseInspectorTest {

    @Test
    fun presetQueries_areAllValidReadOnlySql() {
        assertTrue(SqlDatabaseInspector.PRESET_QUERIES.isNotEmpty())
        SqlDatabaseInspector.PRESET_QUERIES.forEach { preset ->
            assertTrue(
                "Preset '${preset.title}' should be valid read-only SQL",
                SqlDatabaseInspector.isReadOnlySql(preset.sql),
            )
        }
    }

    @Test
    fun isReadOnlySql_acceptsSelectAndPragmaAndRejectsMutatingStatements() {
        assertTrue(SqlDatabaseInspector.isReadOnlySql("SELECT * FROM checkpoints LIMIT 25"))
        assertTrue(SqlDatabaseInspector.isReadOnlySql("  select id, statusTags from captured_inspections; "))
        assertTrue(SqlDatabaseInspector.isReadOnlySql("PRAGMA table_info(checkpoints)"))
        assertTrue(SqlDatabaseInspector.isReadOnlySql("WITH cte AS (SELECT id FROM inspections) SELECT * FROM cte"))

        assertFalse(SqlDatabaseInspector.isReadOnlySql(""))
        assertFalse(SqlDatabaseInspector.isReadOnlySql("DROP TABLE checkpoints"))
        assertFalse(SqlDatabaseInspector.isReadOnlySql("DELETE FROM captured_inspections"))
        assertFalse(SqlDatabaseInspector.isReadOnlySql("UPDATE checkpoints SET name = 'X'"))
        assertFalse(SqlDatabaseInspector.isReadOnlySql("INSERT INTO checkpoints VALUES (1, 'A')"))
        assertFalse(SqlDatabaseInspector.isReadOnlySql("SELECT * FROM checkpoints; DROP TABLE checkpoints"))
    }
}
