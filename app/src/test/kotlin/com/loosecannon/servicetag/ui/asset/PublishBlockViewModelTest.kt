package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.seasonsync.UnconfirmedCause
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #113 row 88, the status half (C36; B4b2 review N4) — the equipment status block: each [PublishState] and each
 * [PublishErrorKind] drawn as its ratified sentence (both exhaustive), the published and verified lines kept apart
 * (`MATCHES` never drawn as published), every refusal's sentence, a `WAIT` error drawn as a fact and not a fault, and
 * the block's controls going through `PublishControls` over a Room-backed [FakeGraph] with a scripted Home Assistant.
 * The sentences are written out in their ratified words, so a paraphrase fails. Every name and helper is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishBlockViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val heater = AssetId("example-heater")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    // --- the pure block: one binding, one delivery, the derived state ------------------------------------------------

    private val binding = PublishBinding(
        id = "binding-1", assetId = heater, connectionId = "connection-1", kind = PublishKind.EQUIPMENT_STATUS,
        entityId = STATUS, enabled = true, revision = 4, activatedInstallationId = "example-installation",
        validatedAt = 1L, validatedRevision = 4, boundaryEntityId = null, createdAt = 1L, updatedAt = 1L,
    )

    private val pending = PublishDelivery(bindingId = "binding-1", desiredValue = "UP", desiredRevision = 7, desiredAt = 5L)

    private val writable = PublishBlockFacts(connected = true, assetName = "Example Heater", maintainedHere = true, writable = true)

    private fun block(state: PublishState, delivery: PublishDelivery = pending, facts: PublishBlockFacts = writable) =
        publishBlockOf(
            PublishKind.EQUIPMENT_STATUS, StatusWords,
            facts.copy(binding = binding, delivery = delivery, state = state),
        ) { "at $it" }

    /** Our POST at the current pair, read back. */
    private val published = pending.copy(
        lastPublishedValue = "UP", lastPublishedAt = 20L, publishedBindingRevision = 4, publishedDesiredRevision = 7,
        lastVerifiedValue = "UP", lastVerifiedAt = 20L, verifiedBindingRevision = 4, verifiedDesiredRevision = 7,
    )

    /** Home Assistant already held it: verified at the current pair, never published by us. */
    private val matching = pending.copy(
        lastVerifiedValue = "UP", lastVerifiedAt = 30L, verifiedBindingRevision = 4, verifiedDesiredRevision = 7,
    )

    private fun erred(kind: PublishErrorKind, detail: String? = null) = pending.copy(
        errorKind = kind, errorDetail = detail, errorAt = 9L, errorBindingRevision = 4, errorDesiredRevision = 7,
    )

    /** What a state draws: its line, the published and verified lines, the faults and the facts. */
    private fun drawn(state: PublishBlockState): List<Any?> =
        listOf(state.stateLine, state.publishedLine, state.verifiedLine, state.faultLines, state.factLines)

    @Test fun eachPublishStateHasItsSentence() {
        val expected: Map<PublishState, List<Any?>> = PublishState.entries.associateWith { state ->
            when (state) {
                // The switch says it: an off switch and the title, nothing else.
                PublishState.NOT_ENABLED -> listOf(null, null, null, emptyList<String>(), emptyList<String>())
                PublishState.COPIED_INSTALLATION -> listOf(COPIED, null, null, emptyList<String>(), emptyList<String>())
                PublishState.NEEDS_VALIDATION -> listOf(NEEDS_VALIDATION, null, null, emptyList<String>(), emptyList<String>())
                PublishState.NEEDS_TOKEN -> listOf(ENTER_TOKEN_AGAIN, null, null, emptyList<String>(), emptyList<String>())
                PublishState.WAITING_FOR_SOURCE -> listOf(WAITING, null, null, emptyList<String>(), emptyList<String>())
                PublishState.NOT_MAINTAINED_HERE -> listOf(NOT_SENDING, null, null, emptyList<String>(), emptyList<String>())
                PublishState.PENDING -> listOf("Not published yet.", null, null, emptyList<String>(), emptyList<String>())
                PublishState.OUTCOME_UNKNOWN -> listOf(OUTCOME_UNKNOWN, null, null, emptyList<String>(), emptyList<String>())
                PublishState.ERROR -> listOf(null, null, null, listOf(WRITE_FORBIDDEN), emptyList<String>())
                PublishState.PUBLISHED -> listOf(
                    null, "Published UP · at 20", "Home Assistant shows UP, which matches ServiceTag · checked at 20",
                    emptyList<String>(), emptyList<String>(),
                )
                PublishState.MATCHES -> listOf(
                    null, null, "Home Assistant shows UP, which matches ServiceTag · checked at 30",
                    emptyList<String>(), emptyList<String>(),
                )
                // The date kind's quiet state (B6b's P114-39): a status binding never reaches it, and draws no line.
                PublishState.INVALIDATED_INELIGIBLE -> listOf(null, null, null, emptyList<String>(), emptyList<String>())
            }
        }
        for (state in PublishState.entries) {
            val delivery = when (state) {
                PublishState.ERROR -> erred(PublishErrorKind.WRITE_FORBIDDEN)
                PublishState.PUBLISHED -> published
                PublishState.MATCHES -> matching
                PublishState.NOT_ENABLED,
                PublishState.COPIED_INSTALLATION,
                PublishState.NEEDS_VALIDATION,
                PublishState.NEEDS_TOKEN,
                PublishState.WAITING_FOR_SOURCE,
                PublishState.NOT_MAINTAINED_HERE,
                PublishState.PENDING,
                PublishState.OUTCOME_UNKNOWN,
                PublishState.INVALIDATED_INELIGIBLE,
                -> pending
            }
            val shown = if (state == PublishState.NOT_ENABLED) {
                publishBlockOf(
                    PublishKind.EQUIPMENT_STATUS, StatusWords,
                    writable.copy(binding = binding.copy(enabled = false), delivery = delivery, state = state),
                ) { "at $it" }
            } else {
                block(state, delivery)
            }
            assertEquals(state.name, expected.getValue(state), drawn(shown))
        }
    }

    @Test fun pendingAfterASuccessSaysWhatItSendsAndNoConnectionSaysSo() {
        val later = published.copy(desiredValue = "DOWN", desiredRevision = 8)
        assertEquals("Sending DOWN…", block(PublishState.PENDING, later).stateLine)
        // No connection stored: P16-10, not the lost-token sentence.
        assertEquals(NOT_CONNECTED, block(PublishState.NEEDS_TOKEN, facts = writable.copy(connected = false)).stateLine)
    }

    @Test fun publishedAndVerifiedAreTwoLines() {
        val shown = block(PublishState.PUBLISHED, published)
        assertEquals("Published UP · at 20", shown.publishedLine)
        assertEquals("Home Assistant shows UP, which matches ServiceTag · checked at 20", shown.verifiedLine)

        // MATCHES is Home Assistant's own value read back, never drawn as published.
        val matched = block(PublishState.MATCHES, matching)
        assertNull(matched.publishedLine)
        assertFalse(drawn(matched).toString().contains("Published"))

        // A new value waiting to go: the old publication stays a fact; "matches ServiceTag" is not said of it.
        val moved = published.copy(desiredValue = "DOWN", desiredRevision = 8)
        val waiting = block(PublishState.PENDING, moved)
        assertEquals("Published UP · at 20", waiting.publishedLine)
        assertNull(waiting.verifiedLine)
    }

    @Test fun eachPublishErrorKindHasItsSentence() {
        val expected: Map<PublishErrorKind, List<String>> = PublishErrorKind.entries.associateWith { kind ->
            when (kind) {
                PublishErrorKind.ENDPOINT_REFUSED -> listOf(ADDRESS_NOT_ALLOWED)
                PublishErrorKind.DENIED -> listOf(NETWORK_NOT_ALLOWED)
                PublishErrorKind.UNREACHABLE -> listOf(COULD_NOT_REACH)
                PublishErrorKind.TIMED_OUT -> listOf("Home Assistant took too long to answer.")
                PublishErrorKind.AUTH_REFUSED -> listOf("Home Assistant refused the access token.")
                PublishErrorKind.REDIRECTED -> listOf(
                    "Home Assistant answered with a redirect, which ServiceTag does not follow. Enter the address it " +
                        "redirects to.",
                )
                PublishErrorKind.HTTP_ERROR -> listOf("Home Assistant answered 404.")
                PublishErrorKind.MALFORMED -> listOf("Home Assistant sent an answer ServiceTag cannot read.")
                PublishErrorKind.NEEDS_TOKEN -> listOf(ENTER_TOKEN_AGAIN)
                PublishErrorKind.NOT_ON_LOCAL_NETWORK -> listOf(NOT_ON_HOME_WIFI)
                PublishErrorKind.NAME_NOT_LOCAL -> listOf(NAME_NOT_PRIVATE)
                PublishErrorKind.TLS_FAILED -> listOf(CERTIFICATE_NOT_VERIFIED)
                PublishErrorKind.ENTITY_NOT_FOUND -> listOf("Home Assistant has no entity $STATUS.")
                PublishErrorKind.WRITE_FORBIDDEN -> listOf(WRITE_FORBIDDEN)
                PublishErrorKind.WRONG_DOMAIN -> listOf("$STATUS is not a dropdown helper (input_select).")
                PublishErrorKind.OPTIONS_MISMATCH, PublishErrorKind.NOT_DATE_ONLY -> listOf(OPTIONS_MISMATCH)
                PublishErrorKind.SERVICE_REFUSED -> listOf("Home Assistant refused the value UP for $STATUS.")
                PublishErrorKind.OUTCOME_UNKNOWN -> listOf(OUTCOME_UNKNOWN)
                PublishErrorKind.REMOTE_DIFFERS ->
                    listOf("Home Assistant shows DOWN after the change; something else may be setting $STATUS.")
                PublishErrorKind.COPIED_INSTALLATION -> listOf(COPIED)
            }
        }
        for (kind in PublishErrorKind.entries) {
            val detail = mapOf(PublishErrorKind.HTTP_ERROR to "404", PublishErrorKind.SERVICE_REFUSED to "UP")[kind]
            val lines = publishErrorLines(kind, detail, STATUS, StatusWords, remoteValue = "DOWN")
            assertEquals(kind.name, expected.getValue(kind), lines)
        }
        // #16's network cause, when the gate named one: P16-77 and the cause's own remedy.
        assertEquals(
            listOf(
                "ServiceTag could not confirm that this phone is on your home Wi-Fi, so it did not contact Home Assistant.",
                "Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again.",
            ),
            publishErrorLines(PublishErrorKind.NOT_ON_LOCAL_NETWORK, UnconfirmedCause.LOCATION_OFF.name, STATUS, StatusWords),
        )
    }

    /** B4b2 review N4: a hand-off away from home records WAIT; the block reports where the phone was, not a fault. */
    @Test fun aWaitErrorIsAFactNotAFault() {
        val away = block(PublishState.ERROR, erred(PublishErrorKind.NOT_ON_LOCAL_NETWORK))
        assertEquals(listOf(NOT_ON_HOME_WIFI), away.factLines)
        assertEquals(emptyList<String>(), away.faultLines)
        val denied = block(PublishState.ERROR, erred(PublishErrorKind.DENIED))
        assertEquals(listOf(NETWORK_NOT_ALLOWED), denied.factLines)
        // A NEEDS_OWNER and a TRANSIENT error are faults.
        assertEquals(listOf(WRITE_FORBIDDEN), block(PublishState.ERROR, erred(PublishErrorKind.WRITE_FORBIDDEN)).faultLines)
        assertEquals(
            listOf(COULD_NOT_REACH),
            block(PublishState.ERROR, erred(PublishErrorKind.UNREACHABLE)).faultLines,
        )
    }

    @Test fun aValidatorFailureDrawsCheckAgainBesideItsReason() {
        val shown = block(PublishState.NEEDS_VALIDATION, erred(PublishErrorKind.OPTIONS_MISMATCH))
        assertEquals(NEEDS_VALIDATION, shown.stateLine)
        assertEquals(listOf(OPTIONS_MISMATCH), shown.faultLines)
        assertTrue(shown.offersCheck)
        assertFalse(shown.offersPublishNow)
    }

    @Test fun everyPublishStateAndRefusalHasASentence() {
        val expected: Map<PublishRefusal, String?> = PublishRefusal.entries.associateWith { reason ->
            when (reason) {
                PublishRefusal.BAD_ENTITY_ID -> "Choose a helper first."
                PublishRefusal.WRONG_DOMAIN -> "$STATUS is not a dropdown helper (input_select)."
                PublishRefusal.OPTIONS_MISMATCH, PublishRefusal.NOT_DATE_ONLY -> OPTIONS_MISMATCH
                PublishRefusal.NO_CONNECTION -> NOT_CONNECTED
                PublishRefusal.NOT_MAINTAINED_HERE ->
                    "Example Heater is archived, retired or transferred out, so it cannot publish to Home Assistant."
                PublishRefusal.TARGET_IN_USE -> "$STATUS is already set by another asset on this phone."
                PublishRefusal.INBOUND_SOURCE ->
                    "$STATUS is the helper this asset's season follows. Choose another: ServiceTag never sets a helper it reads."
                PublishRefusal.STALE_READ -> "The helper changed while it was being checked. Check it again."
                // A status binding names no season-ended helper: the date kind's P114-48 is B6b's.
                PublishRefusal.BOUNDARY_IS_TARGET -> null
                PublishRefusal.STALE_PLAN -> "Something changed since this helper was checked. Check it again."
            }
        }
        for (reason in PublishRefusal.entries) {
            assertEquals(reason.name, expected.getValue(reason), publishRefusalLine(reason, STATUS, "Example Heater", StatusWords))
        }
        // Every state an enabled status binding reaches draws something: a line, a published or verified line, or a fault.
        val reached = PublishState.entries - PublishState.NOT_ENABLED - PublishState.INVALIDATED_INELIGIBLE
        for (state in reached) {
            val delivery = mapOf(
                PublishState.ERROR to erred(PublishErrorKind.UNREACHABLE),
                PublishState.PUBLISHED to published,
                PublishState.MATCHES to matching,
            )[state] ?: pending
            val shown = block(state, delivery)
            assertTrue(state.name, listOfNotNull(shown.stateLine, shown.publishedLine, shown.verifiedLine).isNotEmpty() || shown.faultLines.isNotEmpty())
        }
    }

    @Test fun theStatusBlockShowsItsDestinationMappingAndLimitLine() {
        val shown = block(PublishState.PUBLISHED, published)
        assertEquals("Publish equipment status to Home Assistant", shown.title)
        assertEquals(STATUS, shown.destination)
        assertEquals(
            listOf(PublishMappingRow("Operational", "UP"), PublishMappingRow("Degraded", "DEGRADED"), PublishMappingRow("Down", "DOWN")),
            shown.mapping,
        )
        assertEquals(
            "Sent when the phone can: on opening, on Publish now, or in the background where allowed. It is not instant.",
            shown.limitLine,
        )
        // No binding: the switch is offered only where a binding could be set up.
        val none = publishBlockOf(PublishKind.EQUIPMENT_STATUS, StatusWords, writable) { "at $it" }
        assertTrue(none.drawn && none.offersSetUp && !none.enabled)
        val held = publishBlockOf(PublishKind.EQUIPMENT_STATUS, StatusWords, writable.copy(writable = false)) { "at $it" }
        assertFalse(held.drawn)
        // The date kind has its own words (B6b): its block is offered where a binding could be set up.
        val date = publishBlockOf(PublishKind.SEASON_SERVICE_DATE, publishWordsOf(PublishKind.SEASON_SERVICE_DATE), writable) { "at $it" }
        assertTrue(date.drawn && date.offersSetUp)
        assertEquals("Publish Season Start date to Home Assistant", date.title)
    }

    // --- the model over the graph: every control through PublishControls ----------------------------------------------

    private class Open(val model: PublishBlockViewModel) {
        val state: PublishBlockState get() = model.state.value
    }

    private fun TestScope.open(kind: PublishKind = PublishKind.EQUIPMENT_STATUS): Open {
        val model = PublishBlockViewModel(
            heater, kind, graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
            graph.secretStore, graph.assets, graph.transferRecords, graph.installationIdentity::id, graph.uow,
            graph.publishControls, graph.publishRunner::validate,
            DateMeaning.of(graph.openingServiceReader, graph.events, graph.todayPort) { ZoneOffset.UTC }, emptyFlow(),
        ) { ZoneOffset.UTC }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        advanceUntilIdle()
        return Open(model)
    }

    /** The heater, the fictional connection, its status helper in Home Assistant, and the status binding enabled. */
    private suspend fun TestScope.enabled(): PublishBinding {
        graph.assets.upsert(assetRow(heater.value, "Example Heater"))
        val connection = graph.saveHaConnection.run(
            ADDRESS, Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI, null,
        )
        advanceUntilIdle()
        graph.haPublish.helpers[STATUS] = RemoteEntity(STATUS, "unknown", listOf("UP", "DEGRADED", "DOWN"), null, null)
        val fresh = ValidationResult(
            STATUS, graph.haPublish.helpers.getValue(STATUS), connection.id, connection.configGeneration, 0, graph.now,
        )
        return graph.enablePublishing.run(heater, PublishKind.EQUIPMENT_STATUS, STATUS, fresh, null, graph.installationIdentity.id())
    }

    @Test fun publishNowGoesThroughTheControlsAndReadsTheHelperBack() = runTest(scheduler) {
        val binding = enabled()
        graph.recordCondition.run(heater, ConditionCommand(OperationalCondition.DEGRADED, tzId = "UTC"))
        // The projector observed the condition (B4b1's observer, not started on this graph).
        graph.publishProjector.reconcileDesired(graph.today)
        val block = open()

        block.model.publishNow()
        advanceUntilIdle()
        graph.awaitDispatched()
        advanceUntilIdle()

        // The controls' Publish now stamped the request, projected and ran the pass: Home Assistant shows DEGRADED.
        assertNotNull(graph.publishDeliveries.get(binding.id)?.lastRequestId)
        assertEquals("DEGRADED", graph.haPublish.state(STATUS))
        block.model.onResumed()
        advanceUntilIdle()
        assertTrue(block.state.publishedLine.orEmpty(), block.state.publishedLine.orEmpty().startsWith("Published DEGRADED · "))
    }

    @Test fun stopAsksP113_14ThenDisablesThroughTheControls() = runTest(scheduler) {
        val binding = enabled()
        val block = open()
        assertTrue(block.state.enabled && block.state.offersStop)
        val before = graph.deliveryRequests.get()

        block.model.askStop()
        assertEquals("Stop publishing to $STATUS? Home Assistant keeps the value it has now.", block.state.stopAsk)
        assertTrue("nothing is written before the confirm", graph.publishBindings.get(binding.id)!!.enabled)

        block.model.confirmStop()
        advanceUntilIdle()
        assertFalse(graph.publishBindings.get(binding.id)!!.enabled)
        assertTrue("the control asked for delivery (A03)", graph.deliveryRequests.get() > before)
        assertNull(block.state.stopAsk)
        assertFalse(block.state.enabled)
    }

    @Test fun checkHelperRevalidatesTheStoredHelperAndAFailedReadHasItsSentence() = runTest(scheduler) {
        val binding = enabled()
        // Someone re-optioned the helper in Home Assistant: the fresh read records it, and the block asks for a check.
        graph.haPublish.helpers[STATUS] = RemoteEntity(STATUS, "UP", listOf("UP", "DOWN"), null, null)
        val block = open()
        block.model.checkHelper()
        advanceUntilIdle()
        assertEquals(PublishErrorKind.OPTIONS_MISMATCH, graph.publishDeliveries.get(binding.id)?.errorKind)
        assertEquals(NEEDS_VALIDATION, block.state.stateLine)
        assertEquals(listOf(OPTIONS_MISMATCH), block.state.faultLines)

        // Home Assistant does not answer: the read's own sentence, and nothing recorded.
        graph.haPublish.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        block.model.checkHelper()
        advanceUntilIdle()
        assertEquals(listOf(COULD_NOT_REACH), block.state.notices)
    }

    // --- #114, row 88's date half (B6b): the date words, the F-16 rule, the obligations and the owner's dismissal -------

    private val dateBinding = binding.copy(id = "binding-2", kind = PublishKind.SEASON_SERVICE_DATE, entityId = DATE)

    private fun dateBlock(state: PublishState, delivery: PublishDelivery, facts: PublishBlockFacts = writable) =
        publishBlockOf(
            PublishKind.SEASON_SERVICE_DATE, DateWords,
            facts.copy(binding = dateBinding, delivery = delivery.copy(bindingId = dateBinding.id), state = state),
        ) { "at $it" }

    /** ServiceTag wants 1970-01-01 at the pair `(4, 7)`: nothing read back yet. */
    private val invalidating = pending.copy(desiredValue = "1970-01-01")

    @Test fun theDateKindHasItsOwnSentences() {
        assertEquals(DateWords, publishWordsOf(PublishKind.SEASON_SERVICE_DATE))
        assertEquals("Publish Season Start date to Home Assistant", DateWords.title)
        assertEquals("Date helpers (input_datetime)", DateWords.scopeLine)
        assertEquals(
            "Publish Example Heater's Season Start date to $DATE? While Example Heater is ready for the season, " +
                "ServiceTag sets the service date; otherwise it sets 1970-01-01, which means not ready. Home Assistant " +
                "automations should read it and never set it.",
            DateWords.confirm("Example Heater", DATE),
        )
        assertEquals(
            "Stop publishing to $DATE? ServiceTag first sets it to 1970-01-01, so Home Assistant stops treating the old " +
                "date as a service.",
            DateWords.stopConfirm(DATE),
        )
        val refusals: Map<PublishRefusal, String?> = mapOf(
            PublishRefusal.WRONG_DOMAIN to "$DATE is not a date helper (input_datetime).",
            PublishRefusal.NOT_DATE_ONLY to "$DATE must be a date helper with a date and no time.",
            PublishRefusal.BOUNDARY_IS_TARGET to
                "$DATE is the helper ServiceTag sets. Choose the date helper Home Assistant stamps when the season ends.",
        )
        for ((reason, line) in refusals) assertEquals(reason.name, line, publishRefusalLine(reason, DATE, "Example Heater", DateWords))
        // Every refusal has a date sentence (F-16), the boundary's included.
        for (reason in PublishRefusal.entries) assertNotNull(reason.name, publishRefusalLine(reason, DATE, "Example Heater", DateWords))
        assertEquals(
            listOf("$DATE must be a date helper with a date and no time."),
            publishErrorLines(PublishErrorKind.NOT_DATE_ONLY, null, DATE, DateWords),
        )
        // INVALIDATED_INELIGIBLE: 1970-01-01 read back, the quiet state's own sentence (P114-39).
        val quiet = invalidating.copy(
            lastVerifiedValue = "1970-01-01", lastVerifiedAt = 40L, verifiedBindingRevision = 4, verifiedDesiredRevision = 7,
        )
        assertEquals(
            "Cleared in Home Assistant: $DATE is 1970-01-01. Nothing more is sent while the asset is archived, retired " +
                "or transferred out.",
            dateBlock(PublishState.INVALIDATED_INELIGIBLE, quiet).stateLine,
        )
        // Every state an enabled date binding reaches draws something.
        val reached = PublishState.entries - PublishState.NOT_ENABLED - PublishState.WAITING_FOR_SOURCE -
            PublishState.NOT_MAINTAINED_HERE
        for (state in reached) {
            val delivery = mapOf(
                PublishState.ERROR to erred(PublishErrorKind.UNREACHABLE),
                PublishState.PUBLISHED to published,
                PublishState.MATCHES to matching,
                PublishState.INVALIDATED_INELIGIBLE to quiet,
            )[state] ?: pending
            val shown = dateBlock(state, delivery)
            assertTrue(state.name, listOfNotNull(shown.stateLine, shown.publishedLine, shown.verifiedLine).isNotEmpty() || shown.faultLines.isNotEmpty())
        }
    }

    @Test fun anEnabledDateBindingFailingItsInvalidationDrawsMayStillAllowStarts() {
        // Not yet read back, Home Assistant out of reach: P114-36 beside the error's own sentence.
        val transient = dateBlock(PublishState.ERROR, invalidating.copy(errorKind = PublishErrorKind.UNREACHABLE, errorAt = 9L, errorBindingRevision = 4, errorDesiredRevision = 7))
        assertEquals(listOf(COULD_NOT_REACH), transient.faultLines)
        assertEquals(NOT_YET_CLEARED, transient.invalidationLine)
        // Pending with no error yet: still not cleared, so still said.
        assertEquals(NOT_YET_CLEARED, dateBlock(PublishState.PENDING, invalidating).invalidationLine)
        // An error the owner must act on: P114-37.
        val owner = dateBlock(PublishState.ERROR, invalidating.copy(errorKind = PublishErrorKind.AUTH_REFUSED, errorAt = 9L, errorBindingRevision = 4, errorDesiredRevision = 7))
        assertEquals(CANNOT_REACH, owner.invalidationLine)
        // Read back at the current pair: nothing more to say.
        val cleared = invalidating.copy(
            lastVerifiedValue = "1970-01-01", lastVerifiedAt = 40L, verifiedBindingRevision = 4, verifiedDesiredRevision = 7,
        )
        assertNull(dateBlock(PublishState.MATCHES, cleared).invalidationLine)
        // A service date being sent is not an invalidation; a status binding never draws it.
        assertNull(dateBlock(PublishState.PENDING, pending.copy(desiredValue = "2026-09-15")).invalidationLine)
        assertNull(block(PublishState.PENDING, invalidating).invalidationLine)
    }

    /** The heater's date binding, enabled on this phone over the fictional connection. */
    private suspend fun TestScope.enabledDate(): PublishBinding {
        graph.assets.upsert(assetRow(heater.value, "Example Heater"))
        val connection = graph.saveHaConnection.run(
            ADDRESS, Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI, null,
        )
        advanceUntilIdle()
        graph.haPublish.helpers[DATE] = RemoteEntity(DATE, "2025-11-15", null, true, false)
        val fresh = ValidationResult(DATE, graph.haPublish.helpers.getValue(DATE), connection.id, connection.configGeneration, 0, graph.now)
        return graph.enablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE, DATE, fresh, null, graph.installationIdentity.id())
    }

    private suspend fun owe(id: String, entityId: String, assetId: String?, state: DecommissionState, at: Long) = graph.uow.write {
        graph.publishDecommissions.upsertMerging(
            DecommissionTombstone(
                id, graph.haConnections.get()?.id ?: "connection-gone", entityId, assetId?.let(::AssetId),
                graph.installationIdentity.id(), state, at, null, null, 1,
            ),
        )
    }

    @Test fun theDateBlockDrawsItsDestinationAndTheInvalidDatesMeaning() = runTest(scheduler) {
        enabledDate()
        val block = open(PublishKind.SEASON_SERVICE_DATE)
        assertTrue(block.state.drawn && block.state.enabled)
        assertEquals("Publish Season Start date to Home Assistant", block.state.title)
        assertEquals(DATE, block.state.destination)
        // No Season Start service covers the heater: C9 says not ready, which publishes 1970-01-01 (read through the
        // one readiness reader, in the card's own words).
        assertEquals(listOf(PublishMappingRow("Not ready: no Season Start service", "1970-01-01")), block.state.mapping)
    }

    @Test fun aTombstoneIsDrawnOnItsAssetOrTheHaScreen() = runTest(scheduler) {
        enabledDate()
        graph.assets.upsert(assetRow("example-boiler", "Example Boiler"))
        owe("decommission-1", "input_datetime.example_heater_old_done", heater.value, DecommissionState.PENDING, 10L)
        owe("decommission-2", "input_datetime.example_heater_older_done", heater.value, DecommissionState.UNDELIVERABLE, 20L)
        owe("decommission-3", "input_datetime.example_boiler_service_done", "example-boiler", DecommissionState.PENDING, 30L)
        owe("decommission-4", "input_datetime.example_gone_service_done", "example-gone", DecommissionState.UNDELIVERABLE, 40L)
        val block = open(PublishKind.SEASON_SERVICE_DATE)
        assertEquals(
            listOf(
                PublishTombstoneLine(
                    "decommission-1",
                    "Not yet cleared in Home Assistant: input_datetime.example_heater_old_done may still allow starts. " +
                        "ServiceTag keeps trying; if this phone cannot reach Home Assistant, set it to 1970-01-01 there.",
                    dismissible = false,
                ),
                PublishTombstoneLine(
                    "decommission-2",
                    "ServiceTag can no longer reach input_datetime.example_heater_older_done. It may still allow starts: " +
                        "set it to 1970-01-01 in Home Assistant.",
                    dismissible = true,
                ),
            ),
            block.state.tombstones,
        )
        // The status block draws no obligation; the orphan is the Home Assistant screen's.
        assertTrue(open().state.tombstones.isEmpty())
        val screen = com.loosecannon.servicetag.ui.homeassistant.HaPublishingReads(
            graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.assets, graph.haConnections, graph.uow,
        ).read()
        assertEquals(listOf("decommission-4"), screen.orphanTombstones.map { it.id })

        // Forget leaves the heater's obligations behind, its own helper's among them: the block still draws them with no
        // connection and no binding.
        graph.forgetHaConnection.run()
        advanceUntilIdle()
        block.model.onResumed()
        advanceUntilIdle()
        assertTrue(block.state.drawn)
        assertEquals(
            listOf("input_datetime.example_heater_old_done", "input_datetime.example_heater_older_done", DATE),
            graph.publishDecommissions.all().filter { it.assetId == heater }.sortedBy { it.requestedAt }.map { it.entityId },
        )
        assertEquals(3, block.state.tombstones.size)
        assertTrue(block.state.tombstones.all { it.dismissible })
    }

    @Test fun theOwnersDismissalClearsAnUndeliverableObligationAndARaceRedrawsWithNoSentence() = runTest(scheduler) {
        enabledDate()
        owe("decommission-1", "input_datetime.example_heater_old_done", heater.value, DecommissionState.UNDELIVERABLE, 10L)
        owe("decommission-2", "input_datetime.example_heater_older_done", heater.value, DecommissionState.PENDING, 20L)
        val block = open(PublishKind.SEASON_SERVICE_DATE)
        assertEquals(listOf(true, false), block.state.tombstones.map { it.dismissible })

        // A row that raced back to PENDING, or one already gone: refused, re-read, no sentence (C25a).
        block.model.dismissTombstone("decommission-2")
        advanceUntilIdle()
        block.model.dismissTombstone("decommission-9")
        advanceUntilIdle()
        assertNotNull(graph.publishDecommissions.get("decommission-2"))
        assertEquals(emptyList<String>(), block.state.notices)
        assertEquals(listOf("decommission-1", "decommission-2"), block.state.tombstones.map { it.id })

        // The owner's word lets the UNDELIVERABLE one go, through the controls.
        val before = graph.deliveryRequests.get()
        block.model.dismissTombstone("decommission-1")
        advanceUntilIdle()
        assertNull("the owner's word lets the obligation go", graph.publishDecommissions.get("decommission-1"))
        assertFalse("decommission-1" in block.state.tombstones.map { it.id })
        assertTrue("the control asked for delivery (A03)", graph.deliveryRequests.get() > before)
    }

    private companion object {
        const val STATUS = "input_select.example_heater_equipment_status"
        const val DATE = "input_datetime.example_heater_service_done"
        const val NOT_YET_CLEARED = "Not yet cleared in Home Assistant: $DATE may still allow starts. ServiceTag keeps " +
            "trying; if this phone cannot reach Home Assistant, set it to 1970-01-01 there."
        const val CANNOT_REACH =
            "ServiceTag can no longer reach $DATE. It may still allow starts: set it to 1970-01-01 in Home Assistant."
        const val ADDRESS = "http://192.168.0.10:8123"
        const val HOME_WIFI = "ExampleHomeWifi"

        const val COPIED = "This setup came from another phone's backup, so this phone does not publish. Check the helper " +
            "and turn publishing on again here."
        const val NEEDS_VALIDATION = "Check the helper again before ServiceTag publishes to it."
        const val ENTER_TOKEN_AGAIN = "Enter the access token again: this phone no longer has it."
        const val NOT_CONNECTED = "Not connected. Enter the server address and an access token."
        const val WAITING = "Waiting: no equipment status is recorded, so nothing is sent."
        const val NOT_SENDING =
            "Not sending: the asset is archived, retired or transferred out. Home Assistant keeps its last value."
        const val OUTCOME_UNKNOWN =
            "Home Assistant did not confirm the change. ServiceTag will check the helper before sending again."
        const val WRITE_FORBIDDEN = "Home Assistant refused this change for the access token's user. That user needs " +
            "permission to change helpers."
        const val OPTIONS_MISMATCH = "$STATUS must be a dropdown helper whose options are exactly UP, DOWN and DEGRADED."
        const val ADDRESS_NOT_ALLOWED = "That address is not allowed. Use https:// with a server name or private IPv4 " +
            "address, or http:// with a private IPv4 address."
        const val NETWORK_NOT_ALLOWED = "ServiceTag is not allowed to use the network. Allow network access in the app " +
            "settings, then close ServiceTag and open it again."
        const val NOT_ON_HOME_WIFI = "This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant."
        const val NAME_NOT_PRIVATE = "That server name does not resolve only to private network addresses, so " +
            "ServiceTag did not contact it."
        const val CERTIFICATE_NOT_VERIFIED = "Home Assistant's certificate could not be verified."
        const val COULD_NOT_REACH = "Could not reach Home Assistant. Check that this phone is on the same network."
    }
}
