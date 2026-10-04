package com.loosecannon.servicetag.nfc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import com.loosecannon.nfc.tagcore.android.ndefRecords
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.nfc.TagRoute
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.scan.TagResultWire
import com.loosecannon.servicetag.ui.scan.asTagResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one NFC-exported component (D3 §9, security doc "NFC dispatch"). Background scans arrive
 * here through the one `NDEF_DISCOVERED` filter; only `EXTRA_NDEF_MESSAGES`, `EXTRA_TAG` and the
 * data URI are read — every other extra is ignored.
 *
 * It has no UI at all: everything is handed to `MainActivity` as a (format, key) pair, so the
 * single activity owns every pixel the app draws. The translucent theme is what keeps a window
 * from flashing on the way through.
 */
class NfcDispatchActivity : Activity() {

    private val scope = MainScope()
    private val graph: AppGraph get() = (application as ServiceTagApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun handle(intent: Intent) {
        // A third-party app can aim any extras at an exported activity; on pre-33 devices the
        // untyped `getParcelableArrayExtra` unparcels whatever it is handed, so a hostile or simply
        // wrong bundle throws here rather than returning null. Treat it as "nothing to resolve".
        val payload = try {
            payloadOf(intent)
        } catch (e: Exception) {
            null
        }
        if (payload == null) {
            Toast.makeText(this, localized(R.string.nfc_nothing_to_resolve), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        scope.launch {
            val resolution = try {
                graph.resolveTag.run(payload)
            } catch (e: Exception) {
                // The not-ours key is prose the sheet shows as is (TagResultWire), so it is worded here.
                handOff(TagResultWire.FORMAT_NONE, localized(R.string.nfc_could_not_resolve, e.javaClass.simpleName))
                return@launch
            }
            route(resolution)
        }
    }

    private fun payloadOf(intent: Intent): TagPayload? = when (intent.action) {
        NfcAdapter.ACTION_NDEF_DISCOVERED -> graph.ndefCodec.decode(intent.ndefRecords().orEmpty())
        Intent.ACTION_VIEW -> tagRoute(intent.data)
        else -> null
    }

    private fun tagRoute(uri: Uri?): TagPayload? =
        TagRoute.parse(uri?.scheme, uri?.host, uri?.pathSegments.orEmpty())

    /** Every resolution becomes the very route the foreground scanner would have produced, so the
     *  two paths say the same words about a tag. Nothing is launched from here (2.6). */
    private fun route(r: Resolution) = r.asTagResult().let { handOff(it.format, it.key) }

    /** The trampoline's only exit: the single activity renders the result, this one never does. */
    private fun handOff(format: String, key: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAG_FORMAT, format)
                .putExtra(MainActivity.EXTRA_TAG_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
