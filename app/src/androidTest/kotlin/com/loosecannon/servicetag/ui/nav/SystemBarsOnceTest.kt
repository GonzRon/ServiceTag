package com.loosecannon.servicetag.ui.nav

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #121 — every route sees each system bar once (§3.1), on the **real** root: `ServiceTagRoot` composed as
 * `FormCompletionNavigationTest` composes it, each route reached by emitting it on `deepLinks`.
 *
 * The root pads its `NavDisplay` with the outer `Scaffold`'s padding and consumes it, so a screen's own `Scaffold`
 * and app bar do not apply the bars a second time. Before that (1.8.1) a pushed screen's content began one status bar
 * too low (B1) and ended one navigation bar too high (B2), and a top-level screen's list stopped one navigation bar
 * above the bottom bar, which already holds it (B3). A form's own `Scaffold` adds the keyboard (`formContentInsets`), so
 * with a field focused and the keyboard shown the form ends at the keyboard's top (B4). Each case reads the window's own
 * insets from the activity's root view, so the numbers are the emulator's, not this file's.
 *
 * A column only reaches the bottom of the space it has when its content is taller than that space (F-1), so the two
 * fixtures overflow, and each case asserts so before it measures: B1, B2 and B4 open the entry form of an asset "Bench"
 * whose profile carries [READINGS] readings; B3 lists [CRATES] assets "Crate 01" onward. Everything is fictional.
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsOnceTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    @Before fun freshInstall() = clearInstall()

    /**
     * B1: a pushed route's content starts under the top inset (system bars ∪ display cutout, F-12) once, plus its
     * 64 dp app bar. 1.8.1 counted the status bar twice: one inset lower.
     */
    @Test fun aPushedFormStartsUnderTheTopBarsOnce() {
        openTheBenchForm()
        val column = theOverflowingScrollNode()
        val top = windowInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
        val appBar = with(rule.density) { APP_BAR_HEIGHT.toPx() }

        assertPx("the form's top: the top inset ($top px) once, then the app bar", top + appBar, column.boundsInRoot.top)
    }

    /**
     * B2: the same form, keyboard down, ends over the navigation bar once: the root's height less that inset.
     * 1.8.1 counted the navigation bar twice: one inset higher.
     */
    @Test fun aPushedFormEndsOverTheNavigationBarOnce() {
        openTheBenchForm()
        rule.waitUntil("the keyboard down", IME_TIMEOUT_MS) { windowInsets(WindowInsetsCompat.Type.ime()).bottom == 0 }
        val column = theOverflowingScrollNode()
        val navigation = windowInsets(WindowInsetsCompat.Type.navigationBars()).bottom

        assertPx(
            "the form's bottom: the root's height less the navigation bar ($navigation px) once",
            rootHeight() - navigation,
            column.boundsInRoot.bottom,
        )
    }

    /**
     * B4 (F-15): the same form, a field focused and the keyboard shown, ends at the keyboard's top: the root's height
     * less the keyboard's bottom inset. Without the form's `contentWindowInsets` nothing adds the keyboard, and the form
     * still ends over the navigation bar (B2's line), under the keyboard. The keyboard not showing within
     * [IME_TIMEOUT_MS] fails the case.
     */
    @Test fun aPushedFormEndsAtTheKeyboardsTop() {
        openTheBenchForm()
        theOverflowingScrollNode()
        rule.onNodeWithTag(FIRST_VALUE).performClick()
        rule.runOnUiThread {
            val window = rule.activity.window
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil("the keyboard's bottom inset above 0", IME_TIMEOUT_MS) {
            windowInsets(WindowInsetsCompat.Type.ime()).bottom > 0
        }
        val bottom = settledBottom()
        val keyboard = windowInsets(WindowInsetsCompat.Type.ime()).bottom

        assertPx("the form's bottom: the root's height less the keyboard ($keyboard px)", rootHeight() - keyboard, bottom)
    }

    /**
     * B3: a top-level route's list ends at the bottom bar's top edge. 1.8.1's inner `Scaffold` re-applied the
     * navigation bar above the bottom bar that already contains it: a gap of one navigation bar (F-12).
     */
    @Test fun aTopLevelListEndsAtTheBottomBar() {
        seedTheCrates()
        open(Route.Assets)
        rule.awaitText(crate(1))
        rule.waitUntil("the keyboard down", IME_TIMEOUT_MS) { windowInsets(WindowInsetsCompat.Type.ime()).bottom == 0 }
        val list = theOverflowingScrollNode()

        assertPx("the list's bottom: the bottom bar's top edge", bottomBarTop(), list.boundsInRoot.bottom)
    }

    // ------------------------------------------------------------------ driving

    /** The real root, with [route] emitted on `deepLinks` once the dashboard is up. */
    private fun open(route: Route) {
        val graph = app.graph
        rule.setContent { ServiceTagTheme { ServiceTagRoot(graph = graph, deepLinks = deepLinks, snackbars = snackbars) } }
        rule.awaitText("ServiceTag")
        deepLinks.tryEmit(route)
    }

    /** The Bench's entry form on its profile, waited for until its rows have loaded (its button is enabled). */
    private fun openTheBenchForm() {
        val (bench, profile) = seedTheBench()
        open(Route.EventEntry(bench, profile, null))
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText(RECORD_ENTRY) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The screen's one vertically scrolling node, once the screen is idle, asserted to overflow first (F-1). */
    private fun theOverflowingScrollNode(): SemanticsNode {
        rule.waitForIdle()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
        assertEquals("vertically scrolling nodes on the screen", 1, nodes.size)
        val node = nodes.single()
        val max = node.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
        assertTrue("the fixture must overflow its viewport (F-1): verticalScrollAxisRange.maxValue = $max", max > 0f)
        return node
    }

    /**
     * The overflowing scroll node's bottom once the keyboard's animation has landed: two reads [SETTLE_MS] apart
     * agree, within [IME_TIMEOUT_MS].
     */
    private fun settledBottom(): Float {
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        var last = Float.NaN
        while (true) {
            val bottom = theOverflowingScrollNode().boundsInRoot.bottom
            if (bottom == last) return bottom
            check(SystemClock.uptimeMillis() < deadline) { "the form's bottom never settled: $last, then $bottom" }
            last = bottom
            Thread.sleep(SETTLE_MS)
        }
    }

    /** The top edge of the bottom bar: the row that groups its destinations, each a tab. */
    private fun bottomBarTop(): Float {
        val tabs = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertTrue("the bottom bar's destinations are on screen", tabs.isNotEmpty())
        return tabs.map { checkNotNull(it.parent) { "a destination with no parent" }.boundsInRoot.top }.distinct().single()
    }

    private fun rootHeight(): Float = rule.onRoot().fetchSemanticsNode().boundsInRoot.height

    /** The window's insets of [types], read from the activity's root view on the main thread. */
    private fun windowInsets(types: Int): Insets {
        var insets: Insets? = null
        rule.runOnUiThread { insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.getInsets(types) }
        return checkNotNull(insets) { "the activity's root view has no window insets" }
    }

    private fun assertPx(what: String, expected: Float, actual: Float) =
        assertEquals(what, expected.toDouble(), actual.toDouble(), TOLERANCE_PX)

    // ------------------------------------------------------------------ fixtures

    /** "Bench", and its profile "Bench check" carrying [READINGS] optional readings. Returns the two ids. */
    private fun seedTheBench(): Pair<String, String> = runBlocking {
        val graph = app.graph
        val bench = graph.createAsset.run(AssetCommand(name = BENCH, category = "Workshop")).id
        val readings = (1..READINGS).map { n ->
            graph.saveDefinition.run(
                null,
                DefinitionCommand(
                    assetId = bench,
                    key = "reading_$n",
                    label = "Reading $n",
                    unit = "mm",
                    kind = DefinitionKind.ENTERED,
                    valueType = ValueType.NUMBER,
                    decimals = 0,
                    rangeLow = null,
                    rangeHigh = null,
                    isMeter = false,
                    formula = null,
                    sourceA = null,
                    sourceB = null,
                ),
            )
        }
        val profile = graph.saveProfile.run(
            null,
            ProfileCommand(
                assetId = bench,
                name = "Bench check",
                eventKind = EventKind.MAINTENANCE,
                defaultTitle = "",
                fields = readings.map { ProfileFieldInput(it.id, required = false) },
                consumables = emptyList(),
            ),
        )
        bench.value to profile.id.value
    }

    private fun seedTheCrates() = runBlocking {
        (1..CRATES).forEach { n -> app.graph.createAsset.run(AssetCommand(name = crate(n), category = "Storage")) }
    }

    private fun crate(n: Int) = "Crate " + n.toString().padStart(2, '0')

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val TOLERANCE_PX = 1.0
        val APP_BAR_HEIGHT = 64.dp
        const val BENCH = "Bench"
        const val RECORD_ENTRY = "Record entry"

        /** The first reading's value field (`InstrumentEntryRow`'s `value-<key>` tag). */
        const val FIRST_VALUE = "value-reading_1"

        /** How long B4 waits for the keyboard's inset, and then for the form's bottom to settle. */
        const val IME_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 200L

        /** At least eight (F-1): the form overflows the viewport. */
        const val READINGS = 10

        /** At least 25 (F-1): the list overflows the viewport. */
        const val CRATES = 30
    }
}
