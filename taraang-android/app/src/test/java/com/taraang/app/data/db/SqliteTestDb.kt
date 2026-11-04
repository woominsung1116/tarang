package com.taraang.app.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM 테스트용 실제 SQLite(sqlite-jdbc). Room이 내보낸 스키마(app/schemas/.../N.json)의 createSql로 표를 만들어,
 * 앱이 쓰는 SQL 문자열(쿼리·마이그레이션)을 기기 없이 돌려 본다.
 */
class SqliteTestDb private constructor(val connection: Connection) : AutoCloseable {

    /** `:name` 자리표시를 이름으로 채워 실행한다. 값은 Long/Int/Double/String/null. */
    fun queryLong(sql: String, params: Map<String, Any?> = emptyMap()): Long? {
        val names = PARAM.findAll(sql).map { it.groupValues[1] }.toList()
        connection.prepareStatement(PARAM.replace(sql, "?")).use { st ->
            names.forEachIndexed { i, name -> st.setObject(i + 1, params.getValue(name)) }
            st.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
        }
    }

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** 표의 열 정의(이름, 타입, NOT NULL, 기본값, PK 순서). 마이그레이션 결과와 새 스키마를 비교하는 데 쓴다. */
    fun columns(table: String): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildList { while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}") }
            }
        }.sorted()

    fun indices(table: String): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }.sorted()

    override fun close() = connection.close()

    companion object {
        private val PARAM = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")
        private val SCHEMA_DIR = File("schemas/com.taraang.app.data.db.TaraangDatabase")

        fun schemaFile(version: Int) = File(SCHEMA_DIR, "$version.json")

        /** Room이 내보낸 [version] 스키마로 빈 메모리 DB를 만든다. */
        fun fromSchema(version: Int): SqliteTestDb {
            val db = SqliteTestDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
            db.exec("PRAGMA foreign_keys = ON")
            val root = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject.getValue("database").jsonObject
            for (entity in root.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.exec(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { idx ->
                    db.exec(idx.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            return db
        }
    }
}
