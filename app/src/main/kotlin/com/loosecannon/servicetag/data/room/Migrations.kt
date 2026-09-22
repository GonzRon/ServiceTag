package com.loosecannon.servicetag.data.room

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema v1 -> v2: the journal (spec 8). `asset` gains `template_key`, and the seven journal
 * tables arrive with the keys and indexes Room expects for them.
 *
 * Every statement below is the SQL Room itself generates for these entities, copied verbatim from
 * the exported `2.json` with `${'$'}{TABLE_NAME}` substituted. That is deliberate: Room validates the
 * result of a migration against the compiled schema and refuses to open a database whose tables
 * do not match byte for byte, so the migration and the schema have one source, not two. Tables
 * are created parents-first so the foreign keys resolve as they are declared.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `asset` ADD COLUMN `template_key` TEXT",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `measurement_definition` (`id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `key` TEXT NOT NULL, `label` TEXT NOT NULL, `unit` TEXT NOT NULL, `value_type` TEXT NOT NULL, `decimals` INTEGER NOT NULL, `range_low` REAL, `range_high` REAL, `is_meter` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_definition_asset_id` ON `measurement_definition` (`asset_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_measurement_definition_asset_id_key` ON `measurement_definition` (`asset_id`, `key`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_profile` (`id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `name` TEXT NOT NULL, `event_kind` TEXT NOT NULL, `default_title` TEXT NOT NULL, `template_key` TEXT, `sort_order` INTEGER NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_event_profile_asset_id` ON `event_profile` (`asset_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_field` (`id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `definition_id` TEXT NOT NULL, `required` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`profile_id`) REFERENCES `event_profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`definition_id`) REFERENCES `measurement_definition`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_field_profile_id_definition_id` ON `profile_field` (`profile_id`, `definition_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_profile_field_definition_id` ON `profile_field` (`definition_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_consumable` (`id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `name` TEXT NOT NULL, `default_quantity` REAL, `unit` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`profile_id`) REFERENCES `event_profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_profile_consumable_profile_id` ON `profile_consumable` (`profile_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `asset_event` (`id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `profile_id` TEXT, `occurred_on` TEXT NOT NULL, `occurred_time` TEXT, `tz_id` TEXT NOT NULL, `notes` TEXT NOT NULL, `source` TEXT NOT NULL, `source_ref` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`profile_id`) REFERENCES `event_profile`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_event_asset_id_occurred_on_created_at` ON `asset_event` (`asset_id` ASC, `occurred_on` DESC, `created_at` DESC)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_event_source_source_ref` ON `asset_event` (`source`, `source_ref`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_event_profile_id` ON `asset_event` (`profile_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `measurement` (`id` TEXT NOT NULL, `event_id` TEXT NOT NULL, `definition_id` TEXT NOT NULL, `value_num` REAL, `value_text` TEXT, `unit` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`event_id`) REFERENCES `asset_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`definition_id`) REFERENCES `measurement_definition`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_definition_id_event_id` ON `measurement` (`definition_id`, `event_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_event_id` ON `measurement` (`event_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `consumable_usage` (`id` TEXT NOT NULL, `event_id` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`event_id`) REFERENCES `asset_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_consumable_usage_event_id` ON `consumable_usage` (`event_id`)",
        )
    }
}

/**
 * Schema v2 -> v3: the DERIVED definition (spec §7). `measurement_definition` gains `kind`,
 * `formula`, `source_a_id` and `source_b_id`, the last two foreign keys into the table itself with
 * ON DELETE RESTRICT.
 *
 * SQLite cannot add a column that carries a foreign key, so the table is recreated the way Room
 * recreates one: build `_new_measurement_definition` from `3.json`'s createSql, copy every v2 row
 * into it with `kind = 'ENTERED'` (every definition that existed before this phase is entered by
 * hand — there was no other kind), drop the old table, rename the new one into its place, and
 * create the four indexes `3.json` declares — the two the table already had, which the DROP took
 * with it, plus one for each new source column.
 *
 * Two details make that safe, and neither is incidental:
 *
 *  - Room turns `PRAGMA foreign_keys` **off** for the duration of `migrate` (and runs
 *    `foreign_key_check` afterwards), so dropping `measurement_definition` while `measurement`,
 *    `profile_field` and the new table still name it in their REFERENCES clauses is not an error.
 *    With foreign keys off SQLite also leaves those clauses alone across the RENAME, so they keep
 *    referring to the table *by name* — and the name comes back, attached to the new table, the
 *    moment the rename lands. `Migration1To3Test` runs the whole chain and proves it.
 *  - The new table's own `source_a_id` / `source_b_id` clauses say `measurement_definition`, not
 *    `_new_measurement_definition`, exactly as `3.json` spells them: before the rename they point
 *    at the table being replaced (which nothing inserts into), after it they point at themselves.
 *
 * As everywhere in this file, the SQL is copied verbatim from the exported schema so the migration
 * and the compiled entities have one source; Room validates the result on open.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_measurement_definition` (`id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `key` TEXT NOT NULL, `label` TEXT NOT NULL, `unit` TEXT NOT NULL, `value_type` TEXT NOT NULL, `decimals` INTEGER NOT NULL, `range_low` REAL, `range_high` REAL, `is_meter` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `formula` TEXT, `source_a_id` TEXT, `source_b_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`source_a_id`) REFERENCES `measurement_definition`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`source_b_id`) REFERENCES `measurement_definition`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        connection.execSQL(
            "INSERT INTO `_new_measurement_definition` (`id`, `asset_id`, `key`, `label`, `unit`, " +
                "`value_type`, `decimals`, `range_low`, `range_high`, `is_meter`, `sort_order`, " +
                "`archived_at`, `created_at`, `updated_at`, `kind`) " +
                "SELECT `id`, `asset_id`, `key`, `label`, `unit`, `value_type`, `decimals`, " +
                "`range_low`, `range_high`, `is_meter`, `sort_order`, `archived_at`, `created_at`, " +
                "`updated_at`, 'ENTERED' FROM `measurement_definition`",
        )
        connection.execSQL("DROP TABLE `measurement_definition`")
        connection.execSQL(
            "ALTER TABLE `_new_measurement_definition` RENAME TO `measurement_definition`",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_definition_asset_id` ON `measurement_definition` (`asset_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_measurement_definition_asset_id_key` ON `measurement_definition` (`asset_id`, `key`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_definition_source_a_id` ON `measurement_definition` (`source_a_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_measurement_definition_source_b_id` ON `measurement_definition` (`source_b_id`)",
        )
    }
}

/**
 * Schema v3 -> v4: the asset record (spec §4, §5). `asset` gains the identification, money,
 * warranty and season columns, `retired_on`, and `parent_asset_id` — a self-referencing foreign
 * key into `asset(id)` with ON DELETE RESTRICT, plus its index.
 *
 * SQLite cannot add a column carrying a foreign key, so `asset` is recreated exactly as
 * `MIGRATION_2_3` recreated `measurement_definition`: build `_new_asset` from `4.json`'s
 * createSql, copy every v3 row into it, drop the old table, rename the new one into its place,
 * and create the three indexes `4.json` declares — the two `asset` already had, which the DROP
 * took with it, plus one for `parent_asset_id`.
 *
 * The copy fills the new columns itself rather than leaning on defaults, because there are none:
 * the six new TEXT columns are NOT NULL with no DEFAULT (they are non-null Kotlin `String`s), so
 * the INSERT supplies `''` for each. Everything else is nullable and is simply left out.
 *
 * `status` is rewritten through `CASE status WHEN 'RETIRED' THEN 'ARCHIVED' ELSE status END`.
 * That is defensive, not corrective: `AssetStatus.RETIRED` existed in the enum but no code path
 * ever wrote it, so no shipped install should hold one. If some hand-edited database does, this
 * turns it into a value v4's enum still knows instead of a row that throws in `valueOf` forever
 * after. Retirement is `retired_on` from here on (§7).
 *
 * The same two details that made the 2B-1 recreate safe hold here, and they matter more because
 * `asset` is the table everything else hangs off:
 *
 *  - Room turns `PRAGMA foreign_keys` **off** for the duration of `migrate` (and runs
 *    `foreign_key_check` afterwards), so dropping `asset` while `nfc_tag`, `external_link`,
 *    `measurement_definition`, `event_profile` and `asset_event` all name it in their REFERENCES
 *    clauses is not an error. With foreign keys off SQLite leaves those clauses alone across the
 *    RENAME, so they keep referring to the table *by name* — and the name comes back, attached to
 *    the new table, the moment the rename lands. `Migration1To4Test` runs the whole chain and
 *    proves it, including that the rows on the far side still resolve.
 *  - The new table's own `parent_asset_id` clause says `asset`, not `_new_asset`, exactly as
 *    `4.json` spells it: before the rename it points at the table being replaced (which nothing
 *    inserts a parent into), after it, at itself.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_asset` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `template_key` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `manufacturer` TEXT NOT NULL, `model` TEXT NOT NULL, `serial_number` TEXT NOT NULL, `purchase_on` TEXT, `in_service_on` TEXT, `purchase_price_minor` INTEGER, `currency` TEXT, `vendor` TEXT NOT NULL, `location` TEXT NOT NULL, `warranty_expires_on` TEXT, `warranty_notes` TEXT NOT NULL, `retired_on` TEXT, `parent_asset_id` TEXT, `season_start_mmdd` TEXT, `season_end_mmdd` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`parent_asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        connection.execSQL(
            "INSERT INTO `_new_asset` (`id`, `name`, `description`, `category`, `notes`, `status`, " +
                "`template_key`, `created_at`, `updated_at`, `manufacturer`, `model`, " +
                "`serial_number`, `vendor`, `location`, `warranty_notes`) " +
                "SELECT `id`, `name`, `description`, `category`, `notes`, " +
                "CASE `status` WHEN 'RETIRED' THEN 'ARCHIVED' ELSE `status` END, " +
                "`template_key`, `created_at`, `updated_at`, '', '', '', '', '', '' FROM `asset`",
        )
        connection.execSQL("DROP TABLE `asset`")
        connection.execSQL("ALTER TABLE `_new_asset` RENAME TO `asset`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_status` ON `asset` (`status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_name` ON `asset` (`name`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_parent_asset_id` ON `asset` (`parent_asset_id`)",
        )
    }
}

/**
 * Schema v4 -> v5: the `attachment` table (spec §9.1). Nothing existing changes, so this is a
 * plain `CREATE TABLE` plus its three indexes — no recreate, no copy, no rewrite. Every row that
 * was on disk before the migration is untouched by construction.
 *
 * As everywhere in this file the SQL is copied verbatim from the exported `5.json`, so the
 * migration and the compiled entity have one source and Room validates the result on open.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachment` (`id` TEXT NOT NULL, `asset_id` TEXT, " +
                "`event_id` TEXT, `kind` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                "`display_name` TEXT NOT NULL, `mime_type` TEXT NOT NULL, " +
                "`size_bytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, " +
                "`storage_provider` TEXT NOT NULL, `storage_locator` TEXT NOT NULL, " +
                "`captured_on` TEXT, `notes` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`event_id`) REFERENCES `asset_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachment_asset_id` ON `attachment` (`asset_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachment_event_id` ON `attachment` (`event_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_attachment_storage_provider_storage_locator` ON `attachment` " +
                "(`storage_provider`, `storage_locator`)",
        )
    }
}

/**
 * Schema v5 -> v6: the maintenance tables (spec §3.1). Seven `CREATE TABLE`s with their indices,
 * and then `asset_event` gains three columns and two indices.
 *
 * As everywhere in this file the SQL is copied verbatim from the exported `6.json`, so the
 * migration and the compiled entities have one source and Room validates the result on open.
 *
 * **Why `ALTER TABLE ADD COLUMN` and not a 12-step recreate.** `asset_event` is altered rather than
 * only added to, so two implementations were available: three `ADD COLUMN`s plus two
 * `CREATE INDEX`es, or building a `_new_asset_event`, copying every row into it, dropping the old
 * table and renaming — the shape `MIGRATION_2_3` and `MIGRATION_3_4` had to use. The additive one is
 * used here because it satisfies both requirements outright:
 *
 *  - SQLite **can** add a column carrying a `REFERENCES` clause as long as its default is NULL, and
 *    `schedule_id` is nullable with no default, so the foreign key is registered on the altered
 *    table exactly as a fresh install declares it — which is the thing a recreate would have been
 *    needed for, and is asserted against a fresh v6 database by `MaintenanceMigrationTest`;
 *  - and it copies no rows at all, so "every pre-existing row is byte-identical" holds by
 *    construction rather than by a correct column list in an `INSERT ... SELECT`, which is where a
 *    recreate can silently drop or reorder a value.
 *
 * The new tables are created **parents-first**: `maintenance_group` before `maintenance_schedule`,
 * which references it, and both before `asset_event`'s new `schedule_id`, whose `REFERENCES` clause
 * names a table that has to exist by then.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `maintenance_group` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_group_name` ON `maintenance_group` (`name`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `maintenance_group_member` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, `removed_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`group_id`) REFERENCES `maintenance_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_maintenance_group_member_group_id_asset_id_added_at` ON `maintenance_group_member` (`group_id`, `asset_id`, `added_at`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_group_member_group_id` ON `maintenance_group_member` (`group_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_group_member_asset_id` ON `maintenance_group_member` (`asset_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `maintenance_schedule` (`id` TEXT NOT NULL, `asset_id` TEXT, `group_id` TEXT, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `time_interval` INTEGER, `time_unit` TEXT, `time_basis` TEXT NOT NULL, `anchor_on` TEXT, `lead_days` INTEGER NOT NULL, `meter_definition_id` TEXT, `meter_interval` REAL, `anchor_meter` REAL, `meter_lead` REAL, `season_behavior` TEXT NOT NULL, `season_reentry` TEXT, `season_reentry_offset_days` INTEGER, `completion_mode` TEXT NOT NULL, `profile_id` TEXT, `reminders_enabled` INTEGER NOT NULL, `status` TEXT NOT NULL, `postponed_due_on` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`group_id`) REFERENCES `maintenance_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`meter_definition_id`) REFERENCES `measurement_definition`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`profile_id`) REFERENCES `event_profile`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_schedule_asset_id_status` ON `maintenance_schedule` (`asset_id`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_schedule_group_id_status` ON `maintenance_schedule` (`group_id`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_schedule_meter_definition_id` ON `maintenance_schedule` (`meter_definition_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_maintenance_schedule_profile_id` ON `maintenance_schedule` (`profile_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_provider` (`schedule_id` TEXT NOT NULL, `provider` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`schedule_id`, `provider`), FOREIGN KEY(`schedule_id`) REFERENCES `maintenance_schedule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `occurrence_closure` (`id` TEXT NOT NULL, `schedule_id` TEXT NOT NULL, `occurrence_on` TEXT NOT NULL, `closed_on` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`schedule_id`) REFERENCES `maintenance_schedule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_occurrence_closure_schedule_id_occurrence_on` ON `occurrence_closure` (`schedule_id`, `occurrence_on`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_occurrence_closure_schedule_id` ON `occurrence_closure` (`schedule_id`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_state` (`schedule_id` TEXT NOT NULL, `last_completed_on` TEXT, `last_completion_event_id` TEXT, `last_completed_meter` REAL, `current_meter` REAL, `computed_due_meter` REAL, `last_termination_effective_on` TEXT, `last_termination_kind` TEXT NOT NULL, `computed_due_on` TEXT, `effective_due_on` TEXT, `season_active` INTEGER NOT NULL, `computed_for_on` TEXT NOT NULL, `computed_at` INTEGER NOT NULL, PRIMARY KEY(`schedule_id`), FOREIGN KEY(`schedule_id`) REFERENCES `maintenance_schedule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_schedule_state_effective_due_on` ON `schedule_state` (`effective_due_on`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_local_delivery` (`schedule_id` TEXT NOT NULL, `snoozed_until_at` INTEGER, `last_notified_at` INTEGER, `first_entry_seen` INTEGER NOT NULL, `action_nonce` TEXT, `nonce_issued_at` INTEGER, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`schedule_id`), FOREIGN KEY(`schedule_id`) REFERENCES `maintenance_schedule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

        // The altered table. Each column's definition is the one `6.json` spells for it, so the
        // altered table and a fresh one describe the same column — nullability, default and the
        // REFERENCES clause included.
        connection.execSQL(
            "ALTER TABLE `asset_event` ADD COLUMN `schedule_id` TEXT REFERENCES `maintenance_schedule`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL",
        )
        connection.execSQL("ALTER TABLE `asset_event` ADD COLUMN `occurrence_on` TEXT")
        connection.execSQL(
            "ALTER TABLE `asset_event` ADD COLUMN `details_pending` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_event_schedule_id_occurrence_on_asset_id` ON `asset_event` (`schedule_id`, `occurrence_on`, `asset_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_event_schedule_id_occurred_on` ON `asset_event` (`schedule_id` ASC, `occurred_on` DESC)",
        )
    }
}
