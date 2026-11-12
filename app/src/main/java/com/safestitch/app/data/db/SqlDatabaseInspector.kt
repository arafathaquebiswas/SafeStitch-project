package com.safestitch.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

data class SqlTableStat(
    val tableName: String,
    val rowCount: Long,
    val columns: List<String>,
    val createSql: String,
)

data class SqlPresetQuery(
    val id: String,
    val title: String,
    val sql: String,
)

data class SqlQueryResult(
    val sql: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val executionTimeMs: Long,
    val error: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null
}

/**
 * Inspects the on-device SQLite / Room database ([SafeStitchDatabase]), providing live table statistics,
 * column schemas, read-only SQL query execution, and portable SQL dump generation.
 */
object SqlDatabaseInspector {

    val PRESET_QUERIES: List<SqlPresetQuery> = listOf(
        SqlPresetQuery(
            id = "equipment_summary",
            title = "Equipment & Inspections",
            sql = "SELECT c.code, c.name, c.type, COUNT(i.id) AS inspections " +
                "FROM checkpoints c LEFT JOIN inspections i ON i.checkpointId = c.id " +
                "GROUP BY c.id ORDER BY c.code",
        ),
        SqlPresetQuery(
            id = "captured_photos",
            title = "Captured Photos",
            sql = "SELECT id, timestamp, imagePath, statusTags FROM captured_inspections ORDER BY timestamp DESC",
        ),
        SqlPresetQuery(
            id = "open_actions",
            title = "Corrective Actions",
            sql = "SELECT id, priority, status, responsibleName, description FROM corrective_actions ORDER BY dueAt ASC",
        ),
        SqlPresetQuery(
            id = "locations",
            title = "Factory Locations",
            sql = "SELECT fa.name AS factory, b.name AS building, f.name AS floor, a.name AS area " +
                "FROM areas a JOIN floors f ON a.floorId = f.id " +
                "JOIN buildings b ON f.buildingId = b.id " +
                "JOIN factories fa ON b.factoryId = fa.id " +
                "ORDER BY fa.name, b.name, f.name, a.name",
        ),
    )

    private val INTERNAL_TABLES = setOf(
        "android_metadata",
        "sqlite_sequence",
        "room_master_table",
    )

    /**
     * Returns all user tables in the SQLite database ordered alphabetically, with live row counts and column names.
     */
    fun inspectTables(db: SupportSQLiteDatabase): List<SqlTableStat> {
        val tableEntries = mutableListOf<Pair<String, String>>()
        db.query(
            "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name COLLATE NOCASE",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0).orEmpty()
                val createSql = cursor.getString(1).orEmpty()
                if (name.isNotBlank() && name !in INTERNAL_TABLES) {
                    tableEntries.add(name to createSql)
                }
            }
        }

        return tableEntries.map { (tableName, createSql) ->
            val safeIdent = quoteIdentifier(tableName)
            val count = db.query("SELECT COUNT(*) FROM $safeIdent").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info($safeIdent)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val typeIdx = cursor.getColumnIndex("type")
                while (cursor.moveToNext()) {
                    val colName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val colType = if (typeIdx >= 0) cursor.getString(typeIdx).orEmpty() else ""
                    if (colName.isNotBlank()) {
                        columns.add(if (colType.isNotBlank()) "$colName ($colType)" else colName)
                    }
                }
            }
            SqlTableStat(
                tableName = tableName,
                rowCount = count,
                columns = columns,
                createSql = createSql,
            )
        }
    }

    /**
     * Executes a read-only SQL query (`SELECT` or `PRAGMA`) against [db] and returns up to [maxRows] rows.
     */
    fun executeReadOnlyQuery(
        db: SupportSQLiteDatabase,
        rawSql: String,
        maxRows: Int = 100,
    ): SqlQueryResult {
        val startNs = System.nanoTime()
        val trimmed = rawSql.trim().trimEnd(';').trim()
        if (trimmed.isEmpty()) {
            return SqlQueryResult(
                sql = rawSql,
                columns = emptyList(),
                rows = emptyList(),
                executionTimeMs = 0L,
                error = "Enter a SQL SELECT query.",
            )
        }

        if (!isReadOnlySql(trimmed)) {
            return SqlQueryResult(
                sql = trimmed,
                columns = emptyList(),
                rows = emptyList(),
                executionTimeMs = 0L,
                error = "Only read-only SELECT or PRAGMA queries are allowed in the SQL inspector.",
            )
        }

        return try {
            db.query(trimmed).use { cursor ->
                val cols = cursor.columnNames?.toList().orEmpty()
                val rows = mutableListOf<List<String>>()
                while (cursor.moveToNext() && rows.size < maxRows) {
                    val row = List(cols.size) { idx ->
                        when (cursor.getType(idx)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx).toString()
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx).toString()
                            android.database.Cursor.FIELD_TYPE_BLOB -> "<BLOB ${cursor.getBlob(idx)?.size ?: 0}B>"
                            else -> cursor.getString(idx) ?: "NULL"
                        }
                    }
                    rows.add(row)
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                SqlQueryResult(
                    sql = trimmed,
                    columns = cols,
                    rows = rows,
                    executionTimeMs = elapsedMs,
                    error = null,
                )
            }
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            SqlQueryResult(
                sql = trimmed,
                columns = emptyList(),
                rows = emptyList(),
                executionTimeMs = elapsedMs,
                error = e.message ?: "SQL execution failed.",
            )
        }
    }

    /**
     * Checks whether a SQL statement is a single read-only `SELECT`, `WITH ... SELECT`, or `PRAGMA` query.
     */
    fun isReadOnlySql(sql: String): Boolean {
        val cleaned = sql.trim().trimEnd(';').trim()
        if (cleaned.isEmpty() || cleaned.contains(';')) return false
        val upper = cleaned.uppercase(Locale.ROOT)
        val startsWithAllowed = upper.startsWith("SELECT ") ||
            upper.startsWith("WITH ") ||
            upper.startsWith("PRAGMA ") ||
            upper.startsWith("EXPLAIN ")
        if (!startsWithAllowed) return false
        val forbiddenTokens = listOf(
            "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "CREATE ", "REPLACE ", "TRUNCATE ", "ATTACH ", "DETACH ",
        )
        return forbiddenTokens.none { token -> upper.contains(token) }
    }

    private fun quoteIdentifier(ident: String): String =
        "\"" + ident.replace("\"", "\"\"") + "\""
}
