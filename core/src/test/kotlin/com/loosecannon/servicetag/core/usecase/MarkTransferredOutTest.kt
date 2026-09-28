package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.EMPTY_GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferFixtures.OPENER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #77 (C8; AC 9, 10, 15; R77-6, R77-16) — marking, one write: the pack re-read, re-selected, re-encoded and
 * compared inside it, then every pack asset archived, each group wholly in the pack archived, and one OUT per
 * asset appended **last**. Every refusal writes nothing (`commits == 0`). The estate is the fixtures' (the
 * heater, its anode, their flush round, an empty round, an archived opener, an unrelated compressor); the
 * names and pack ids are fictional.
 */
class MarkTransferredOutTest {

    private val heater = AssetId(HEATER)
    private val anode = AssetId(ANODE)
    private val lifecycle = mutableListOf<AssetId>()

    private suspend fun seeded() = BackupInstall().also { TransferFixtures.seed(it) }

    /** The heater's pack as the ready screen holds it: created, sealed, its file's sha256 taken. */
    private suspend fun created(
        install: BackupInstall,
        lineageOf: suspend (AssetId) -> List<String> = { emptyList() },
    ): CreatedPack {
        val result = TransferPackTesting.creationOf(install, lineageOf).run(listOf(heater), "Example handover note")
        val draft = assertIs<CreateTransferPackResult.Created>(result).draft
        return CreatedPack.of(draft, TransferPackTesting.seal(draft).second.packSha256)
    }

    private fun markerOf(install: BackupInstall): MarkTransferredOut {
        var next = 0
        return MarkTransferredOut(
            TransferPackTesting.repositoriesOf(install), install.uow, IdGenerator { "out-${next++}" }, Clock { MARKED_AT },
            onLifecycleChanged = { lifecycle += it },
        )
    }

    private suspend fun BackupInstall.snapshot() = readSnapshot(TransferPackTesting.repositoriesOf(this))

    @Test
    fun marksInOneWrite() = runBlocking<Unit> {
        val install = seeded()
        val pack = created(install)

        val marked = assertIs<MarkTransferredOutResult.Marked>(markerOf(install).run(pack))

        assertEquals(1, install.uow.commits, "one write")
        assertEquals(listOf(heater, anode), lifecycle, "each asset's lifecycle rebuild, in the write")
        for (id in listOf(heater, anode)) {
            val asset = install.assets.get(id)!!
            assertEquals(AssetStatus.ARCHIVED to MARKED_AT, asset.status to asset.updatedAt, id.value)
        }
        val expected = listOf(
            TransferRecord("out-0", heater, TransferKind.OUT, pack.packId, emptyList(), MARKED_AT, pack.packSha256, "Example Water Heater", "Example handover note"),
            TransferRecord("out-1", anode, TransferKind.OUT, pack.packId, emptyList(), MARKED_AT, pack.packSha256, "Example Anode Rod", "Example handover note"),
        )
        assertEquals(expected, marked.records)
        assertEquals(expected, install.transfers.all())
        assertEquals(setOf(heater, anode), install.transfers.heldIds())
    }

    /** The flush round is wholly the pack's and is archived; the empty round is wholly in nothing and is not. */
    @Test
    fun aWhollyInGroupIsArchivedAndAnEmptyOneIsNot() = runBlocking<Unit> {
        val install = seeded()

        assertIs<MarkTransferredOutResult.Marked>(markerOf(install).run(created(install)))

        val flush = install.groups.get(GroupId(GROUP))!!
        assertEquals(MARKED_AT to MARKED_AT, flush.archivedAt to flush.updatedAt)
        val empty = install.groups.get(GroupId(EMPTY_GROUP))!!
        assertEquals(null to TransferFixtures.groups.single { it.id.value == EMPTY_GROUP }.updatedAt, empty.archivedAt to empty.updatedAt)
    }

    /** R77-16: re-read and re-hashed inside the write — a row added, changed or scanned since creation. */
    @Test
    fun aRowAddedOrChangedSinceThePackIsPackOutdated() = runBlocking<Unit> {
        val edits: List<Pair<String, suspend (BackupInstall) -> Unit>> = listOf(
            "an event added" to { it.events.upsert(completionOf("e9", "2026-06-01", null, assetId = HEATER)) },
            "the heater renamed" to { it.assets.upsert(it.assets.get(heater)!!.copy(name = "Example Water Heater 2")) },
            "a tag scanned" to { it.tags.upsert(it.tags.get(TagId("t1"))!!.copy(lastScannedAt = 1_758_970_000_000L)) },
        )
        for ((what, edit) in edits) {
            val install = seeded()
            val pack = created(install)
            edit(install)

            assertEquals(MarkTransferredOutResult.PackOutdated(heater), markerOf(install).run(pack), what)
            assertEquals(0, install.uow.commits, what)
            assertEquals(emptyList(), install.transfers.all(), what)
        }
    }

    /** A new component, a new group member or a new loan since creation: refused, nothing written. */
    @Test
    fun aNewChildMemberOrLoanIsRefused() = runBlocking<Unit> {
        val child = seeded()
        val childPack = created(child)
        child.assets.upsert(plainAssetOf("h3", "Example Drain Valve").copy(parentAssetId = anode))
        assertEquals(MarkTransferredOutResult.PackOutdated(AssetId("h3")), markerOf(child).run(childPack))
        assertEquals(0, child.uow.commits)

        val member = seeded()
        val memberPack = created(member)
        val round = member.groups.get(GroupId(GROUP))!!
        member.groups.upsert(round.copy(members = round.members + GroupMember("m9", AssetId(COMPRESSOR), 9, 1_758_900_000_000L, null)))
        assertEquals(MarkTransferredOutResult.PackOutdated(heater), markerOf(member).run(memberPack))
        assertEquals(0, member.uow.commits)

        val lent = seeded()
        val lentPack = created(lent)
        lent.loans.upsert(loanOf("l9", assetId = ANODE))
        assertEquals(MarkTransferredOutResult.OpenLoan(anode, "l9"), markerOf(lent).run(lentPack))
        assertEquals(0, lent.uow.commits)
        assertEquals(emptyList(), lent.transfers.all())
    }

    /** P77-57: an asset already held here is never marked twice; nothing is written. */
    @Test
    fun alreadyHeldIsAlreadyTransferred() = runBlocking<Unit> {
        val install = seeded()
        val pack = created(install)
        assertIs<MarkTransferredOutResult.Marked>(markerOf(install).run(pack))

        assertEquals(MarkTransferredOutResult.AlreadyTransferred(listOf(heater, anode)), markerOf(install).run(pack))
        assertEquals(1, install.uow.commits)
        assertEquals(2, install.transfers.all().size)
    }

    /**
     * The lineage creation sealed must still be the records' answer at marking — an IN appended since is a
     * different pack — and each OUT carries the pack's lineage for its asset.
     */
    @Test
    fun theLineageIsComparedAndCarried() = runBlocking<Unit> {
        val arrived = seeded()
        arrived.transfers.append(transferOf("i1", assetId = HEATER, kind = TransferKind.IN, packId = "pack-o", lineage = listOf("pack-n")))
        arrived.transfers.append(transferOf("i2", assetId = ANODE, kind = TransferKind.IN, packId = "pack-o", lineage = listOf("pack-n")))
        val lineageOf: suspend (AssetId) -> List<String> = { com.loosecannon.servicetag.core.model.lineageFor(arrived.transfers.all(), it) }
        val pack = created(arrived, lineageOf)
        check(pack.lineage == mapOf(HEATER to listOf("pack-n", "pack-o"), ANODE to listOf("pack-n", "pack-o")))

        val marked = assertIs<MarkTransferredOutResult.Marked>(markerOf(arrived).run(pack))
        assertEquals(listOf(listOf("pack-n", "pack-o"), listOf("pack-n", "pack-o")), marked.records.map { it.lineage })

        val stale = seeded()
        val stalePack = created(stale)
        stale.transfers.append(transferOf("i1", assetId = ANODE, kind = TransferKind.IN, packId = "pack-o"))
        assertEquals(MarkTransferredOutResult.PackOutdated(anode), markerOf(stale).run(stalePack))
        assertEquals(0, stale.uow.commits)
    }

    /** Marking changes each pack asset's status and stamp and the round's archive stamp — and nothing else. */
    @Test
    fun markingRewritesNoHistory() = runBlocking<Unit> {
        val install = seeded()
        val before = install.snapshot()

        assertIs<MarkTransferredOutResult.Marked>(markerOf(install).run(created(install)))

        val after = install.snapshot()
        val held = setOf(HEATER, ANODE)
        fun unmarked(data: com.loosecannon.servicetag.core.backup.BackupData) = data.copy(
            assets = data.assets.filterNot { it.id in held },
            maintenanceGroups = data.maintenanceGroups.filterNot { it.id == GROUP },
            transferRecords = emptyList(),
        )
        assertEquals(unmarked(before), unmarked(after))
        for (id in held) {
            val was = before.assets.single { it.id == id }
            assertEquals(was.copy(status = AssetStatus.ARCHIVED.name, updatedAt = MARKED_AT), after.assets.single { it.id == id })
        }
        val round = before.maintenanceGroups.single { it.id == GROUP }
        assertEquals(round.copy(archivedAt = MARKED_AT, updatedAt = MARKED_AT), after.maintenanceGroups.single { it.id == GROUP })
    }

    /**
     * The controller's ruling: a staying row naming a pack row — the compressor's event set onto the heater's
     * schedule, reachable through the API — is refused at marking (P77-58), not left to break the next export.
     */
    @Test
    fun aStayingRowNamingAPackRowIsEntangled() = runBlocking<Unit> {
        val install = seeded()
        install.events.upsert(completionOf("e9", "2026-05-01", "2026-05-01", assetId = COMPRESSOR, scheduleId = "s1"))
        val pack = created(install)

        val refusal = assertIs<MarkTransferredOutResult.Entangled>(markerOf(install).run(pack))

        assertEquals(listOf(EntangledRef("assetEvents", "e9", "maintenanceSchedules", "s1")), refusal.refs)
        assertEquals(0, install.uow.commits)
    }

    /**
     * R77-B2a-MARK: marking succeeds only if the **whole** estate it leaves retains cleanly — no exemption for a
     * reference an earlier transfer left. Here the compressor is already held, and a staying row (an event of the
     * archived opener, set by hand onto the compressor's schedule) names one of its rows: marking the unrelated
     * heater pack is refused, nothing written.
     */
    @Test
    fun markingRefusesAnEstateAlreadyEntangledByAnEarlierTransfer() = runBlocking<Unit> {
        val install = seeded()
        install.transfers.append(transferOf("r0", assetId = COMPRESSOR, packId = "pack-earlier"))
        install.events.upsert(completionOf("e9", "2026-05-01", "2026-05-01", assetId = OPENER, scheduleId = "s2"))
        val pack = created(install)

        val refusal = assertIs<MarkTransferredOutResult.Entangled>(markerOf(install).run(pack))

        assertEquals(listOf(EntangledRef("assetEvents", "e9", "maintenanceSchedules", "s2")), refusal.refs)
        assertEquals(0, install.uow.commits)
        assertEquals(listOf("r0"), install.transfers.all().map { it.id })
    }

    /** A pack with no id or no file hash was never sealed: a contract violation, before any read. */
    @Test
    fun aBlankIdOrShaIsRefused() = runBlocking<Unit> {
        val install = seeded()
        val pack = created(install)

        assertFailsWith<IllegalArgumentException> { markerOf(install).run(pack.copy(packId = " ")) }
        assertFailsWith<IllegalArgumentException> { markerOf(install).run(pack.copy(packSha256 = "")) }
        assertEquals(0, install.uow.commits)
    }

    private companion object {
        const val MARKED_AT = 1_758_990_000_000L
    }
}
