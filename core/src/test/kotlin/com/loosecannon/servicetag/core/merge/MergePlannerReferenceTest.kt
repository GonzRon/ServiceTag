package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The merge planner's 1.3 half: `REFERENCES`, its **second identity** `(asset_id, uri)`, the three
 * new reasons, and the one rule that departs from every shipped second identity — a diverged local
 * row makes the incoming one **`SKIPPED`**, never a `CONFLICT` (D-18 C, spec §5).
 *
 * Same shape as `MergePlannerTest`: one archive and one snapshot in, one plan out, nothing written.
 * The two arms that look alike are kept apart on purpose and each says which case it is: the
 * *equivalent* second identity compares every backup-format field, `createdAt` and `updatedAt`
 * included, so it is reachable in practice only from a copied or replayed archive, while the
 * *diverged* one is the live two-phone case.
 */
class MergePlannerReferenceTest {

    // --- fixtures ---------------------------------------------------------------------------

    private fun asset(id: String, name: String = "Cub Cadet XT1") =
        Asset(id = AssetId(id), name = name, createdAt = 1L, updatedAt = 2L)

    private fun reference(
        id: String,
        assetId: String = "a1",
        uri: String = "https://example-mower.invalid/parts?model=XT1#deck",
        displayName: String = "OEM parts lookup",
        description: String = "deck belt and blades",
        kind: ReferenceKind = ReferenceKind.WEB_URL,
        scheme: String = "https",
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
    ) = AssetReference(
        id = ReferenceId(id), assetId = AssetId(assetId), kind = kind, uri = uri,
        displayName = displayName, description = description, scheme = scheme,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun backupOf(
        assets: List<Asset> = emptyList(),
        references: List<AssetReference> = emptyList(),
    ) = Backup(
        manifest = BackupManifest(
            formatVersion = 7, appVersion = "1.3.0", schemaVersion = 7, createdAt = 1L,
            counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
        ),
        data = BackupData(
            assets = assets.map { it.toDto() },
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            assetReferences = references.map { it.toDto() },
        ),
    )

    private fun snapshotOf(
        assets: List<Asset> = emptyList(),
        references: List<AssetReference> = emptyList(),
    ) = MergeSnapshot(
        assets = assets,
        references = references,
        attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(id: String): MergeDecision =
        decisions.single { it.table == MergeTable.REFERENCES && it.id == id }

    // --- write order --------------------------------------------------------------------------

    /**
     * Hazard: merge order breaks a reference. `REFERENCES` follows `ATTACHMENTS` — the last of the
     * eleven shipped tables, with 1.4's three after it — and an archive whose asset and reference
     * arrive together writes the asset first — so the reference's owner is an
     * `INSERT` of this same plan by the time it is decided.
     *
     * The inversion the order exists to prevent is pinned beside it: with the asset taken out of
     * the archive the same reference is `OWNER_NOT_AVAILABLE` instead, which is what a pass
     * decided *before* `ASSETS` would answer for every row.
     */
    @Test
    fun `references are decided after the asset that owns them`() {
        // The last of the eleven shipped tables; 1.4's three follow it (`MergePlannerSeasonHealthTest`).
        assertEquals(MergeTable.REFERENCES, MergeTable.entries[10])

        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"))),
            snapshotOf(),
        )

        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(listOf("a1"), plan.writes.assets.map { it.id.value })
        assertEquals(listOf("r1"), plan.writes.references.map { it.id.value })
        assertEquals(MergeDecision(MergeTable.REFERENCES, "r1", MergeVerdict.INSERT), plan.decision("r1"))

        val orphaned = mergePlanOf(backupOf(references = listOf(reference("r1"))), snapshotOf())
        assertEquals(
            MergeDecision(
                MergeTable.REFERENCES, "r1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a1",
            ),
            orphaned.decision("r1"),
        )
    }

    /**
     * Hazard: a reference is not planned at all. A new reference on an asset this install already
     * has plans `INSERT`, the row appears in `MergeWrites.references`, and the tally counts it —
     * without the pass the table would be silently absent from every report.
     */
    @Test
    fun `a new reference is an insert, in the writes and in the tally`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"))),
            snapshotOf(assets = listOf(asset("a1"))),
        )

        assertEquals(listOf("r1"), plan.writes.references.map { it.id.value })
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 0), plan.tally(MergeTable.REFERENCES))
        assertEquals(MergeTally(1, 0, 0, 0), plan.report().references)
    }

    // --- the row's own id ----------------------------------------------------------------------

    /**
     * Hazard: an unchanged re-import stops being a no-op. An archive re-imported into the install
     * that produced it plans `IDENTICAL` for every reference row — so a mapper that rewrote
     * `updated_at`, or re-derived `scheme` on a read, would show up here as `CONTENT_DIFFERS`.
     */
    @Test
    fun `a re-imported reference is identical`() {
        val rows = listOf(reference("r1"), reference("r2", uri = "joplin://note/abc", scheme = "joplin"))
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = rows),
            snapshotOf(assets = listOf(asset("a1")), references = rows),
        )

        assertEquals(MergeTally(0, 2, 0, 0), plan.tally(MergeTable.REFERENCES))
        assertTrue(plan.decisions.filter { it.table == MergeTable.REFERENCES }
            .all { it.reason == MergeReason.NONE })
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Hazard: a locally edited reference is silently overwritten. The **same id** with a different
     * description is `CONFLICT / CONTENT_DIFFERS`, and the plan's writes are empty **in every
     * field** — there is no `UPDATE` verdict, so adopting the incoming text is not on offer.
     */
    @Test
    fun `the same id with different content is a blocking conflict and writes nothing`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"))),
            snapshotOf(
                assets = listOf(asset("a1")),
                references = listOf(reference("r1", description = "belt only")),
            ),
        )

        assertEquals(
            MergeDecision(
                MergeTable.REFERENCES, "r1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "r1",
            ),
            plan.decision("r1"),
        )
        assertFalse(plan.applicable)
        assertEquals(MergeWrites(), plan.writes)
    }

    // --- the second identity, `(asset_id, uri)` ------------------------------------------------

    /**
     * Hazard: the equivalent second identity becomes an insert. A local row under a **different**
     * id that is field-for-field equal under `dto.copy(id = samePair.id)` is `IDENTICAL /
     * REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`, and the detail names the holder.
     *
     * Matching on the row id alone would make this an `INSERT` that the unique index refuses at
     * apply time. Equality here includes `createdAt` and `updatedAt`, which two phones that each
     * typed the link agree about never — so this arm is a **copied or replayed archive**, not the
     * live two-phone case, which the next test is.
     */
    @Test
    fun `an equivalent local row under another id is identical, not an insert`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"))),
            snapshotOf(assets = listOf(asset("a1")), references = listOf(reference("local-1"))),
        )

        assertEquals(
            MergeDecision(
                MergeTable.REFERENCES, "r1", MergeVerdict.IDENTICAL,
                MergeReason.REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW, "local-1",
            ),
            plan.decision("r1"),
        )
        assertTrue(plan.applicable)
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Hazard: **the diverged second identity blocks the archive (D-18 C).** Two phones saved the
     * same URL on the same asset under different names. The incoming row is
     * `SKIPPED / REFERENCE_HELD_BY_A_LOCAL_ROW`, nothing is written for it, **`applicable` stays
     * true**, and everything else in the archive still applies.
     *
     * Raising a `CONFLICT` there would empty `MergeWrites` and refuse the whole archive — the exact
     * failure D-18 C exists to prevent, and what the second half of this test pins: the asset's
     * other, new reference is still an `INSERT`.
     */
    @Test
    fun `a diverged local row under another id is skipped and never blocks`() {
        val fresh = reference("r2", uri = "https://example-mower.invalid/manual", displayName = "Manual")
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"), fresh)),
            snapshotOf(
                assets = listOf(asset("a1")),
                references = listOf(reference("local-1", displayName = "Parts list")),
            ),
        )

        assertEquals(
            MergeDecision(
                MergeTable.REFERENCES, "r1", MergeVerdict.SKIPPED,
                MergeReason.REFERENCE_HELD_BY_A_LOCAL_ROW, "local-1",
            ),
            plan.decision("r1"),
        )
        assertTrue(plan.applicable, "a skip must never block: ${plan.conflicts}")
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 1), plan.tally(MergeTable.REFERENCES))

        // Hazard: a skipped row is written anyway. Only the other row is in the write set.
        assertEquals(listOf("r2"), plan.writes.references.map { it.id.value })
        assertTrue(plan.writes.references.none { it.id.value == "r1" })
    }

    /**
     * Hazard: the same-id arm stops blocking. Folding both arms into the skip would make references
     * incapable of ever blocking an archive, which spec §5 explicitly does not want — so the two
     * cases are planned together here and the verdicts must differ.
     */
    @Test
    fun `the same-id arm still blocks while the second-identity arm skips`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1"), asset("a2", name = "Orchard Pump")),
                references = listOf(
                    reference("r1"),
                    reference("r2", assetId = "a2", uri = "https://example-mower.invalid/pump"),
                ),
            ),
            snapshotOf(
                assets = listOf(asset("a1"), asset("a2", name = "Orchard Pump")),
                references = listOf(
                    reference("local-1", displayName = "Parts list"),
                    reference("r2", assetId = "a2", uri = "https://example-mower.invalid/pump", description = "hose"),
                ),
            ),
        )

        assertEquals(MergeVerdict.SKIPPED, plan.decision("r1").verdict)
        assertEquals(MergeVerdict.CONFLICT, plan.decision("r2").verdict)
        assertEquals(MergeReason.CONTENT_DIFFERS, plan.decision("r2").reason)
        assertFalse(plan.applicable, "the same-id arm must still be able to refuse an archive")
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Hazard: an archive disagreeing with itself. Two rows of one archive claiming one
     * `(assetId, uri)` under different ids — which the decoder deliberately does not refuse — are
     * an `INSERT` and then a `CONFLICT / REFERENCE_DUPLICATED_IN_ARCHIVE` naming the first's id.
     *
     * Without the claimed-pairs map both would insert and the unique index would refuse one at
     * apply time, halfway through the write.
     */
    @Test
    fun `two archive rows claiming one pair are a conflict naming the first`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1")),
                references = listOf(reference("r1"), reference("r2", displayName = "Parts")),
            ),
            snapshotOf(assets = listOf(asset("a1"))),
        )

        assertEquals(MergeVerdict.INSERT, plan.decision("r1").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.REFERENCES, "r2", MergeVerdict.CONFLICT,
                MergeReason.REFERENCE_DUPLICATED_IN_ARCHIVE, "r1",
            ),
            plan.decision("r2"),
        )
        assertFalse(plan.applicable)
    }

    /**
     * Hazard: the same URI on two different assets is refused. `UNIQUE(asset_id, uri)` is a pair,
     * so one URL filed on two machines is ordinary and both rows insert (I-7). A pair map keyed on
     * the URI alone would make the second a duplicate.
     */
    @Test
    fun `one uri on two assets is ordinary`() {
        val shared = "https://example-mower.invalid/oil-spec"
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1"), asset("a2", name = "Orchard Pump")),
                references = listOf(
                    reference("r1", uri = shared),
                    reference("r2", assetId = "a2", uri = shared),
                ),
            ),
            snapshotOf(assets = listOf(asset("a1"), asset("a2", name = "Orchard Pump"))),
        )

        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(listOf("r1", "r2"), plan.writes.references.map { it.id.value })
    }

    // --- the report -----------------------------------------------------------------------------

    /**
     * Hazard: the report loses a table. `MergeReport`'s tallies are read **by name, in
     * `MergeTable` order**, and compared against `tally(table)` for each member — so a tally
     * dropped from the type stops compiling, one wired to the wrong table fails on its value, and
     * one left out of `report()` fails as an empty tally where a populated one belongs.
     *
     * The archive is shaped so the fifteen expectations are not all the same value: only
     * `references` and `assets` carry rows, so a report that read `attachments` where it meant
     * `references` — the drift a positional mirror invites — fails here rather than passing on a
     * row of zeroes.
     */
    @Test
    fun `the report carries a tally per table in MergeTable order`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1")), references = listOf(reference("r1"))),
            snapshotOf(),
        )
        val report = plan.report()

        val byName = listOf(
            MergeTable.ASSETS to report.assets,
            MergeTable.GROUPS to report.groups,
            MergeTable.DEFINITIONS to report.definitions,
            MergeTable.PROFILES to report.profiles,
            MergeTable.SCHEDULES to report.schedules,
            MergeTable.CLOSURES to report.closures,
            MergeTable.LINKS to report.links,
            MergeTable.TAGS to report.tags,
            MergeTable.EVENTS to report.events,
            MergeTable.ATTACHMENTS to report.attachments,
            MergeTable.REFERENCES to report.references,
            MergeTable.SEASON_ACTIVATIONS to report.seasonActivations,
            MergeTable.CONDITIONS to report.conditions,
            MergeTable.HEALTH_SUBJECTS to report.healthSubjects,
            // #74: appended last, in enum order, though categories are written first.
            MergeTable.CATEGORIES to report.categories,
        )
        assertEquals(MergeTable.entries.toList(), byName.map { it.first })
        assertEquals(MergeTable.entries.map { plan.tally(it) }, byName.map { it.second })

        // and the two the archive actually populates, so the comparison above is not all zeroes
        assertEquals(MergeTally(1, 0, 0, 0), report.references)
        assertEquals(MergeTally(1, 0, 0, 0), report.assets)
        assertEquals(MergeTally(0, 0, 0, 0), report.attachments)
        // The last of the eleven shipped tables; 1.4's three follow it (`MergePlannerSeasonHealthTest`).
        assertEquals(MergeTable.REFERENCES, MergeTable.entries[10])
    }

    /**
     * Hazard: deterministic reports. Two plans over the same archive and the same snapshot produce
     * the same decision order and the same fingerprint — an unordered map iteration anywhere in the
     * new pass would make the fingerprint move between runs.
     */
    @Test
    fun `two plans over one archive agree on order and fingerprint`() {
        val assets = listOf(asset("a1"))
        val rows = listOf(
            reference("r3", uri = "https://example-mower.invalid/c"),
            reference("r1", uri = "https://example-mower.invalid/a"),
            reference("r2", uri = "https://example-mower.invalid/b"),
        )
        val backup = backupOf(assets = assets, references = rows)
        val snapshot = snapshotOf(assets = assets)

        val first = mergePlanOf(backup, snapshot)
        val second = mergePlanOf(backup, snapshot)

        assertEquals(first.decisions, second.decisions)
        assertEquals(first.fingerprint, second.fingerprint)
        // and the reference decisions are sorted by id within their table, as every table is
        assertEquals(
            listOf("r1", "r2", "r3"),
            first.decisions.filter { it.table == MergeTable.REFERENCES }.map { it.id },
        )
    }
}
