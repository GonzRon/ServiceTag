package com.loosecannon.servicetag.ui.scan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.6 — what the owner sees when an old link tag is read: one sentence, no launch, no bind offer.
 * The row and the link row are both still there afterwards; the sheet is the whole of the app's
 * response. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class PreSplitLinkTagSheetTest {

    @get:Rule val rule = createComposeRule()

    private val key = "123e4567-e89b-12d3-a456-426614174000"

    @Before fun freshInstall() = clearInstall()

    @Test fun anOldLinkTagSaysSoAndOffersNothing() {
        val graph = app.graph
        runBlocking {
            graph.uow.write {
                graph.links.upsert(ExternalLink(LinkId("l1"), null, LinkKind.JOPLIN, "note", "joplin://x", 1L, null, 1L))
                graph.tags.upsert(
                    TagBinding(TagId(key), PayloadFormat.V1, key, TagTarget.LinkTarget(LinkId("l1")), TagStatus.ACTIVE, createdAt = 1L, updatedAt = 1L),
                )
            }
        }
        rule.setContent {
            ServiceTagTheme {
                TagResultSheet(
                    graph = graph,
                    format = PayloadFormat.V1.name,
                    key = key,
                    onDismiss = {},
                    onWriteTag = { error("a pre-split link tag must never offer a write") },
                    onOpenAsset = { error("a pre-split link tag must never open an asset") },
                    onNewAsset = {},
                )
            }
        }

        rule.awaitText(
            "This tag points at a note link from before the product split. " +
                "ServiceTag no longer opens links; NoteTag does.",
        )
        rule.onAllNodesWithText("Bind to asset").assertCountEquals(0)
        rule.onAllNodesWithText("Write a new tag over it").assertCountEquals(0)
        runBlocking { check(graph.links.all().size == 1) { "the tombstone row was touched" } }
    }
}
