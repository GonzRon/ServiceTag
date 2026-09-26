# #67 — key-document roles and Asset-editor document intake: audit, plan and briefs (rev 2.1, reviewed 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review
> budget in `docs/superpowers/planning-policy.md`. Three briefs (§9 Task 1, §10 Task 2a, §11 Task 2b),
> sequential, one implementer at a time, one task review each, one batched fix round each, at most one
> scoped re-review each, one whole-branch review. Planned read-only from issue #67 under the owner's GO
> of 2026-09-26, on master 5c36286b. Rev 2 folds in the independent brief review (REJECT on rev 1: a new
> asset has no id until the settings save mints it, so rev 1's retry would have created a duplicate asset;
> the retry path could be refused silently and re-ask the #78 question; a default `role = null` on the
> update command would clear roles on every rename; the migration test and the production migration
> wiring were mis-placed; "disabled" buttons contradicted spec §8.1; a role difference blocks a whole
> merge and needed a ruling). Rev 2.1 closes the scoped re-review's residuals by controller inspection (the #78
> question decided from the pre-write mode; the update command's construction site is the sheet; `writtenForm`
> defined; the pickers' resolver; the docs lines; two owner-visible choices under R67-8; option C's cost).
> Nothing is dispatched until every string in §6 is ratified and every ruling
> in §7 is answered.

**Goal:** an owner can attach the purchase invoice or receipt, the user manual and the service manual
while creating or editing an Asset without leaving the editor; those documents carry a canonical **role**
that survives backup, restore and merge; the Asset detail shows role-tagged documents in a delineated
**Key documents** area while the ordinary Documents list keeps showing them; a role is metadata on the one
existing attachment row — no second row, no copied bytes; cancelling a new asset creates neither an asset
nor an orphan attachment.

**Spec:** issue #67 (AC 1–7); the attachments design spec `docs/superpowers/specs/2026-09-15-phase-4a-attachments-design.md`
(§4 model — kind is "a default the person may override; never a constraint", §5 store, §6 use cases,
§7.1 archive checks, §8.1 DOCUMENTS section — with no store "both actions instead show a StatusBlock …
with a button that pushes Route.Settings", §11 rulings); the share-intake spec
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` (§3.3 invariants I-3/I-8/I-9 and §4 security
stay untouched — AC 7; its §7 composition and §10 string table gain the Role section as an amendment);
the #74 plan's format rules (`FIRST_CATEGORY_FORMAT` check after the strict decode; `LAST_LEGACY_FORMAT`
stays 7; a pre-upgrade export still re-plans IDENTICAL); the schema-9 release gate in `docs/release-proofs.md:83`.

## Global constraints

- **One row, one set of bytes.** A role is a nullable field on the existing `Attachment`; featuring,
  reclassifying or un-tagging a document is a metadata update that never calls `AttachmentStore.put`.
- **Canonical and portable.** The role lives in Room (schema 10), the data archive (format 10) and the
  merge comparison under R67-12; `LAST_LEGACY_FORMAT` stays 7; a format ≤ 9 archive carrying a non-null
  role is refused.
- **No orphans, no duplicates.** A file picked for an unsaved asset is not copied and no row is written
  until the asset's own settings write has succeeded; once written, that asset's id is the only id every
  later Save addresses; cancel, back, a refusal and process death before Save leave nothing.
- **#7 and #43 untouched** (AC 7): the store, the SAF tree, the locator shape, the sweep, the intake's
  lift, scheme policy and security rules.
- **Strings.** Every visible or accessibility string is in §6, verbatim, ratified before dispatch; the
  existing attachment sentences are reused through one extracted mapping, never re-spelled.
- Tests JVM-first, Compose instrumented on `emulator-5554` only, one class per invocation. Hygiene,
  gitlink `7e0377a`, one-line commits, the tombstone rule, no personal data.

## 1. Audit (controller, read-only, 2026-09-26; corrected by the review)

**Model and ports.** `core/model/Attachment.kt`: `Attachment(id, owner: OfAsset|OfEvent, kind:
AttachmentKind {PHOTO, LABEL_PHOTO, RECEIPT, MANUAL, WARRANTY, DOCUMENT, OTHER}, mode, displayName,
mimeType, sizeBytes, sha256, storageProvider, storageLocator, capturedOn, notes, createdAt, updatedAt)`
— a plain data class, no `init` rules anywhere in `core/model`; `AttachmentLocator` fixes the on-disk
shape; `MAX_ATTACHMENT_BYTES` 256 MiB. `AttachmentRepository` (`upsert/get/forOwner/forAsset/all/delete/
deleteAll/count/observeForOwner`). `AttachmentStore.put` is the only place bytes are written and hashed.
`AddAttachment.run(owner, AddAttachmentCommand(displayName, mimeType, sizeBytes?, kind?, capturedOn?,
notes, fromCamera), source)` refuses `BlankName`, `NoStore`/`StoreUnavailable` (from `state()`/`store()`
before any copy), `OwnerMissing`, `TooLarge`; it copies with `put` first and builds the row after
(:66-86); `kind` is inferred when null (`AttachmentKinds.inferFrom`, "only a default"). `UpdateAttachment.run(id,
UpdateAttachmentCommand(displayName, kind, capturedOn?, notes))` copies every command field onto the row
(:28-34). `AttachmentSweep` deletes bytes best-effort after a row delete.

**Data.** Room `AppDatabase` version 9, `exportSchema` (`app/schemas/…/9.json`); the `attachment` table
(`asset_id`, `event_id`, `kind`, `mode`, `display_name`, `mime_type`, `size_bytes`, `sha256`,
`storage_provider`, `storage_locator`, `captured_on`, `notes`, timestamps; unique `(storage_provider,
storage_locator)`); entity↔domain mapping in `data/room/Mappers.kt` (:188). Migrations are hand-written
Kotlin on `SQLiteConnection` (`MIGRATION_8_9`); the production migration list is `di/AppGraph.kt:190-193`
with no destructive fallback; `AppGraph.SCHEMA_VERSION = 9` (:754) is stamped into exports and
`/v1/status`; `VersionAgreementTest` pins 9/9 and binds the constant to `AppDatabase`. Migration tests
are JVM, one class per step (`Migration1To2Test` … `Migration8To9Test`) over
`MigrationTestSupport.createSchemaVersion`/`openMigrated`, whose chain is hard-coded (:64-68); Room's
`MigrationTestHelper` cannot run on the JVM here (:19-27). `BackupCodec.FORMAT_VERSION = 9`, `Json` with
`encodeDefaults = true` and unknown keys refused; `AttachmentDto` mirrors the row; the §7.1 attachment
checks live in `BackupCodec.validateGraph` (:638-670); the `FIRST_CATEGORY_FORMAT` check (:236-240) runs
after the strict decode; `LegacyArchive.upgrade` passes attachments through untouched. The merge planner
compares `dto == local.toDto()` → IDENTICAL else CONFLICT/CONTENT_DIFFERS, and one conflict anywhere means
nothing is written (`server.py:1040-1041`; `docs/api/v1.md`). The API exposes no attachment routes; the
archive import and `/v1/status` docs say "format **1–9**" (`v1.md:181`, `:1010`), "`schemaVersion` (8 in
1.4, 9 since #74)" (`:184-185`); the MCP docstring lists formats at `server.py:1036-1038`. The bundle
tool emits format 5 (`archive.py:29`) and never writes roles.

**Screens.** `DocumentsSection` (asset and event detail): "Documents · n", a 56 dp thumbnail or kind
glyph, the name, a quiet line `kind.label() · size · capturedOn`, "Add file" / "Take photo"; with the
store NotConfigured/AccessLost both actions are **hidden** and one `StatusBlock` card shows ("Attachment
storage" / "Attachment storage not set up" / "Choose a folder in Settings" / "Open settings" → `Route.Settings`)
— a ruling recorded in the code (:90-111); the store state is re-read on entering composition (:141).
`AttachmentsSection` (:119) builds the section's view model under the key `attachments-assets/<id>`
(:126-129) and owns the pickers and the sheet. Rows open through `onOpen`; `AttachmentEditSheet` takes
only the row ("Name", "Kind" chips over `AttachmentKind.entries`, "Captured on", "Notes", "Delete" /
"Cancel" / "Save") and builds the update command field by field (:103-108); `AttachmentRowState(id,
displayName, kind, sizeBytes, capturedOn, notes, mimeType, locator, isImage, present, thumbnail)` has
neither role nor createdAt. `AttachmentsSectionViewModel` stages picks as `PickedFile(displayName,
mimeType, sizeBytes?, fromCamera, open: () -> InputStream)`; `rememberAttachmentPickers` builds it over
`LocalContext.current.contentResolver` (`AttachmentPickers.kt:51-52`, `:136`) — an Activity resolver a view
model must never retain (the section's KDoc at :86-90 only keeps `Uri` off the JVM path); it copies on an
injected `io` context (:102-106), adds with `capturedOn = today`, and
maps `AttachmentProblem` to sentences in a **private** `say()` (:363-375: `Give the file a name`, `Choose
an attachment folder in Settings first`, `The attachment folder is not available`, `That file is larger
than 256 MB`, `That file is no longer here`) with `Could not add <name>` inline (:304) and `No app can
pick files` on a picker launch failure (`DocumentsSection.kt:149`). `AttachmentPickers` uses
`OpenMultipleDocuments` and `TakePicture`; a result grant lasts as long as the receiving activity record.
The Asset editor: blocks Identity, Placement, Operating season, Maintenance break, Health subjects,
**Purchase** ("Purchase date", "In service date", vendor, price, currency), Warranty, Notes;
`SectionHeader` draws upper-case, `SentenceSectionHeader` sentence case; the title ("New asset"/"Edit
asset") and the template row key off `editing` (:259, :305); the close button stays enabled while
`saving` (:260-263). `AssetEditViewModel` holds `val id: AssetId?` — **null for a new asset**
(:1493, from `Route.AssetEdit(null)`); `save()` builds one `AssetSettingsCommand` and `commit` calls
`saveAssetSettings.run(id, cmd, …)` (:1702); `SaveAssetSettings.run` mints the id for a new asset
(`current ?: Asset(id = AssetId(ids.newId()), …)`, :96) and returns early on an unchanged form (:108);
`saving` drops to false right after the write (:1722); `asksManualPhase` compares against
`storedSeasonMode`, read once at load (:1389), and a MANUAL → MANUAL command that still sends
`manualPhase` is refused as `ManualPhaseForbidden` with nothing drawn (`SeasonCommands.kt:185-186`,
`AssetViewModels.kt:1961-1969`); the #78 reconcile question (`reconcilePromptFor`, :1760-1768) also reads
the stale stored mode, and `keepSchedules()` emits `saved` (:1656-1666); `saved` → `onDone`. The Asset
detail draws `AttachmentsSection` (:372) then `ReferencesSection`; `DetailsSection` reads only
`AssetDetailState` (:767-787) and lists "Purchase date", "Price", "Vendor", "Warranty", "Warranty notes".
Share intake (`ShareIntakeViewModel`, BYTES/LINK/NOTE): every word from `IntakeStrings`; the "Type" chips
over `AttachmentKind.entries` are drawn on BYTES and never on a LINK (`theTypeControlIsDrawnOnBytesAndNeverOnALink`);
`ShareIntakeScreen` is stateless and `ShareIntakeScreenTest` renders it directly. The picker seam a
Compose test can stub is `EmptyStoreRestorePromptTest`'s `LocalActivityResultRegistryOwner` registry
(:36-40, :61-80) — in-process, allowed by the testing-hierarchy ruling. The editor's JVM suite is
`AssetViewModelsTest`.

## 2. Behaviour (the briefs' contract)

- **C1, the role.** `enum class DocumentRole { PURCHASE_INVOICE_OR_RECEIPT, USER_MANUAL, SERVICE_MANUAL }`,
  `Attachment.role: DocumentRole? = null`, and `DocumentRole.label()` beside `AttachmentKind.label()` as
  the single home of P67-2/3/4 and P67-6. A role is allowed only on an asset-owned attachment — no model
  `init`: `AddAttachment` rejects a role on an event owner **before** `put` (`require`, a programming
  error the UI and the codec never reach), and `AttachmentDto.toDomain()` checks owner and role first and
  throws `BackupCorrupt`. Role and kind are independent (R67-7). Any number of documents may share a role
  (R67-3, R67-14); ordering within a role is `capturedOn` descending (nulls last), then `createdAt`
  descending; nothing is persisted about "preferred".
- **C2, use cases.** `AddAttachmentCommand.role: DocumentRole? = null`. `UpdateAttachmentCommand.role:
  DocumentRole?` **without a default**: null means "no role" (a clear), and every caller passes the row's
  current role — Task 1 adds `role` to `AttachmentRowState` (the view-model mapping) and the sheet — the command's
  only construction site (`AttachmentEditSheet.kt:103-108`) — passes `row.role`, so a rename, re-kind or
  notes edit keeps the role (pinned in the rows and in the use case; Task 2b adds the device check that
  captures the sheet's saved command). No view-model override of the role, ever: it would mask the chips. A role-only update is
  `UpdateAttachment` metadata: the store sees no `put`; locator, sha256 and size are unchanged (AC 5).
- **C3, schema 10.** `MIGRATION_9_10` = `ALTER TABLE attachment ADD COLUMN document_role TEXT` (nullable,
  no backfill), added to `AppGraph`'s migration list and to `MigrationTestSupport.openMigrated`'s chain;
  `AppDatabase` version 10; `AppGraph.SCHEMA_VERSION = 10`; `VersionAgreementTest` pins 10/10; `10.json`
  exported; entity, DAO, `Mappers.kt` and the in-memory double map the field.
- **C4, format 10.** `FORMAT_VERSION = 10`; `AttachmentDto.role: String? = null`, written as `"role":
  null` like `capturedOn` (the codec's `encodeDefaults`); `validateGraph`: a role must be one of the three
  names and only on an asset-owned attachment; after the strict decode, an archive with `formatVersion ≤ 9`
  whose attachments carry a **non-null** role is refused on the `FIRST_CATEGORY_FORMAT` pattern (an explicit
  `"role": null` is accepted, as `assetCategories: []` is); `LAST_LEGACY_FORMAT` stays 7; replace carries
  the role through `toDomain`; the merge comparison is R67-12's. Docs: `v1.md:181` and `:1010` (the format range),
  `:184-185` (the `schemaVersion`/`backupFormatVersion` lines, where the phrase wraps), `:1022` (the
  IDENTICAL definition — where R67-12's outcome is documented) and `server.py:1036-1038` gain their 10 line; the release-gate paragraph in `docs/release-proofs.md`
  becomes the direct 1.4.1 (schema 8) → schema 10 proof, restated executably: seed attachments of every
  kind through a data-archive merge with the bytes staged (or the owner's data), upgrade, prove every row
  unchanged with `document_role IS NULL` and a pre-upgrade export re-planning IDENTICAL, then set roles
  and prove the export → restore/merge round trip; `docs/versioning.md` at the next release, not now.
- **C5, the editor's staging (AC 1, 2).** `AssetEditState` gains `staged: List<StagedDocument>`
  (`StagedDocument(role, file: PickedFile)` — the section's own `PickedFile`, which the new single-document
  launcher builds over `context.applicationContext.contentResolver`, never the Activity's; no
  `android.net.Uri` in the view model) and the store state, re-read on entering
  composition and on return from Settings. The **Purchase** block gains the receipt affordance and lists
  the asset's existing receipts read-only under the role label; a new **Key documents** block below
  Warranty gains the two manual affordances and lists the existing manuals the same way (R67-6). Each
  affordance is a button (P67-7/8/9; a single `OpenDocument`, any MIME, the `No app can pick files`
  sentence on launch failure); a staged file draws as a line `<name>` with the quiet text P67-10 and the
  reused visible `Remove` (content description P67-12). With the store NotConfigured/AccessLost the three
  affordances are **hidden** and the section's `StatusBlock` card shows once, in the Key documents block,
  its "Open settings" pushing `Route.Settings` through a new `onOpenSettings` on `AssetEditScreen` wired
  in `ServiceTagRoot` (R67-13). Staging holds nothing but the `PickedFile`; a result grant lasts the
  activity record, so process death forgets the staging (R67-2) and a grant revoked before Save fails at
  copy time with `Could not add <name>` and is cleared with Remove. Nothing is copied and no row exists
  until C6 says so.
- **C6, Save with staged files (replaces rev 1's C6).** `saving` is true from the tap until C6 ends; a
  second tap is ignored; the close button and back are held while saving. The view model keeps
  `savedId: AssetId?` (the route's id, or the id the write minted), `storedSeasonMode` refreshed from the
  written row, and `writtenForm`, the user-editable fields as they were at the last successful write — exactly the fields
  `settingsCommand` reads, excluding `storedSeasonMode`, `staged`, `saving`, `problems` and the store state. Save runs:
  1. **The settings write, only if the form differs from `writtenForm`** (or nothing was written yet):
     `SaveAssetSettings.run(savedId, cmd)`. A refusal leaves the form as typed and the staged list intact.
     On success the view model first decides the #78 question from the **pre-write** stored mode exactly as
     today (`reconcilePromptFor` fires only from YEAR_ROUND, edit-only, keyed to the route id — a new asset
     whose template seeded a CONTINUOUS schedule is never asked once `savedId` exists), then records the
     written id and refreshes the stored mode, flips `editing = true` (the title reads "Edit asset" and the
     template row hides, both keyed off `editing` today), and stores `writtenForm`.
     A retry whose form is unchanged since the write **never calls `SaveAssetSettings` again**; a retry
     after further edits calls it with `savedId` and the refreshed stored mode, so MANUAL → MANUAL sends
     no phase and is not refused.
  2. **The copies, straight after the write and before any #78 question**, in staged order on the
     injected `io` context: `AddAttachment.run(OfAsset(savedId), cmd(role, kind = null, capturedOn =
     today), file.open)`; each success drops its line; the first failure sentences its line through the
     extracted mapping (`StoreUnavailable` → `The attachment folder is not available`; a throwing `put` or
     a `SecurityException` from `open` → `Could not add <name>`) and stops, the rest staying staged. The
     asset is never undone.
  3. **Only when the staged list is empty:** the question decided in step 1, held while anything was
     staged, is raised at most once; then `saved` emits (or after the question's answer, as today).
     `keepSchedules()` therefore never emits `saved` over a staged file.
  Cancel after a failure keeps the saved asset and drops the staged files. Pins: after a retry on a
  **new** asset `assets.count() == 1`; a MANUAL new asset with a failing copy retries to completion; the
  #78 question is asked exactly once; a second `save()` during the copies is a no-op; a settings refusal
  keeps the staged list.
- **C7, the sheet and the intake (AC 3).** `AttachmentRowState` carries `role` from Task 1 and gains `createdAt`; the section
  passes the sheet `rolesOffered = owner is OfAsset`; `AttachmentEditSheet` gains a **Role** section under
  Kind (P67-5 as a `SectionHeader` like "KIND"; chips P67-6 + the three labels, seeded from `row.role`,
  keyed by `row.id` like the other fields) and passes the chosen role in the command. Share intake gains
  the same Role section for BYTES shares only (R67-9) — `ShareIntakeScreen` gains `onRole`, the view
  model passes it through `AddAttachmentCommand.role`; LINK and NOTE untouched; the intake spec §7/§10
  gain the section as an amendment; §3.3/§4 untouched. The intake's "every word from `IntakeStrings`"
  rule holds through one `const val ROLE` initialised from the `ui/attachments` home, and the labels
  through `DocumentRole.label()`, as the kind labels already are.
- **C8, the detail (AC 4).** The **Key documents** block is drawn **inside `AttachmentsSection`**, directly
  above the Documents list, from the same view model, pickers and sheet — no second load, no duplicate
  rows; header P67-1 as a `SectionHeader` (drawn "KEY DOCUMENTS"); drawn only when at least one role-tagged
  row exists; groups in the fixed order receipt → user manual → service manual, each under its role label
  (a `FieldLabel`, sentence case), each row the section's own `DocumentRow` with the same `onOpen` and
  `onEdit`. The Documents list is unchanged (R67-4). The Details fact `Purchase document` (R67-5) comes
  from the detail view model observing `attachments.observeForOwner(OfAsset(id))` and naming the newest
  receipt's display name; it is text, not a link.
- **C9, nothing else moves.** Event attachments (no role, no picker); the store, locator, sweep; the
  intake's lift, scheme and security; #73/#71's list; the API routes; the bundle tool (R67-10); the
  season, health and maintenance editor blocks; the Documents row text.

## 3. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| role only on assets (C1) | core `AttachmentUseCasesTest` · `aRoleOnAnEventIsRejectedBeforeAnyCopy` (store `put` count 0); `BackupCodecTest` · `aRoleOnAnEventAttachmentIsBackupCorrupt` | check after `put`; plain exception |
| role never touches bytes (C2, AC 5) | core `AttachmentUseCasesTest` · `aRoleOnlyUpdateWritesNoBytes` (spy store, `put` 0; locator/sha/size equal); `addAttachmentCarriesTheRole` | call `put`; drop the field |
| the rows and updates carry the role (C2) | `AttachmentsSectionViewModelTest` · `theRowsCarryTheRole`; core `AttachmentUseCasesTest` · `anUpdateCarryingTheRowsRoleKeepsIt`; device (Task 2b) `AssetDetailKeyDocumentsTest` · `aRenameThroughTheSheetLeavesTheRoleUnchanged` (captures `onSave`'s command) | drop the mapping; the sheet omits the role |
| schema 10 (C3) | `Migration9To10Test` (JVM, `createSchemaVersion`/`openMigrated`, after `Migration8To9Test`) · `everyAttachmentRowSurvivesWithANullRole` (field for field); `theMigratedSchemaEqualsAFreshVersion10`; `VersionAgreementTest` 10/10 | a default in the migration; a missing list entry (the chain grep) |
| format 10 (C4) | `BackupCodecTest` · `aRoleRoundTrips`; `aFormat9ArchiveWithANonNullRoleIsRefused`; `aFormat9ArchiveWithAnExplicitNullRoleRestores`; `anUnknownRoleNameIsRefused`; `aFormat10ArchiveWithoutRolesRestoresAsBefore`; `LAST_LEGACY_FORMAT` 7 (grep) | accept; skip the check |
| merge (C4, AC 6, R67-12) | `MergePlannerTest` · the cases of the chosen option: a format-9 archive against a role-tagged local row; a format-10 archive with the same role (IDENTICAL), a different role, a null against a local role; `ImportBackupMergeTest` · `aMergedRoleLands` on a new row | compare without the role; compare with it |
| staging creates nothing (C5, AC 1) | `AssetViewModelsTest` · `stagingWritesNothingUntilSave` (attachment count 0 and store empty after stage + cancel, and after a settings refusal, which also keeps the staged list); `savingANewAssetAttachesTheStagedFilesWithTheirRoles` (rows owned by the minted id, role set, kind inferred, `capturedOn` = today, `saved` after); `removingAStagedFileForgetsIt` | copy at pick; attach before the write |
| no duplicate asset (C6, B1) | · `aRetryAfterAFailedCopyAddressesTheSavedAssetOnly` (new asset, store AccessLost then Ready: `assets.count() == 1`, `saved` once, `editing` true after the write); `aSecondTapDuringTheCopiesIsIgnored` | call `run(null)` again; drop `saving` early |
| the retry is not refused (C6, M1) | · `aManualNewAssetWithAFailingCopyRetriesToCompletion`; `theReconcileQuestionIsAskedOnceAndOnlyWhenNothingIsStaged`; `anUnchangedRetryNeverCallsSaveAssetSettings` (counted through `AssetRepository.all()`, the write's first read, on a port fake — not through the unit of work) | send the phase again; ask twice; emit `saved` from `keepSchedules` over a staged file |
| sentences (C6) | · `aLostFolderSentencesTheLine` (AccessLost → `The attachment folder is not available`); `aThrowingCopySentencesTheLine` (`put` throws, and `open` throwing `SecurityException` → `Could not add <name>`; Remove then Save finishes) | swap the sentences |
| no store (C5, R67-13) | `AssetViewModelsTest` · `withoutAFolderTheStateHidesTheAffordances` (the store-state flag); device `AssetEditorKeyDocumentsTest` · `theCardShowsOnceWithoutAFolder` (the three buttons absent, the card once) | show the flag; disable instead of hide |
| sheet role (C7, AC 3) | `AttachmentsSectionViewModelTest` · `editingTheRoleUpdatesMetadataOnly`; device `AssetDetailKeyDocumentsTest` · `theRoleChipsAreOfferedForAnAssetRowAndNotForAnEventRow` (the sheet drawn alone with the flag) | offer them on events |
| intake role (C7) | `ShareIntakeViewModelTest` · `aBytesShareCarriesTheChosenRole`; `aLinkShareHasNoRole`; device `ShareIntakeScreenTest` · `theRoleControlIsDrawnOnBytesAndNeverOnALink` | pass the role to links |
| detail Key documents (C8, AC 4) | `AttachmentsSectionViewModelTest` · `keyDocumentsGroupByRoleNewestFirst`; `noRoleNoBlock`; device `AssetDetailKeyDocumentsTest` · `aReceiptShowsInKeyDocumentsAndInDocumentsAndOpensTheSameAttachment` (same id through both `onOpen`s); `theDetailsFactsNameThePurchaseDocument` | a second row; duplicate load |
| editor device (C5) | `AssetEditorKeyDocumentsTest` (new, emulator; the picker stubbed through `LocalActivityResultRegistryOwner` as `EmptyStoreRestorePromptTest` does) · `theThreeAffordancesStageAndRemove`; `cancelLeavesNoRowAndNoFile` (Room count + SAF tree listing); `theCardShowsOnceWithoutAFolder` | — |
| regression | `AttachmentsDeviceProofTest`, `DocumentsDescriptionLineTest`, `AssetEditorCategoryPickerTest`, `AssetEditorSeasonAndHealthTest`, `SafTreeAttachmentStoreContractTest`, `Format7RestoreContractTest`, `CategoriesScreenTest`, every `Migration*Test`, `ShareIntakeScreenTest` (changed by the new case and its `show()` helper's `onRole`) green | — |

## 4. Files (indicative; the implementer owns the placement)

**Task 1 (core + data):** `core/model/Attachment.kt` (`DocumentRole`, `role`), `core/usecase/AttachmentCommands.kt`,
`AddAttachment.kt`, `UpdateAttachment.kt`, `core/backup/BackupFormat.kt` (`AttachmentDto.role`, `toDomain`),
`BackupCodec.kt` (format 10, `validateGraph`, the ≤ 9 refusal), `core/merge/MergePlanner.kt` (R67-12),
`app/…/data/room/entities/AttachmentEntity.kt`, `dao/AttachmentDao.kt`, `data/room/Mappers.kt`,
`Migrations.kt` (`MIGRATION_9_10`), `AppDatabase.kt` (10), `di/AppGraph.kt` (the migration list,
`SCHEMA_VERSION = 10`), `app/schemas/…/10.json`, `app/src/test/…/data/room/MigrationTestSupport.kt` (the
chain), `VersionAgreementTest.kt`, `Migration9To10Test.kt` (new), `core/src/test/…/InMemoryRepositories.kt`,
`ui/attachments/AttachmentsSectionViewModel.kt` (`AttachmentRowState.role`) and `ui/attachments/AttachmentEditSheet.kt` (passes `row.role`) (C2), `docs/api/v1.md`,
`tools/servicetag-mcp/…/server.py` (docstring), `docs/release-proofs.md` (the gate paragraph).
**Task 2a (editor intake):** `ui/asset/AssetViewModels.kt` (`AssetEditState.staged`, `savedId`,
`writtenForm`, the C6 orchestration), `ui/asset/AssetEditScreen.kt` (Purchase affordance, Key documents
block, `onOpenSettings`, hold while saving), `ui/nav/ServiceTagRoot.kt` (the route), `ui/attachments/
AttachmentPickers.kt` (a single-document launcher whose `PickedFile.open` closes over the application
context's resolver), `ui/attachments/AttachmentsSectionViewModel.kt`
(the extracted sentence mapping, `DocumentRole.label()` home — or a sibling file). Tests: `AssetViewModelsTest`,
`AssetEditorKeyDocumentsTest`.
**Task 2b (sheet, intake, detail):** `ui/attachments/AttachmentsSectionViewModel.kt` (`role`/`createdAt`
on rows, the grouped view, `rolesOffered`), `DocumentsSection.kt` (the Key documents block inside
`AttachmentsSection`), `AttachmentEditSheet.kt` (Role chips), `ui/asset/AssetViewModels.kt` (the detail's
`Purchase document` fact from `observeForOwner`), `AssetDetailScreen.kt` (the fact line),
`share/ShareIntakeViewModel.kt` + `ShareIntakeScreen.kt` (Role chips for BYTES), the intake spec §7/§10
amendment. Tests: `AttachmentsSectionViewModelTest`, `ShareIntakeViewModelTest`, `AssetDetailKeyDocumentsTest`,
`ShareIntakeScreenTest`.
**Untouched:** `attachments/Saf*`, `AttachmentSweep`, `SharedItem`/lift/scheme policy, `ApiRouter`,
`tools/servicetag-bundle`, #73/#71 list code, the engine, the dashboard, the Documents row text.

## 5. Schema, backup and merge implications (summary for the owner)

Room 9 → 10: one nullable column, no data move, wired into the production migration list and the test
chain. Backup format 9 → 10: one field on attachments, written as `"role": null` when unset; format ≤ 9
archives restore exactly as today; a format ≤ 9 archive carrying a non-null role is refused as malformed;
`LAST_LEGACY_FORMAT` stays 7. **Merge (R67-12):** because one conflict anywhere blocks a whole merge and
an id already on the phone is never overwritten, a role difference is not "just another field": under
plain comparison, a role set on either phone blocks the dev → production merge, and every existing export
in the backups folder stops re-planning IDENTICAL once any of its attachments gets a role. API: no route
change; the import and status docs read 1–10 and "10 since #67". MCP: docstrings. The next signed
release's upgrade gate becomes the direct 1.4.1 (schema 8) → schema 10 proof (R67-10); nothing here bumps
`versionName`.

## 6. Strings — for ratification

| id | where | text | treatment |
|---|---|---|---|
| P67-1 | section title, detail (inside the attachments section) and editor | `Key documents` | `SectionHeader`, drawn upper-case like DOCUMENTS |
| P67-2 | role label | `Purchase invoice or receipt` | chip text; group label as a `FieldLabel` |
| P67-3 | role label | `User manual` | same |
| P67-4 | role label | `Service manual` | same |
| P67-5 | picker header, sheet and intake | `Role` | `SectionHeader`, like KIND / TYPE |
| P67-6 | picker chip | `No role` | chip |
| P67-7 | editor button, Purchase block | `Add purchase invoice or receipt` | `TextButton` with the attach-file glyph, as "Add file" |
| P67-8 | editor button, Key documents block | `Add user manual` | same |
| P67-9 | editor button, Key documents block | `Add service manual` | same |
| P67-10 | staged line quiet text; replaced by the problem sentence after a failed copy | `Attached when you save` | `QuietLine` |
| P67-11 | Details fact label | `Purchase document` | as "Purchase date" |
| P67-12 | accessibility only: the content description of the reused visible `Remove` on a staged line | `Remove <name>` | — |
| — | reused verbatim | `Remove` (ReferenceSheets), `Attachment storage` / `Attachment storage not set up` / `Choose a folder in Settings` / `Open settings`, `No app can pick files`, the six sentences and `Could not add <name>` of §1, `Not on this device`, `No app can open this file`, `More for <name>`, `Documents`, the seven kind labels, `Cancel` / `Save` | through their homes |

Twelve new strings; nothing else changes wording.

## 7. Rulings and owner questions (controller, 2026-09-26)

- **R67-1 (recommended: orthogonal).** The role is a new nullable field beside `kind`, not a refinement of
  `AttachmentKind`. **Owner: confirm.**
- **R67-2 (recommended: hold the pick, copy on save).** Staging keeps the section's `PickedFile` in the
  editor's view model and copies only after the settings write; no cache file, no sweep, no orphan by
  construction. Cost: process death between pick and Save forgets the pick (the typed form is not restored
  today either). Alternative: copy into the app cache at pick time and sweep on cancel. **Owner: confirm.**
- **R67-3 (recommended: no persisted "preferred").** Several documents may hold one role; the newest by
  `capturedOn` then `createdAt` lists first and is the one the Details fact names. Visible consequence: a
  staged receipt is dated today, not the typed purchase date. **Owner: confirm.**
- **R67-4 (recommended).** Key documents sits inside the attachments section directly above the Documents
  list, drawn only when a role-tagged document exists; the Documents rows are unchanged. **Owner: confirm,
  or ask for a role marker on the Documents row (one more string form).**
- **R67-5 (recommended: yes).** The Details facts gain `Purchase document` naming the newest receipt (text,
  not tappable).
- **R67-6 (recommended).** The receipt affordance and the existing receipts live in the Purchase block; the
  manual affordances and the existing manuals in a new Key documents block below Warranty; reclassifying
  an existing file is the sheet's Role picker on the detail.
- **R67-7 (confirmation of spec §4).** The role never constrains the kind.
- **R67-8 (recommended).** A copy that fails after the settings write does not undo the asset; the editor
  stays open on the saved asset (title "Edit asset"), the failed line carries the sentence, Save retries the
  copies only; Cancel keeps the asset and drops the staged files. Two choices under this ruling, recommended
  and yours to confirm: close and back are held for the whole copy (up to three files of up to 256 MB, no
  progress line — the Save button shows its saving state), and the copies stop at the first failure (spec
  §8.1's multi-add continues with the rest; here the failed line must be resolved before the editor can
  finish, so stopping keeps the order legible).
- **R67-9 (recommended).** Share intake offers the Role chips for BYTES shares only (an intake-spec §7/§10
  amendment).
- **R67-10 (the release-proofs precedent).** Schema 10 / format 10 land on master under `versionName`
  1.4.1 exactly as schema 9 did (emulator only; no phone); the release gate becomes the direct 8 → 10
  proof; the bundle tool stays at format 5 (a follow-up issue if roles are to be loaded by tool).
- **R67-11 (from the issue's framing).** A role is allowed only on asset-owned attachments.
- **R67-12, the merge — owner's ruling.** (A) plain comparison: a role difference is CONFLICT and blocks
  the merge like a rename; every pre-10 export stops re-planning IDENTICAL once a role is assigned.
  **(B, recommended)** a format ≤ 9 archive is compared **without** the role (an incoming absent role
  against a local role is IDENTICAL), so every existing export keeps re-planning IDENTICAL; a format-10
  archive is compared with it (same → IDENTICAL, different → CONFLICT, null against a role → CONFLICT). (C)
  treat the role as mergeable metadata — an incoming role on a locally-null row is applied, an incoming
  null never clears, two different roles conflict — which is the only option under which a role
  propagates dev → production by merge, and the only one that changes the "never overwritten" contract —
  at the cost of a new merge verdict or update write path in the planner and `ImportBackupMerge`
  (`MergeVerdict` has four members; the merge only ever inserts today) plus the API/MCP report schema and
  docs.
  **Owner: A, B or C.**
- **R67-13, no store (recommended: hide, per spec §8.1).** With no attachment folder the three affordances
  are hidden and the section's card shows once, in the Key documents block, its "Open settings" leaving the
  editor for Settings and the store state re-read on return. Alternative: disabled buttons (a departure
  from §8.1's ruling). **Owner: confirm.**
- **R67-14 (recommended).** AC 2's "replace" is read as adding another document; the newest lists first;
  nothing is deleted or overwritten. **Owner: confirm.**

## 8. What this plan does not do

No OCR or purchase-fact inference; no new file store; no API route; no bundle-tool change; no change to
the Documents row text; no `versionName` bump; no phone install; no preferred-document flag; no
reclassification inside the editor; no role on event attachments.

## 9. Brief — Task 1: role, schema 10, format 10, merge, docs

**Read first:** §1–§8; issue #67; the attachments spec §4, §6, §7.1, §11; `Attachment.kt`,
`AttachmentCommands.kt`, `AddAttachment.kt`, `UpdateAttachment.kt`, `BackupFormat.kt` (`AttachmentDto`),
`BackupCodec.kt` (`validateGraph`, the `FIRST_CATEGORY_FORMAT` check), `MergePlanner.kt` (the attachments
block), `Migrations.kt` (`MIGRATION_8_9`), `AppDatabase.kt`, `di/AppGraph.kt` (the migration list,
`SCHEMA_VERSION`), `data/room/Mappers.kt`, `AttachmentEntity.kt`, `AttachmentDao.kt`,
`MigrationTestSupport.kt`, `Migration8To9Test`, `VersionAgreementTest`, `BackupCodecTest`, `MergePlannerTest`,
`AttachmentUseCasesTest`, `AttachmentsSectionViewModel.kt` (the row mapping) and `AttachmentEditSheet.kt` (:103-108, the command's
construction site).
**Lane:** alone, branched from master after this plan's ratified commit. **Blocked on:** §6, §7 (R67-12 in
particular). **Contracts:** C1–C4, C9. **Test matrix:** §3 rows 1–6 verbatim. **Produces for Task 2:**
`DocumentRole` + `label()` home, `Attachment.role`, the two command fields (update's without a default),
the DAO column, `AttachmentDto.role`, the merge behaviour.
**Gate:** `:core:test :app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`;
connected `Format7RestoreContractTest`, `SafTreeAttachmentStoreContractTest`, `AttachmentsDeviceProofTest`
on `emulator-5554`; greps: `LAST_LEGACY_FORMAT: Int = 7` → 1; `FORMAT_VERSION = 10` → 1; `version = 10`
in `AppDatabase.kt` → 1; `SCHEMA_VERSION = 10` → 1; `MIGRATION_9_10` present in `AppGraph.kt` and in
`MigrationTestSupport.kt`; `10.json` present; `git grep -nE 'format \*?\*?1–' -- docs/api tools/servicetag-mcp/src`
→ every hit says `1–10`; `sed -n '181p;184,185p;1010p;1022p' docs/api/v1.md` → each names 10 and `:1022`
states R67-12's rule; `git diff <base> --stat -- app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/share
app/src/main/kotlin/com/loosecannon/servicetag/attachments tools/servicetag-bundle` → only
`ui/attachments/AttachmentsSectionViewModel.kt` (the row mapping) and `ui/attachments/AttachmentEditSheet.kt`
(passing `row.role`).
**Must NOT:** touch bytes on an update; backfill the column; move `LAST_LEGACY_FORMAT`; add a model
`init`; check the role after `put`; touch the external_link/externalLinks tombstones; touch any other UI;
touch the bundle tool.
**Size:** medium.

## 10. Brief — Task 2a: the editor's intake (C5, C6)

**Read first:** §1–§8; issue #67 AC 1–2; the attachments spec §8.1; `AssetEditScreen.kt`,
`AssetViewModels.kt` (`AssetEditState`, `AssetEditViewModel` — `id`, `save`, `commit`, `saved`,
`asksManualPhase`, `reconcilePromptFor`, `keepSchedules`), `SaveAssetSettings.kt` (:96, :108),
`SeasonCommands.kt` (:185-186), `AttachmentsSectionViewModel.kt` (`PickedFile`, the `io` context, `say()`,
:304), `DocumentsSection.kt` (:90-111, :141, :149), `AttachmentPickers.kt`, `ui/nav/ServiceTagRoot.kt`
(:207-222), `EmptyStoreRestorePromptTest` (the registry seam), `AssetEditorSeasonAndHealthTest`, Task 1's
report. **Lane:** alone, after Task 1 is merged into the branch. **Contracts:** C5, C6, C9 (+ the extracted
sentence mapping and the `DocumentRole.label()` home if Task 1 did not place them). **Test matrix:** §3
rows 7–11 and the editor device row.
**Gate:** JVM as Task 1; connected on `emulator-5554`, one class per invocation: `AssetEditorKeyDocumentsTest`,
`AssetEditorCategoryPickerTest`, `AssetEditorSeasonAndHealthTest`, `AttachmentsDeviceProofTest`; greps:
P67-1, P67-7/8/9/10 each exactly once in `app/src/main` (`git grep -nF '"Add user manual"'` → 1, etc.) and
P67-12 once in its template form (`"Remove $name"` or the equivalent the implementer writes);
`git grep -nE '"(Receipt|Manual|Photo)"' -- app/src/main` → the same hits as the base (`AttachmentKind.label()`
and `ui/components/Previews.kt`); `git diff <base> --stat -- core app/src/main/kotlin/com/loosecannon/servicetag/share/SharedItem.kt app/src/main/kotlin/com/loosecannon/servicetag/attachments`
→ empty.
**Must NOT:** copy or write anything before the settings write; call `SaveAssetSettings` twice for one
written form; let `saving` drop before the copies end; emit `saved` over a staged file; ask the #78
question twice; re-spell a sentence; disable an affordance (hide it); touch the sheet, the intake or the
detail; use any device but `emulator-5554`.
**Size:** medium.

## 11. Brief — Task 2b: the sheet, the intake and the detail (C7, C8)

**Read first:** §1–§8; issue #67 AC 3–4; the attachments spec §8.1; the intake spec §3.3, §4, §7, §10;
`AttachmentsSectionViewModel.kt`, `DocumentsSection.kt` (`AttachmentsSection`, `DocumentRow`),
`AttachmentEditSheet.kt` (:48-51, :103-108), `AssetDetailScreen.kt` (`DetailsSection` :767-787, the
`AttachmentsSection` call :372), the detail view model in `AssetViewModels.kt`, `ShareIntakeViewModel.kt`,
`ShareIntakeScreen.kt` (:35-36, the Type chips), `ShareIntakeScreenTest` (`theTypeControlIsDrawnOnBytesAndNeverOnALink`),
Task 1's and Task 2a's reports. **Lane:** alone, after Task 2a. **Contracts:** C7, C8, C9. **Test
matrix:** §3 rows 12–14.
**Gate:** JVM as Task 1; connected on `emulator-5554`, one class per invocation: `AssetDetailKeyDocumentsTest`,
`DocumentsDescriptionLineTest`, `AttachmentsDeviceProofTest`, `ShareIntakeScreenTest`; greps: P67-5, P67-6,
P67-11 each exactly once in `app/src/main`; `"Role"` → the one home (the intake's const initialised from
it counts as no second literal); `git diff <base> --stat -- core app/src/main/kotlin/com/loosecannon/servicetag/share/SharedItem.kt app/src/main/kotlin/com/loosecannon/servicetag/attachments app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetEditScreen.kt`
→ empty.
**Must NOT:** create a second attachment row for a role; add a load for the Key documents block (the fact's
one `observeForOwner` observation in the detail view model is the accepted second read); offer the
Role picker on an event attachment or a LINK/NOTE share; change the Documents row text; change the
intake's lift, scheme or security code; touch the editor; use any device but `emulator-5554`.
**Size:** medium.
