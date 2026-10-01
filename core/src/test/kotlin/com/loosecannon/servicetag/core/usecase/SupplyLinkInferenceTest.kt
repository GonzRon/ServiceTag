package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #15 (C37.1, row 37) — **no inference, anywhere.** A material line named exactly as a SupplyItem stays
 * unlinked through every path that writes one: `SaveProfile`, `LogEvent`, `ApplyTemplate`, a merge and a
 * replace import. A link exists only because a person chose it, or because a row carrying it was copied
 * verbatim; a name match is never one.
 */
class SupplyLinkInferenceTest {

    private val home = LinkageInstall("home")

    private val named = "Example Prefilter Cartridge"

    private fun EventProfile.links() = consumables.map { it.name to it.supplyId }
    private fun AssetEvent.links() = consumables.map { it.name to it.supplyId }

    /** A donor install holding a SupplyItem and a quick action and an event whose lines carry its exact name, unlinked. */
    private suspend fun donorArchive(): ByteArray {
        val donor = LinkageInstall("donor")
        val asset = donor.createAsset.run("Example RO System").id
        donor.item(named)
        donor.replaceAction(asset, "Replace prefilter", named, link = null)
        donor.logEvent.run(event(asset, named))
        return donor.raw.export.run().data
    }

    private fun event(asset: AssetId, line: String) = EventCommand(
        assetId = asset, profileId = null, kind = EventKind.REPLACEMENT, title = "Prefilter swap",
        occurredOn = "2026-02-20", occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(),
        consumables = listOf(ConsumableInput(line, "1", "ea", supplyId = null)),
    )

    @Test
    fun saveProfileNeverLinksByName() = runBlocking<Unit> {
        val asset = home.createAsset.run("Example RO System").id
        home.item(named)

        val saved = home.replaceAction(asset, "Replace prefilter", named, link = null)

        assertEquals(listOf(named to null), home.raw.profiles.get(saved.id)!!.links())
    }

    @Test
    fun logEventNeverLinksByName() = runBlocking<Unit> {
        val asset = home.createAsset.run("Example RO System").id
        home.item(named)

        val logged = home.logEvent.run(event(asset, named))

        assertEquals(listOf(named to null), home.raw.events.get(logged.id)!!.links())
    }

    @Test
    fun applyTemplateNeverLinksByName() = runBlocking<Unit> {
        val lineNames = SeedTemplates.all.flatMap { it.profiles }.flatMap { it.consumables }.map { it.name }.distinct()
        assertTrue(lineNames.isNotEmpty())
        lineNames.forEach { home.item(it) }

        SeedTemplates.all.forEach { template -> home.createAsset.run("Example ${template.name}", templateKey = template.key) }

        val lines = home.raw.profiles.all().flatMap { it.links() }
        assertEquals(lineNames.toSet(), lines.map { it.first }.toSet())
        assertTrue(lines.all { it.second == null }, "$lines")
        assertEquals(lineNames.size, home.raw.supplyItems.all().size)
    }

    @Test
    fun aMergeNeverLinksByName() = runBlocking<Unit> {
        home.item(named)
        val bytes = donorArchive()

        val plan = home.raw.build.run(bytes)
        assertTrue(plan.applicable, "${plan.conflicts}")
        home.raw.apply.run(plan)

        assertEquals(listOf(named to null), home.raw.profiles.all().single().links())
        assertEquals(listOf(named to null), home.raw.events.all().single().links())
        assertEquals(2, home.raw.supplyItems.all().count { it.name == named })
    }

    @Test
    fun aReplaceImportNeverLinksByName() = runBlocking<Unit> {
        val bytes = donorArchive()

        home.raw.replace.run(bytes)

        assertEquals(listOf(named to null), home.raw.profiles.all().single().links())
        assertEquals(listOf(named to null), home.raw.events.all().single().links())
        assertEquals(listOf(named), home.raw.supplyItems.all().map { it.name })
    }
}
