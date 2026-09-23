# B01 — Room schema 6 → 7, `asset_reference`, backup format 7, and the merge

**Read first:** the master plan's §1 (global constraints), §2 (schema), §3 (format 7), §4 (the
merge), §9 (invariants), §10 (file map), §18.1, §18.3 and §18.9. Those sections are the contract;
this brief is the work. **Spec:** `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`
§3.1, §3.2, §3.3, §3.4, §5, §12. **Report:**
`.superpowers/sdd/2026-09-23-servicetag-share-intake/B01-report.md`.

## Purpose

Land the data contract 1.3.0 needs, and nothing else: the `AssetReference` domain shape, one new
Room table with its DAO, mapper and repository, the `MIGRATION_6_7` that produces it without
touching a single existing row, the exported `7.json`, backup **format 7** (one new top-level list,
one new `counts` key, deterministic sorting, eager validation, the owner check) and the merge
extension (`MergeTable.REFERENCES` last, three new reason codes, the reference's second identity
under D-18 C, and the fourth tally on all three carriers). **No policy, no use case, no UI, no API
surface, no MCP.** Every brief after this one compiles against what this brief produces, which is
why its field sets and index names are asserted rather than assumed.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/model/AssetReference.kt` — `ReferenceKind` (three values) and the `AssetReference` data class. **Shapes only**: no scheme derivation, no inference, no validation — those are B02's.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/AssetReferenceEntity.kt` — the `@Entity`, in the style of `entities/AttachmentEntity.kt`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/AssetReferenceDao.kt`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/ReferenceMappers.kt` — entity ↔ domain, in the style of `data/room/MaintenanceMappers.kt`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/ReferenceRepositories.kt` — the `RoomReferenceRepository`, in the style of `data/room/MaintenanceRepositories.kt`. **Its own file**, so wave 3's lanes never collide with it.
- `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/7.json` (generated and committed).
- Tests: `app/src/test/kotlin/.../data/ReferenceMigrationTest.kt`, `app/src/test/kotlin/.../data/ReferenceDaoConstraintTest.kt`, `core/src/test/kotlin/.../backup/BackupFormat7Test.kt`, `core/src/test/kotlin/.../merge/MergePlannerReferenceTest.kt`.

**Modify**

- `core/.../core/model/Ids.kt` — `@JvmInline value class ReferenceId(val value: String)`, the tenth.
- `core/.../core/ports/Repositories.kt` — `ReferenceRepository` (see Interfaces).
- `core/.../core/backup/BackupFormat.kt` — `AssetReferenceDto`, `BackupData.assetReferences` with an `emptyList()` default and a KDoc in the shape of the format-6 lists' (`BackupFormat.kt:338`–`343`), and the `toDto()` / `toDomain()` pair.
- `core/.../core/backup/BackupCodec.kt` — `FORMAT_VERSION = 7`; the list in `encode`'s `sorted` block and in `counts`; the eager `toDomain()` validation line; `validateGraph`'s `uniqueIds` call and the owner check. The KDoc's `data.json` sketch at `:31` gains the list.
- `core/.../core/merge/MergePlan.kt` — `MergeTable.REFERENCES` **last**, its KDoc's "ten canonical tables" (`:18`) → eleven with one sentence saying why last is correct (a reference points only at an asset); the three new `MergeReason` members with KDoc in the shipped style; `MergeWrites.references`, `MergeSnapshot.references`, `MergeReport.references` **in the positions master plan §4 pins** — last in `MergeWrites`, last among `MergeReport`'s tallies, and in `MergeSnapshot` **after `attachments` and before `storedBytes`** with `= emptyList()`, because that class's field order is not the write order and `attachmentStoreConfigured` must stay last with no default; `MergePlan.report()`'s eleventh tally. **Two KDoc sentences in this file are now false and this brief fixes both** (master §18.9, comment text only, no member moves): `MergeVerdict`'s at `:40` ("`SKIPPED` is the plan declining to write a row it could otherwise have written — **in 1.1.0**, an attachment row whose bytes are not on this phone…") and `MergeReport`'s at `:257` ("neither does a `SKIPPED` attachment row"). Both must name the reference skip as the second case.
- `core/.../core/merge/MergePlanner.kt` — the references pass, last in write order, and the two id-independent lookup maps beside the closure pass's.
- `core/.../core/usecase/ApplyBackupMergePlan.kt` — the write loop in `MergeWrites` field order.
- `core/.../core/usecase/BuildBackupMergePlan.kt` — `references` read into the snapshot, **and its KDoc's "read the ten canonical tables" at `:27` → eleven**. That is the second live "ten canonical tables" in `core/src/main`, which is why this brief's gate greps the tree and not one file.
- `core/.../core/usecase/ImportBackupReplace.kt` — `references.deleteAll()` in the delete phase and the replay **after assets**, since a reference's only foreign key is `asset_id`.
- `core/.../core/usecase/ExportBackupSet.kt` — `assetReferences` read into `BackupData`.
- `app/.../data/room/AppDatabase.kt` — the entity in the list, `version = 7`, the DAO accessor.
- `app/.../data/room/Migrations.kt` — `MIGRATION_6_7`, with a KDoc in the shape of `MIGRATION_4_5`'s.
- `app/.../di/AppGraph.kt` — `SCHEMA_VERSION = 7`; `MIGRATION_6_7` appended to `addMigrations(...)`; `val references: ReferenceRepository`. **No use case is wired here by this brief** (B02's).
- `app/.../api/ApiDtos.kt` — **only** its `:142` comment ("Ten tables since format 6, not seven." → eleven, two lines above the field this brief adds), `MergeReportResponse.references: MergeTallyDto` and its line in `MergeReport.toResponse()`, both in write-order position (last). Master plan §18.3 explains why this one field is B01's and not B05's.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt` — **the schema and format assertions only, and this brief leaves the whole `:app` unit suite green**. Read that file first. Two edits: `theSchemaAndTheFormatAreBothSix` (`:60`) is renamed to name seven and asserts `AppGraph.SCHEMA_VERSION == 7`, `BackupCodec.FORMAT_VERSION == 7` and that the two agree; and in `statusEchoesTheVersionsTheBuildCarries`, `assertEquals(6, status.schemaVersion)` and `assertEquals(6, status.backupFormatVersion)` (`:124`–`:125`) become **7**. **Nothing else in that file moves** — `assertEquals("1.2.1", status.appVersion)` (`:123`), `theReleaseIdentityIs121AndCode14` (`:49`) and `versioningRecordsThisReleaseAndNoLongerReservesItsCode` are **B06's** and stay as they are, because at this brief's tip `versionName` is still **1.2.1**, `versionCode` still **14**, and `docs/versioning.md` carries **no 1.3.0 row**. `theRoomDatabaseCarriesTheSameVersionAsTheGraphConstant` (`:73`) and `theProductionHandlersAreWiredToTheBuildsOwnConstants` (`:133`) need **no source change**: the first derives everything from `SCHEMA_VERSION`, so it starts asserting `version = 7,` and `7.json` on its own and its passing is the proof that `7.json` was committed. See master plan §18.16.
- **The shipped tests whose pinned numbers necessarily move with schema 7, format 7 and the eleventh table.** Read each file and anchor the edit before touching it. **The allowed change is a rule, not a licence:** *a pinned format number, table count, `counts`-map size or table list moves to the new value — no assertion is weakened, no case is deleted or `@Ignore`d, no exact comparison becomes a `contains`, and every case must still **fail** if the value is wrong.* If a case cannot be updated under that rule, stop and raise it with the controller.
  - `core/src/test/.../backup/BackupFormat6Test.kt` — **keeps its name** (master §18.21): it proves a **format-6** archive decodes and restores, which stays true and stays wanted. Four pins move: the table list and its count (`:265`–`:269`, `tables.takeLast(3)` and `assertEquals(10, tables.size)` → the eleventh table and **11**); the `FORMAT_VERSION` pin at `:340` (`assertEquals(6, …)` → **7**); and the `counts` map with its size at `:355`–`:368` (the new `"assetReferences"` key and `assertEquals(16, manifest.counts.size)` → **17**). Its format-5 decode case (`:286`–`:304`) and its `BackupNewerFormat` case (`:329`–`:333`, written against `FORMAT_VERSION + 1`) need **no** edit and must keep passing.
  - `core/src/test/.../backup/BackupCodecTest.kt` — the two `counts` maps at `:215`–`:226` and `:438`–`:444` each gain `"assetReferences" to 0`. Everything keyed off `BackupCodec.FORMAT_VERSION` (`:211`, `:270`–`:276`, `:726`, `:814`) follows the constant and needs no edit; the format-1/2/3/4 fixtures must keep passing untouched.
  - `core/src/test/.../usecase/BackupUseCasesTest.kt` — the `counts` map at `:405`–`:416` gains the key. Its `FORMAT_VERSION`-relative cases need no edit.
  - `core/src/test/.../backup/StageABundleConformanceTest.kt` — a **named `FORMAT_7_TABLES` beside `FORMAT_6_TABLES`** (`:178`–`:179`), so the key-set guard at `:92` still subtracts an exact, named set and anything added later still fails it. `FORMAT_6_TABLES` is **not** renamed or widened in place: the two sets record two formats, which is what makes the guard readable. The fixture's own `assertEquals(5, backup.manifest.formatVersion)` (`:34`) is a **format-5** bundle and does not move.
  - `core/src/test/.../merge/MergePlannerMaintenanceTest.kt` — the `MergeTable.entries` list at `:256`–`:262` gains `MergeTable.REFERENCES` **last**, and the case's name (`:255`, "the ten tables…") is renamed to eleven. The conflict-ordering list at `:876`–`:891` follows if it enumerates tables.
  - `app/src/test/.../api/MaintenanceRoutesTest.kt` — `assertEquals(6, report.formatVersion)` at `:1008` → **7**.
- `app/src/test/kotlin/com/loosecannon/servicetag/testing/FakeGraph.kt` — **the `:app` test graph must not carry its own idea of the schema version.** `:298` declares `const val SCHEMA_VERSION = 6` in a private companion, injected at `:225`; that constant is silently decoupled from the one the release gate and `VersionAgreementTest` assert, so it would sit at 6 through this whole release and nothing would say so. **Preferred fix: delete the local constant and reference `AppGraph.SCHEMA_VERSION`** at the injection site, which makes the decoupling impossible. If that import is awkward from `testing/`, bump it to **7** *and* add a one-line case asserting `FakeGraph`'s value equals `AppGraph.SCHEMA_VERSION`, so the next bump cannot leave it behind. Either way the report says which was done and why.
- Existing tests that construct a `BackupData`, a `MergeSnapshot` or a `MergeReport` positionally: extend with the new field at its default and change **nothing else** about what they assert.

**Untouched:** `external_link`, `ExternalLink`, `LinkKind`, `LinkRepository`, `ExternalLinkDao`,
`ExternalLinkEntity` and the format-5 `externalLinks` field and its planner pass (I-5);
**`MergeVerdict`'s four members** — no member added, removed or reordered, **its KDoc excepted**
(master §18.9); every existing `MergeTable` member's position; `app/schemas/{1..6}.json`
(byte-identical); `ArtifactsCodec` and every artifacts tally; `Attachment.kt` in every particular;
`app/build.gradle.kts` (B06 owns the version); `docs/api/v1.md` (B05); every `ui/**`, `share/**`,
`api/**` file but the two edits above; `tools/`. **And, inside the shipped tests this brief repins:**
every case not named above, `BackupFormat6Test`'s **name** (§18.21), `FORMAT_6_TABLES`' membership, and
the format-1 through format-5 fixtures, which must all keep passing untouched.

## Interfaces

**Produces, for B02, B03, B04 and B05.**

```kotlin
// core/.../core/model/AssetReference.kt
enum class ReferenceKind { WEB_URL, NOTE_LINK, OTHER }

data class AssetReference(
    val id: ReferenceId,
    val assetId: AssetId,
    val kind: ReferenceKind,
    val uri: String,
    val displayName: String,
    val description: String,
    val scheme: String,
    val createdAt: Long,
    val updatedAt: Long,
)
```

```kotlin
// core/.../core/ports/Repositories.kt
interface ReferenceRepository {
    suspend fun upsert(reference: AssetReference)
    suspend fun get(id: ReferenceId): AssetReference?
    suspend fun forAsset(assetId: AssetId): List<AssetReference>   // ordered by displayName, then id
    suspend fun findByUri(assetId: AssetId, uri: String): AssetReference?
    suspend fun all(): List<AssetReference>
    suspend fun delete(id: ReferenceId)
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<AssetReference>>
}
```

`findByUri` exists so B02's duplicate refusal is a keyed read and not a scan of `forAsset`, and so
the `UNIQUE(asset_id, uri)` index is the thing that answers it.

`AssetReferenceDto` — `@Serializable`, nine fields, **exactly this order**: `id, assetId, kind, uri, displayName, description, scheme, createdAt, updatedAt`; every field a `String` but `createdAt` and
`updatedAt`, which are `Long`. **No `provenance`, ever** (D-21 C).

`AssetReferenceDao` — `@Dao` interface with: upsert one; get by id; list for an asset ordered by
`display_name COLLATE NOCASE, id`; find by `(asset_id, uri)`; list all ordered by `id`; delete by
id; delete all; observe for an asset.

`MergeTable`, `MergeReason`, `MergeWrites`, `MergeSnapshot`, `MergeReport` in their extended shapes.
The three new reasons, with KDoc in the shipped style:

- `REFERENCE_DUPLICATED_IN_ARCHIVE` — two rows of one archive claim one `(asset_id, uri)`.
- `REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` — rides on an `IDENTICAL`; a local row under a different id already **is** this reference, field for field. Its KDoc must say that equality includes `createdAt` and `updatedAt`, which spec §5 itself says two phones "agree about never", so **this arm is reachable in practice only from a copied or replayed archive** — a reviewer must not read the matrix row for it as a live two-phone case. The live two-phone case is the arm below.
- `REFERENCE_HELD_BY_A_LOCAL_ROW` — rides on a **`SKIPPED`**; a local row under a different id holds the pair and differs. Its KDoc must state D-18 C's reasoning (master plan §4) so the next reader cannot mistake it for an oversight.

**Consumes:** nothing from another brief in this package. It extends shipped code only.

## Invariants this brief must hold

**I-3** (a reference has no bytes: no locator, no sha256, no artifacts entry, and it is never handed
to `AttachmentStore` — the proof is that `ArtifactsCodec` and the `managed` filter in
`BackupCodec.encode` do not move); **I-4** (a reference is never a tag target — the proof is that
`TagTarget`, `TagTargets` and `requireTargetExists` are untouched); **I-5** (the 2.6 tombstones);
**I-7** (`UNIQUE(asset_id, uri)`, and the same URI on two assets is ordinary).

It must not make **I-1**, **I-2** or **I-6** unholdable: nothing this brief writes may offer a way
to change a stored `uri` or an `asset_id` — the DAO has no `@Query` that updates either column, and
`upsert` is the only write.

## Test matrix

One test per hazard class. Each must fail without the change it names.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| a new field silently dropped in transit | every field of `AssetReferenceDto` survives `encode` → `decode` with non-default values, including a URI carrying a query and a fragment | a field omitted from the DTO or the mapper comes back at its default and the assertion on the round-tripped value fails |
| DTO field-set drift | the serializer descriptor's element names equal the nine names of Interfaces, in that order, and `"provenance"` is absent | a field added later, misnamed or reordered changes the descriptor and the comparison fails |
| an old archive stops working | a hand-built **format-6** archive decodes and restores with `assetReferences` empty, and every other count unchanged | a list declared without a default makes the decode throw |
| a newer archive read by an older build | a format-7 manifest is refused with `BackupNewerFormat` before any row is read, asserted against the shipped gate with `FORMAT_VERSION` held at 6 in the test's expectation | bumping `FORMAT_VERSION` without the gate lets a 1.2.x build read rows it does not understand |
| counts drift from rows | the manifest's `counts` has exactly **17** keys and `assetReferences` equals the row count | a missing key, or a count taken from the wrong list, gives the wrong number |
| non-deterministic bytes | encoding the same `BackupData` with `assetReferences` shuffled produces identical bytes, and the rows come out in `id` order | an unsorted list makes the two byte arrays differ |
| a duplicate id in an archive | two reference rows with one `id` are refused by `uniqueIds("assetReferences", …)` with that table name in the message | a missing `uniqueIds` call lets the duplicate through to a foreign-key failure at apply time |
| an orphan reference in an archive | a row whose `assetId` is absent from the archive's own `assets` is a `BackupCorrupt` naming the table and both ids | without the owner check a replace-import fails halfway, with the user's data already gone |
| in-archive pair uniqueness checked too early | two reference rows with the **same** `(assetId, uri)` and different ids **decode successfully** | a `(assetId, uri)` uniqueness check at decode makes `REFERENCE_DUPLICATED_IN_ARCHIVE` unreachable — spec §5 requires it reachable |
| **a replace-import drops or misorders references** | export an archive holding references on two assets, **replace-import** it into a store that already holds other references, and read back: the archive's reference rows are present **field-for-field**, the pre-existing ones are gone (`ReferenceRepository.deleteAll()` ran), and no row was replayed before its asset | a replace path that forgets `deleteAll()` leaves stale rows the unique index then refuses; one that replays references before assets fails on the foreign key with the user's data already deleted. The format-6 row below proves only the *empty*-list case, so without this row `ImportBackupReplace.kt`'s change is untested |
| the migration destroys data | a v6 database seeded with an asset, a definition, a profile, an event with measurements, a tag, an attachment, a group, a schedule and a closure migrates to v7 and **every seeded row reads back field-for-field identical**; the database then validates for a v7 `AppDatabase` open | a recreate that drops or reorders a column fails the row comparison; a schema that does not match `7.json` fails the open |
| the migration produces a different schema than a fresh install | the migrated database's columns, primary key, foreign key and **index names** for `asset_reference` equal a fresh v7 install's | a hand-written index name, or a `REFERENCES` clause the `CREATE` forgot, differs |
| the unique constraint is only a convention | inserting a second row with the same `(asset_id, uri)` fails at the database; the same `uri` on **two different assets** both succeed (I-7) | without the unique index the second insert succeeds; with it declared on the wrong columns the two-assets case fails |
| deleting an asset orphans its references | deleting the asset row removes its reference rows by cascade, and removes nothing belonging to another asset | `ON DELETE` left at the Room default leaves an orphan and the next foreign-key check fails |
| merge order breaks a reference | the eleven `MergeTable` members are in master plan §4's order, asserted as a list; an archive whose asset and reference arrive together writes the asset first | a member inserted anywhere but last makes a reference decided before its asset and the `OWNER_NOT_AVAILABLE` assertion inverts |
| a reference is not planned at all | an archive with a new reference plans `INSERT` and the row appears in `MergeWrites.references` | a planner pass that was never added silently leaves the table out of every report |
| an unchanged re-import stops being a no-op | an archive re-imported into the phone that produced it plans **`IDENTICAL`** for every reference row | a mapper that rewrites `updated_at` or re-derives `scheme` on read makes the row differ and the verdict becomes `CONTENT_DIFFERS` |
| a locally edited reference is silently overwritten | a local row with the **same id** and a different `description` plans `CONFLICT / CONTENT_DIFFERS`, and the plan's `writes` are **empty in every field** | a `MergeVerdict.UPDATE` or a partial write set makes the emptiness assertion fail |
| the equivalent second identity becomes an insert | a local row under a **different** id, field-for-field equal under `dto.copy(id = samePair.id)` — the shipped closure pass's spelling at `MergePlanner.kt:387`, where `local` is the *same-id* row and `samePair` the second-identity one — plans `IDENTICAL / REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` | matching on the row id alone makes this an `INSERT` that the unique index then refuses at apply time |
| **the diverged second identity blocks the archive (D-18 C)** | a local row under a different id holding the same `(assetId, uri)` with a **different `displayName`** plans **`SKIPPED / REFERENCE_HELD_BY_A_LOCAL_ROW`**, nothing is written for it, **`applicable` stays true**, and the apply succeeds leaving the local row unchanged | raising a `CONFLICT` there empties `MergeWrites` and refuses the whole archive — the exact failure D-18 C exists to prevent |
| the same-id arm stops blocking | the same-id/different-content case is still `CONFLICT / CONTENT_DIFFERS` and `applicable` is false | folding both arms into the skip makes references incapable of ever blocking, which spec §5 explicitly does not want |
| an archive disagreeing with itself | two archive rows claiming one `(assetId, uri)` plan `CONFLICT / REFERENCE_DUPLICATED_IN_ARCHIVE`, the second naming the first's id in `detail` | without the claimed-pairs map both insert and the unique index refuses one at apply time |
| an orphan survives the decoder | a reference whose `assetId` is neither local nor being inserted plans `CONFLICT / OWNER_NOT_AVAILABLE` | without the reference pass the apply fails on a foreign key with rows already written |
| the report loses a table | `MergeReport` carries eleven tallies and `MergeReportResponse` serialises `references` in last position | a tally added to the domain type and not to the wire mirror is silently dropped (master plan §18.3) |
| a skipped row is written anyway | after a plan holding one `SKIPPED` reference, `MergeWrites.references` does not contain it and the destination row count is unchanged after the apply | a pass that adds to the write list before deciding writes the row the plan declined |
| deterministic reports | two plans over the same archive and snapshot produce the same `decisions` order and the same `fingerprint` | an unordered map iteration in the new pass makes the fingerprint unstable |

## Gate

- `./gradlew :core:test --console=plain` — green, with `BackupFormat7Test` and `MergePlannerReferenceTest` present and counted.
- `./gradlew :app:testDebugUnitTest --console=plain` — **GREEN, the whole suite, zero failures**, with `ReferenceMigrationTest` and `ReferenceDaoConstraintTest` present and counted. **There is no "red until B06" allowance**: this brief owns `VersionAgreementTest`'s schema and format assertions and updates them in the same commit, so the tip it hands back is green. It gets there **without** touching `app/build.gradle.kts` or `docs/versioning.md`: `versionName` stays **1.2.1**, `versionCode` stays **14**, and no 1.3.0 row is added — those are B06's and the suite still asserts them at their current values.
- `grep -rl 'ten canonical tables' core/src/main` → **no output**. **Tree-wide, not one file:** there are two live occurrences today (`MergePlan.kt:18` and `BuildBackupMergePlan.kt:27`) and a single-file grep would leave the second stale. **`-rl`, not `-rc`**, which prints a count line for every file it searches (~90) and so cannot be read as a pass/fail.
- `grep -c 'Ten tables since format 6' app/src/main/kotlin/com/loosecannon/servicetag/api/ApiDtos.kt` → **0**.
- `grep -rn 'in 1.1.0, an' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → no output (the `MergeVerdict` KDoc sentence of master §18.9 rewritten, not merely appended to).
- **No `provenance` column, field or argument** (D-21 C) — two patterns, both of which return **0 today**: `grep -rn '"provenance"' core/src/main app/src/main` (a serialized field name, a JSON key, a SQL column inside a migration string) and `grep -rnE '\bprovenance\s*[:=]' core/src/main app/src/main` (a Kotlin property, a named argument, an assignment). **Not the bare word**: seven shipped prose and comment lines contain it — in `SeedTemplates.kt`, `Journal.kt`, `ContentHash.kt`, `MainActivity.kt`, `JournalEntities.kt`, the MCP server and `v1.md` — every one of them in a file this brief or §1.14 forbids touching, so a bare-word grep could never pass and would invite an unowned reword. **Leave those seven lines alone.** The assertion that actually carries D-21 C is the DTO descriptor row in the matrix above.
- `git diff --stat $BASE..HEAD -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/6.json` → empty. **`$BASE` is the lane's base commit**, recorded by the controller at dispatch (master §1.15).
- Report the per-module test deltas (`:core` before/after, `:app` before/after), **the list of shipped
  test files repinned and the value moved in each**, and which `FakeGraph` option was taken and why.

## This brief must NOT

Write a scheme, validate a URI, infer a kind, cap a length, refuse a blank name, or launch anything
— all B02's. Touch `AttachmentProblem`, `AttachmentKinds`, `AttachmentLocator`, `AttachmentStore` or
the artifacts archive. Add a route, a DTO request type, a `counts` key to `/v1/status`, or an MCP
tool. Add a `provenance` column or field. Touch any tombstone. Change `app/build.gradle.kts`,
`docs/versioning.md`, `docs/api/v1.md` or `README.md`. Add or move a `MergeVerdict` member. Move an
existing `MergeTable` member. Declare a SQL `CHECK`. Introduce any user-visible string.
