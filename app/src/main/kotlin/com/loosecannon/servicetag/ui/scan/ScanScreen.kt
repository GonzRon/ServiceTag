package com.loosecannon.servicetag.ui.scan

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nfc.ReaderMode
import com.loosecannon.servicetag.ui.nfc.TagSinkEffect
import com.loosecannon.servicetag.ui.theme.PlateShape
import com.loosecannon.servicetag.ui.theme.SheetSentence

/** The halo's full breath, in and out (D12 §11: "a very subtle slow breathing scale"). */
private const val BREATH_MILLIS = 3_200

/**
 * Foreground reader-mode scanning (D3 §9, G1 §1.4). The only `tertiaryContainer` surface in the
 * app sits here and the halo is the app's only animation; what a tag turns out to be is decided by
 * `ResolveTag` and shown on the result sheet.
 *
 * Reached as a pushed destination (Settings' Read / inspect tag row, or the dashboard's empty-state
 * action), never a tab (D12 §16 correction), so it always needs a way back.
 *
 * 2.7 (#37): the screen owns neither the reader-mode session — the nav shell holds one for the
 * whole tag flow — nor a route for its answer. The answer is drawn over this screen, so an inspect
 * never takes the screen out from under a tag that is still against the phone. 2.7.1 (#41) finishes
 * the thought: a tag already bound to an asset is *named* here rather than opened, so the one case
 * that still navigated by itself no longer does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    graph: AppGraph,
    readerMode: ReaderMode,
    onOpenAsset: (String) -> Unit,
    onNewAsset: () -> Unit,
    onWriteTag: (Route.WriteTag) -> Unit,
    onBack: () -> Unit,
) {
    val model: ScanViewModel = viewModel(key = "scan") { ScanViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()

    // The activity owns the one session; this screen only says where its tags land while it is
    // resumed. Leaving it takes the sink away, and the nav shell decides about NFC itself.
    TagSinkEffect(readerMode) { tag -> model.onTag(tag) }

    // The answer, as the two strings the route used to carry — which is all it ever carried, and
    // which `rememberSaveable` can keep through process death without a `Saver` of its own. The
    // counter is what tells one delivery from the next: the pair cannot, because reading the same
    // tag twice produces the same pair, and the sheet must not answer the second read with the
    // model the first one resolved.
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var answerKey by rememberSaveable { mutableStateOf("") }
    var readId by rememberSaveable { mutableStateOf(0) }
    val clearAnswer = { format = null; answerKey = "" }

    // Back dismisses the answer and leaves the user on READY TO SCAN, which is what one back press
    // did at 2.6 when the answer was its own entry and back popped it. The answer is composed here
    // now, so without this the nav shell's `onBack` would pop the inspect screen itself — a gesture
    // nobody ratified, and one that hands NFC back mid-look. Registered inside the entry's content,
    // so it is added to the dispatcher after the shell's callback and wins while it is enabled; it
    // disables itself the moment the answer is gone, leaving this screen's own back alone.
    BackHandler(enabled = format != null) { clearAnswer() }

    LaunchedEffect(model) {
        model.events.collect { event ->
            when (event) {
                is ScanEvent.Show -> {
                    format = event.route.format
                    answerKey = event.route.key
                    readId++
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Read / inspect tag") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReadyToScan(reading = state.reading)
            state.problem?.let { QuietLine(it) }
            NfcAvailability(readerMode)
        }
    }

    // Over this screen, not on top of it. Each way out clears the answer first, so coming back to
    // the inspector shows READY TO SCAN and not the answer to a tag that is long gone.
    //
    // The sheet resolves its own view model, and resolves it once. Drawn inside this entry it would
    // resolve against the entry's store, which lives until the entry is popped — so binding a tag,
    // coming back and reading it again would be answered by the first read's model, which still
    // thinks the tag is unassigned. One store per read is what the pushed result entry used to give.
    //
    // 2.7.1 (#41): `inspecting = true` is what makes this screen an inspector rather than a second
    // ambient trampoline. A tag already bound to an asset is named here and opened on a tap, so the
    // screen is never taken out from under a tag that is still against the phone — which is also
    // what keeps the activity's reader-mode hold alive for the whole look.
    format?.let { shown ->
        val owner = rememberReadScopedOwner(readId)
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            TagResultSheet(
                graph = graph,
                format = shown,
                key = answerKey,
                inspecting = true,
                onDismiss = clearAnswer,
                onWriteTag = { route -> clearAnswer(); onWriteTag(route) },
                onOpenAsset = { id -> clearAnswer(); onOpenAsset(id) },
                onNewAsset = { clearAnswer(); onNewAsset() },
            )
        }
    }
}

/**
 * A [ViewModelStoreOwner] that lives exactly as long as one read (2.7, #37).
 *
 * `TagResultSheet` is reused verbatim and resolves its own view model in `init`, which is fine when
 * each read is its own nav entry with its own store and wrong when every read shares the scan
 * screen's. A fresh store per [readId], cleared the moment that read is replaced or dismissed,
 * restores the old behaviour: `onCleared` runs, its `observeAll` subscription goes with it, and the
 * next read resolves the tag again instead of re-showing the previous answer.
 *
 * `internal` rather than private so the wiring is provable on a device with no NFC, which is the
 * only part of this a test can reach — see `ReadScopedSheetOwnerTest`.
 */
@Composable
internal fun rememberReadScopedOwner(readId: Int): ViewModelStoreOwner {
    val owner = remember(readId) { ReadScopedOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

private class ReadScopedOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** "READY TO SCAN": `tertiaryContainer`, the contactless glyph in a breathing halo, one sentence. */
@Composable
private fun ReadyToScan(reading: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = PlateShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
        ) {
            Halo()
            Text(
                text = if (reading) "READING TAG" else "READY TO SCAN",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Hold the top of your phone near the equipment tag.",
                style = SheetSentence,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A 64dp glyph inside a 96dp halo at 18% tertiary, breathing over 3.2 s. The breath stops when the
 * phone's animator scale is zero, which is how "reduce motion" reaches an app on Android.
 */
@Composable
private fun Halo() {
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val scale = if (animate) {
        val breath = rememberInfiniteTransition(label = "halo")
        val animated by breath.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(BREATH_MILLIS), RepeatMode.Reverse),
            label = "halo-scale",
        )
        animated
    } else {
        1f
    }
    Box(contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
            shape = CircleShape,
            modifier = Modifier.size(96.dp).scale(scale),
            content = {},
        )
        Icon(
            imageVector = ServiceTagIcons.Contactless,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(64.dp),
        )
    }
}

/** What the phone can actually do, said once and quietly — never over the top of the card. */
@Composable
private fun NfcAvailability(readerMode: ReaderMode) {
    val line = when {
        !readerMode.present -> "Scanning needs the app's own window."
        !readerMode.available -> "This phone has no NFC hardware."
        !readerMode.enabled -> "NFC is turned off. Enable it in system settings, then come back."
        else -> null
    }
    line?.let { QuietLine(it) }
}
