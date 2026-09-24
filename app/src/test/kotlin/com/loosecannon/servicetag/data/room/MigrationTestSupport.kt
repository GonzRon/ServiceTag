package com.loosecannon.servicetag.data.room

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * What the migration tests share: building an old database from the schema that shipped with it,
 * and opening the result through Room so Room runs the migrations and validates what they left
 * behind against the compiled schema.
 *
 * Room ships `SQLiteDriverMigrationTestHelper`, but only in `room3-testing`'s **android** variant:
 * every constructor there takes an `Instrumentation` and loads the exported schema out of the
 * instrumentation context's assets, so it cannot run in a JVM unit test. There is no JVM variant
 * of that artefact at 3.0.3, so the same proof is assembled by hand — and it is not weaker. The
 * old database is built from the *exported* schema (its DDL and its `room_master_table` identity
 * hash, not a hand-copied guess) and the new side is opened through Room with the migrations
 * registered, which is exactly what `runMigrationsAndValidate` does. A migration that produced a
 * column, index or foreign key the entities don't declare fails on open.
 */

/** Opens [file] over the bundled driver, outside Room, for raw schema and row questions. */
internal fun <T> withConnection(file: File, block: (SQLiteConnection) -> T): T =
    BundledSQLiteDriver().open(file.absolutePath).use(block)

/**
 * Builds the database schema [version] shipped — every table, every index, the `setupQueries`
 * that carry Room's identity hash, and `PRAGMA user_version` — then hands the connection to
 * [seed] for the rows the test wants to watch survive.
 */
internal fun createSchemaVersion(version: Int, file: File, seed: (SQLiteConnection) -> Unit) =
    withConnection(file) { c ->
        val schema = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject
            .getValue("database").jsonObject
        schema.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            c.execSQL(entity.sql("createSql", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                c.execSQL(index.jsonObject.sql("createSql", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
        c.execSQL("PRAGMA user_version = $version")
        seed(c)
    }

/**
 * Opens [file] as the compiled [AppDatabase] with the whole migration chain registered. Opening
 * is what runs the migrations the file needs and validates the result; nothing here chooses which
 * ones run — the database's own `user_version` does.
 */
internal fun openMigrated(file: File): AppDatabase = Room
    .databaseBuilder<AppDatabase>(name = file.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .addMigrations(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8,
    )
    .build()

/**
 * Opens [file] as a **fresh** database — no migration runs, because there is nothing on disk to
 * migrate — so its schema is whatever the compiled entities say. Comparing a migrated file against
 * one of these is how "the migration produces the same schema as a new install" is asserted without
 * hand-writing the expected DDL twice.
 */
internal fun openFresh(file: File): AppDatabase = Room
    .databaseBuilder<AppDatabase>(name = file.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .build()

internal fun SQLiteConnection.tableNames(): Set<String> = buildSet {
    prepare("SELECT name FROM sqlite_master WHERE type='table'").use { s ->
        while (s.step()) add(s.getText(0))
    }
}

internal fun SQLiteConnection.indexNamesOn(table: String): Set<String> = buildSet {
    prepare("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?").use { s ->
        s.bindText(1, table)
        while (s.step()) add(s.getText(0))
    }
}

internal fun SQLiteConnection.columnNamesOf(table: String): Set<String> = buildSet {
    prepare("SELECT name FROM pragma_table_info(?)").use { s ->
        s.bindText(1, table)
        while (s.step()) add(s.getText(0))
    }
}

/**
 * The columns of [table] in declaration order, each with its type, nullability and default — the
 * whole of what `PRAGMA table_info` knows except the row number.
 */
internal fun SQLiteConnection.columnsOf(table: String): List<String> = buildList {
    prepare("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?)").use { s ->
        s.bindText(1, table)
        while (s.step()) {
            val default = if (s.isNull(3)) "-" else s.getText(3)
            add("${s.getText(0)} ${s.getText(1)} notnull=${s.getInt(2)} default=$default pk=${s.getInt(4)}")
        }
    }
}

/** The primary-key columns of [table], in key order. */
internal fun SQLiteConnection.primaryKeyOf(table: String): List<String> = buildList {
    val byPosition = sortedMapOf<Int, String>()
    prepare("SELECT name, pk FROM pragma_table_info(?)").use { s ->
        s.bindText(1, table)
        while (s.step()) {
            val position = s.getInt(1)
            if (position > 0) byPosition[position] = s.getText(0)
        }
    }
    addAll(byPosition.values)
}

/** Every foreign key of [table] as `child -> parent(column) ON DELETE action`, sorted. */
internal fun SQLiteConnection.foreignKeysOf(table: String): List<String> = buildList {
    prepare("SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list(?)").use { s ->
        s.bindText(1, table)
        while (s.step()) {
            add("${s.getText(1)} -> ${s.getText(0)}(${s.getText(2)}) ON DELETE ${s.getText(3)}")
        }
    }
}.sorted()

/** Every index on [table] as `name | sql`, sorted — so a hand-written name or order shows up. */
internal fun SQLiteConnection.indexDefinitionsOn(table: String): List<String> = buildList {
    prepare("SELECT name, COALESCE(sql,'(implicit)') FROM sqlite_master WHERE type='index' AND tbl_name=?")
        .use { s ->
            s.bindText(1, table)
            while (s.step()) add("${s.getText(0)} | ${s.getText(1)}")
        }
}.sorted()

/** Every column of one row of [table], as text, so a seeded row can be compared field for field. */
internal fun SQLiteConnection.rowOf(table: String, id: String, key: String = "id"): List<String> =
    buildList {
        prepare("SELECT * FROM $table WHERE $key = ?").use { s ->
            s.bindText(1, id)
            check(s.step()) { "$table has no row with $key = $id" }
            for (i in 0 until s.getColumnCount()) {
                add("${s.getColumnName(i)}=${if (s.isNull(i)) "NULL" else s.getText(i)}")
            }
        }
    }

/**
 * `app/src/main/...`, resolved the way [schemaFile] resolves the exported schemas: Gradle runs JVM
 * unit tests with the module directory as the working directory, but an IDE run configuration may
 * use the repository root, so both are tried. This is what lets a structural assertion be made
 * about the source itself — which is the only way to see a Room annotation, whose retention is
 * BINARY and therefore invisible to runtime reflection.
 */
internal fun mainSourceFile(relative: String): File =
    listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        .firstOrNull { it.isFile }
        ?: error("cannot find src/main/$relative from ${File(".").absolutePath}")

/** Every Kotlin file under `app/src/main`. */
internal fun mainSourceFiles(): List<File> {
    val root = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
        ?: error("cannot find src/main from ${File(".").absolutePath}")
    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}

private fun JsonObject.sql(key: String, table: String): String =
    getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

/**
 * `app/schemas/...`. Gradle runs JVM unit tests with the module directory as the working
 * directory, but an IDE run configuration may use the repository root, so both are tried.
 */
private fun schemaFile(version: Int): File {
    val relative = "schemas/com.loosecannon.servicetag.data.room.AppDatabase/$version.json"
    return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
        ?: error("cannot find the exported schema $relative from ${File(".").absolutePath}")
}

/** The five columns schema v8 appends to `asset`, in `MIGRATION_7_8`'s order. */
internal val V8_NEW_ASSET_COLUMNS = setOf(
    "season_mode",
    "blackout_start_mmdd",
    "blackout_end_mmdd",
    "health_aggregation",
    "health_primary_subject_id",
)

/** The three tables schema v8 added. */
internal val V8_TABLES = setOf("asset_season_activation", "asset_condition", "health_subject")

/** The seven tables schema v2 added. */
internal val JOURNAL_TABLES = setOf(
    "measurement_definition",
    "event_profile",
    "profile_field",
    "profile_consumable",
    "asset_event",
    "measurement",
    "consumable_usage",
)
