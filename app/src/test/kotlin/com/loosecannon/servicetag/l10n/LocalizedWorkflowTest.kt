package com.loosecannon.servicetag.l10n

import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ResourcePack
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.maintenance.COMPLETE_SELECTED
import com.loosecannon.servicetag.ui.maintenance.CompletionAnswer
import com.loosecannon.servicetag.ui.maintenance.LastCompletionReadings
import com.loosecannon.servicetag.ui.maintenance.MAINTENANCE_SHEET_TITLE
import com.loosecannon.servicetag.ui.maintenance.MaintenanceSheetViewModel
import com.loosecannon.servicetag.ui.maintenance.NOTHING_DUE
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.maintenance.SHEET_OPEN_ASSET
import com.loosecannon.servicetag.ui.maintenance.SheetBlock
import com.loosecannon.servicetag.ui.maintenance.TAG_PLACEMENT
import com.loosecannon.servicetag.ui.maintenance.WHEN_WAS_THIS_DONE
import com.loosecannon.servicetag.ui.maintenance.statusLabel
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.scan.ScanEvent
import com.loosecannon.servicetag.ui.scan.ScanViewModel
import com.loosecannon.servicetag.ui.scan.TagResult
import com.loosecannon.servicetag.ui.scan.TagResultViewModel
import com.loosecannon.servicetag.ui.scan.TagResultWire
import com.loosecannon.servicetag.ui.scan.identityLine
import com.loosecannon.servicetag.ui.scan.placementOrNull
import java.time.LocalDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #102, acceptance 4, 7 and 8 (and 3 on this path): the representative **NFC → Asset → maintenance** workflow, walked
 * in English and in every shipped language pack, in process — nothing here drives a screen (`docs/release-proofs.md`).
 *
 * One walk per language, each on a fresh [FakeGraph] with fixed row ids, the counting id generator, a fixed clock and
 * UTC, with the pack installed through [AppText.install] for the length of the walk:
 *
 * 1. **NFC read** — [ScanViewModel] over a [TagIo] holding the V1 NDEF `NdefCodec.encodeV1` writes; its answer is the
 *    language-neutral `Route.TagResult` (format, key) pair.
 * 2. **Tag → Asset** — [TagResultViewModel] re-resolves that pair, as `TagResultSheet` does, to `OpensAsset` with
 *    `maintenance` true, so the ambient scan goes to `Route.MaintenanceSheet` (#50).
 * 3. **The asset's maintenance** — [MaintenanceSheetViewModel] on that route's ids lists the one OVERDUE quick schedule.
 * 4. **Completion** — "Complete selected", then `CompletionFlow`'s "When was this done?" answered with its default,
 *    today.
 * 5. **After** — the sheet reads S139 "Nothing due", and the asset detail's schedules section reads OK. That section is
 *    read through the projection it draws, `DueReadModel.forAsset` filtered to asset targets as `AssetDetailViewModel`
 *    filters it: the view model's state is a hot combine over two dozen Room flows whose collector would outlive each
 *    language's database, and the row it draws takes its word from the same [statusLabel].
 *
 * What each walk proves:
 *
 * - **The words are the pack's** (acceptance 3, 4). Every word a step draws is compared with `pack.stringNamed` for
 *   the resource the screen names: the Kotlin getters and state fields the screens draw ([MAINTENANCE_SHEET_TITLE],
 *   `SheetItem.statusWord`, `whyNow`, `completionTakes`, [WHEN_WAS_THIS_DONE], [NOTHING_DUE] ...) as rendered under
 *   the installed pack, and, for words a composable draws with `stringResource` — the scanner's, the tag sheet's, the
 *   completion dialog's buttons — the same id read through [localized], which is what `stringResource` reads on a
 *   device in that language (composables do not render on the JVM). No name this walk read may have fallen back to
 *   English: the names it added to `pack.fallbacks` must be none. The delta, not the whole set, because a pack is
 *   one object per JVM and other classes may read it first; a name missing outright is also
 *   `LocalizationCoverageTest`'s failure.
 * - **The owner's text is shown as entered** (acceptance 8): the asset's name and notes, the tag placement and the
 *   schedule title, at every step that shows them, in every language; ids likewise, and a date field the owner types
 *   into keeps its ISO day. A date the owner reads inside a sentence is the pack's display date, never the ISO day.
 * - **What is stored is the same in every language** (acceptance 7): after the completion, every table the workflow
 *   can write — the scan stamps, the completion event, the recomputed schedule state — equals the English walk's,
 *   row for row. The event's title is the schedule's and its notes are empty: the workflow writes no word of its own.
 *
 * The languages are `listOf(ResourcePack.english) + ResourcePack.languagePacks`, so the walk runs in English alone
 * until a `values-<language>` pack ships and covers each pack as it lands. English is put back with
 * [EnglishResources] after every walk and again in [tearDown], so no other class sees a pack installed.
 *
 * One scheduler carries the test body and `Dispatchers.Main`, with each fixture's Room on a `StandardTestDispatcher`
 * sharing it — `MaintenanceSheetViewModelTest`'s arrangement — which is what makes `advanceUntilIdle()` a real settle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalizedWorkflowTest {

    private val scheduler = TestCoroutineScheduler()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        AppText.install(EnglishResources())
        Dispatchers.resetMain()
    }

    @Test fun theScanToCompletionWorkflowSpeaksEachLanguageAndStoresTheSameRows() = runTest(scheduler) {
        val stored = linkedMapOf<String, Map<String, List<Any>>>()
        for (pack in listOf(ResourcePack.english) + ResourcePack.languagePacks) {
            stored[pack.locale.toLanguageTag()] = walk(pack)
        }

        val english = stored.getValue(ResourcePack.english.locale.toLanguageTag())
        for ((language, rows) in stored) {
            assertEquals(english.keys, rows.keys)
            for ((table, englishRows) in english) {
                assertEquals(
                    "$language stores the $table English stores: a language changes no stored value (#102, acceptance 7)",
                    englishRows,
                    rows[table],
                )
            }
        }
    }

    /** The whole workflow in [pack]'s language on a fresh fixture; what it left in the store comes back. */
    private suspend fun TestScope.walk(pack: ResourcePack): Map<String, List<Any>> {
        val language = pack.locale.toLanguageTag()
        val graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        val fellBackBefore = pack.fallbacks.toSet()
        AppText.install(pack)
        try {
            graph.today = TODAY
            graph.now = NOW
            seed(graph)

            // 1. NFC: the tag is read and answered with the (format, key) pair, which carries no word.
            val scan = ScanViewModel(
                ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock, graph.transferRecords),
                NdefTagIo(graph.ndefCodec.encodeV1(TagId(KEY))),
                graph.ndefCodec,
                UnconfinedTestDispatcher(scheduler),
            )
            // A foreground collector, cancelled once the answer is in, as ScanViewModelTest collects: `advanceUntilIdle()`
            // stops when only background work is left, so a backgroundScope collector would never be resumed to
            // record the event.
            val shown = mutableListOf<ScanEvent>()
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) { scan.events.collect { shown += it } }
            scan.onTag(Handle)
            advanceUntilIdle()
            collecting.cancel()
            assertNull("$language: the read is not a problem", scan.state.value.problem)
            assertFalse("$language: the scanner is waiting again", scan.state.value.reading)
            val route = (shown.single() as ScanEvent.Show).route
            assertEquals(Route.TagResult(TagResultWire.wordFor(PayloadFormat.V1), KEY), route)
            assertDrawnFromPack(
                pack,
                "the scanner",
                listOf(
                    "scan_title" to localized(R.string.scan_title),
                    "scan_ready" to localized(R.string.scan_ready),
                    "scan_hold_hint" to localized(R.string.scan_hold_hint),
                ),
            )

            // 2. Tag → Asset: the sheet re-resolves the pair to the asset, with due work to open the sheet for.
            val result = TagResultViewModel(
                ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock, graph.transferRecords),
                BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
                graph.assets,
                graph.scanSheetOffer,
                route.format,
                route.key,
                graph.transferRecords,
            )
            advanceUntilIdle()
            val opened = result.state.value as? TagResult.OpensAsset
                ?: error("$language: the tag did not open its asset: ${result.state.value}")
            assertTrue("$language: due work sends the ambient scan to the completion sheet", opened.maintenance)
            assertEquals("$language: the asset's name, as entered", ASSET_NAME, opened.asset.name)
            assertEquals("$language: the asset's notes, as entered", ASSET_NOTES, opened.asset.notes)
            assertEquals("$language: the tag placement, as entered", PLACEMENT, opened.tag.placementOrNull())
            assertEquals("$language: the identifier is not a word", "55555555 · v1", opened.tag.identityLine())
            assertDrawnFromPack(
                pack,
                "the tag sheet",
                listOf(
                    "tag_eyebrow_reading" to localized(R.string.tag_eyebrow_reading),
                    "tag_looking_up" to localized(R.string.tag_looking_up),
                    "tag_eyebrow_detected" to localized(R.string.tag_eyebrow_detected),
                    "tag_placement" to localized(R.string.tag_placement),
                    "tag_opening_asset" to localized(R.string.tag_opening_asset),
                ),
            )

            // 3. The asset's maintenance: the sheet the route opens, on the route's ids alone.
            val sheetRoute = Route.MaintenanceSheet(opened.asset.id.value, opened.tag.id.value)
            var reconciles = 0
            val sheet = MaintenanceSheetViewModel(
                due = graph.dueReadModel,
                health = graph.assetHealthReadModel,
                assets = graph.assets,
                tags = graph.tags,
                readings = LastCompletionReadings { id -> graph.events.get(id)?.measurements.orEmpty() },
                lastCompletionEventId = graph.lastCompletionEventId,
                rounds = graph.scanRoundMembership,
                incidentNeed = graph.incidentNeed,
                snoozer = graph.scheduleSnooze,
                postponeSchedule = graph.postponeSchedule,
                reconcile = ReminderReconcile { reconciles++ },
                clock = graph.clock,
                completion = graph.completionFlow,
                assetId = AssetId(sheetRoute.assetId),
                tagId = sheetRoute.tagId?.let(::TagId),
            )
            advanceUntilIdle()
            val open = sheet.state.value
            val item = open.items.singleOrNull()
                ?: error("$language: expected the one due schedule, got ${open.items.map { it.title }}")
            assertEquals("$language: the sheet names the asset as entered", ASSET_NAME, open.assetName)
            assertEquals("$language: the sheet shows the placement as entered", PLACEMENT, open.tagPlacement)
            assertEquals("$language: the schedule title, as entered", SCHEDULE_TITLE, item.title)
            assertEquals(DueStatus.OVERDUE, item.status)
            val dueOn = item.effectiveDueOn ?: error("$language: an overdue time rule has a date")
            assertEquals(
                "$language: the why-now line is the pack's sentence around the pack's display date",
                String.format(pack.locale, pack.stringNamed("maintenance_why_now_overdue_since"), localizedDate(dueOn)),
                item.whyNow,
            )
            assertFalse("$language: no ISO day inside the localized sentence", item.whyNow.orEmpty().contains(dueOn))
            assertDrawnFromPack(
                pack,
                "the completion sheet",
                listOf(
                    "maintenance_title" to MAINTENANCE_SHEET_TITLE,
                    "maintenance_tag_placement" to TAG_PLACEMENT,
                    "maintenance_status_overdue" to item.statusWord,
                    "maintenance_one_tap" to item.completionTakes,
                    "maintenance_complete_selected" to COMPLETE_SELECTED,
                    "maintenance_sheet_open_asset" to SHEET_OPEN_ASSET,
                    "maintenance_not_now" to NOT_NOW,
                ),
            )

            // 4. Completion: "Complete selected", then "When was this done?" answered with today.
            sheet.toggle(item.scheduleId)
            assertTrue("$language: the row is selected", sheet.state.value.canComplete)
            sheet.completeSelected()
            advanceUntilIdle()
            val prompt = graph.completionFlow.prompt.value
                ?: error("$language: Complete selected asked nothing")
            assertEquals("$language: the question names the schedule as entered", SCHEDULE_TITLE, prompt.scheduleTitle)
            assertEquals("$language: today, as an ISO date", TODAY.toString(), prompt.occurredOn)
            assertDrawnFromPack(
                pack,
                "the completion question",
                listOf(
                    "maintenance_when_was_this_done" to WHEN_WAS_THIS_DONE,
                    "maintenance_date" to localized(R.string.maintenance_date),
                    "maintenance_time" to localized(R.string.maintenance_time),
                    "maintenance_save" to localized(R.string.maintenance_save),
                    "maintenance_cancel" to localized(R.string.maintenance_cancel),
                ),
            )
            assertTrue(
                "$language: the default answer completes",
                graph.completionFlow.submit(CompletionAnswer(occurredOn = prompt.occurredOn)),
            )
            advanceUntilIdle()

            // 5. After: nothing due on the sheet, OK on the asset's schedules section, one event in the journal.
            val done = sheet.state.value
            assertTrue("$language: the completed row is gone", done.items.isEmpty())
            assertTrue(done.blocks.filterIsInstance<SheetBlock.Maintenance>().single().nothingDue)
            assertEquals("$language: the completion reconciled once", 1, reconciles)
            val row = graph.dueReadModel.forAsset(AssetId(ASSET)).single { it.target is ScheduleTarget.AssetTarget }
            assertEquals(DueStatus.OK, row.status)
            assertEquals("$language: the detail row's title, as entered", SCHEDULE_TITLE, row.title)
            assertEquals("$language: the detail row's asset, as entered", ASSET_NAME, row.assetName)
            assertDrawnFromPack(
                pack,
                "after the completion",
                listOf(
                    "maintenance_nothing_due" to NOTHING_DUE,
                    "maintenance_status_ok" to statusLabel(row.status),
                ),
            )
            val event = graph.events.all().single()
            assertEquals("$language: the event is titled with the schedule's own words", SCHEDULE_TITLE, event.title)
            assertEquals("$language: the workflow writes no note of its own", "", event.notes)
            assertEquals(ScheduleId(SCHEDULE), event.scheduleId)
            assertEquals(TODAY.toString(), event.occurredOn)
            assertEquals(EventSource.SCHEDULE_QUICK_COMPLETE, event.source)

            assertEquals(
                "$language fell back to English on the scan-to-completion workflow (#102, acceptance 3)",
                emptySet<String>(),
                pack.fallbacks - fellBackBefore,
            )
            return stored(graph)
        } finally {
            AppText.install(EnglishResources())
            graph.close()
        }
    }

    /** A garage generator with one tag and one quarterly oil change, overdue on [TODAY]. Fixed ids throughout. */
    private suspend fun seed(graph: FakeGraph) {
        graph.assets.upsert(assetRow(ASSET, name = ASSET_NAME).copy(notes = ASSET_NOTES))
        graph.tags.upsert(
            TagBinding(
                TagId(KEY),
                PayloadFormat.V1,
                KEY,
                TagTarget.AssetTarget(AssetId(ASSET)),
                TagStatus.ACTIVE,
                label = PLACEMENT,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        graph.schedules.upsert(
            scheduleOf(SCHEDULE, assetId = ASSET, title = SCHEDULE_TITLE, anchorOn = "2026-01-01", leadDays = 0),
        )
        graph.recomputeSchedules.forSchedule(ScheduleId(SCHEDULE))
    }

    /** Every table this workflow can write, read back through the production repositories. */
    private suspend fun stored(graph: FakeGraph): Map<String, List<Any>> = linkedMapOf<String, List<Any>>(
        "assets" to graph.assets.all(),
        "tags" to graph.tags.all(),
        "schedules" to graph.schedules.all(),
        "scheduleStates" to graph.scheduleStates.all(),
        "events" to graph.events.all(),
        "conditions" to graph.conditions.all(),
        "scheduleLocalDelivery" to graph.scheduleLocalDelivery.all(),
    )

    /**
     * [drawn] pairs a resource name with the text a step draws for it, and each must be [pack]'s own string. Reading
     * it by name also records a fallback, which the walk's fallback check then reports.
     */
    private fun assertDrawnFromPack(pack: ResourcePack, step: String, drawn: List<Pair<String, String>>) {
        val wrong = drawn.mapNotNull { (name, text) ->
            val own = pack.stringNamed(name)
            if (text == own) null else "$name drew \"$text\", the pack says \"$own\""
        }
        assertEquals("${pack.locale.toLanguageTag()}, $step", emptyList<String>(), wrong)
    }

    private object Handle : TagHandle {
        override val uid: String = "04a1"
    }

    /**
     * A tag whose NDEF is exactly [records], as `ScanViewModelTest`'s readable fake: `ScanViewModel` reads nothing
     * but `inspection.read`, so the capacity fields are an ordinary writable tag's.
     */
    private class NdefTagIo(private val records: List<NdefRecordData>) : TagIo {
        override fun inspect(tag: TagHandle): TagInspection = TagInspection(
            "04a1",
            TagRead.Readable(records),
            maxSize = 137,
            writable = true,
            needsFormat = false,
            canLock = true,
        )
        override fun format(tag: TagHandle): WriteResult = error("not used")
        override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult = error("not used")
        override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean = error("not used")
    }

    private companion object {
        const val KEY = "55555555-5555-4555-8555-555555555555"
        const val ASSET = "gen"
        const val SCHEDULE = "s-oil"

        // The owner's own words: English, as an English-speaking owner typed them, and so in every language.
        const val ASSET_NAME = "Garage generator"
        const val ASSET_NOTES = "Fuel stabiliser on the left shelf — 10W-30 only"
        const val PLACEMENT = "Under the pull cord"
        const val SCHEDULE_TITLE = "Oil change"

        val TODAY: LocalDate = LocalDate.parse("2026-04-15")

        /** 09:30 UTC on [TODAY]: every stamp the walk writes — scans, the event, the recompute — is this instant. */
        val NOW: Long = dayMillis("2026-04-15") + 34_200_000L
    }
}
