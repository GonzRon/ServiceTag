package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Row 42 (#91, C23, R91-5): Add link's form, the state the sheet renders. It is driven by the view
 * model's **real** `roleOffered` (C-4) — the kind `AddReference` will derive from the same text,
 * asked whether it takes a role — so this class never classifies a link itself, and neither does
 * the form: the chips follow the live text, a pick the text can no longer carry is cleared, and
 * Save never sends a role for a link that cannot take one. No refusal sentence exists (G1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddLinkFormTest {

    private companion object {
        const val MANUAL = "https://manuals.example.invalid/water-heater/manual.pdf"
        const val NOTE = "joplin://x-callback-url/openNote?id=example"

        /** Texts `AddReference` would file as a web link: http or https, any case, any padding. */
        val WEB = listOf(
            MANUAL,
            "http://manuals.example.invalid/water-heater",
            "   HTTP://MANUALS.EXAMPLE.INVALID/water-heater/service.pdf",
        )

        /** Texts it would not: nothing yet, a note app, a blocked scheme, and two that merely start "http". */
        val NOT_WEB = listOf(
            "",
            "   ",
            NOTE,
            "mailto:",
            "httpx://manuals.example.invalid/water-heater",
            "https-manuals.example.invalid/water-heater/manual.pdf",
            "manuals.example.invalid/water-heater/manual.pdf",
        )
    }

    private val store = ViewModelStore()
    private lateinit var graph: FakeGraph

    /** The view model's own question, and nothing standing in for it. */
    private lateinit var offered: (String) -> Boolean

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = FakeGraph()
        val policy = LinkLaunchPolicy()
        val factory = viewModelFactory {
            initializer {
                ReferencesSectionViewModel(
                    AssetId("example-water-heater"),
                    graph.references,
                    AddReference(
                        graph.references, graph.assets, graph.supplyItems, graph.installedComponents, policy, graph.uow,
                        graph.ids, graph.clock,
                    ),
                    UpdateReference(graph.references, graph.uow, graph.clock),
                    RemoveReference(graph.references, graph.uow),
                    policy,
                    graph.attachments,
                    HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) }),
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)["add-link-form", ReferencesSectionViewModel::class]
        offered = vm::roleOffered
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    /** The chips are drawn only while the typed text is a web link, and an empty form is not one. */
    @Test fun noChipsUntilTheTextIsAWebLink() {
        assertFalse("an empty form offers no chips", AddLinkForm().offersRole(offered))
        NOT_WEB.forEach { text ->
            assertFalse("not offered on '$text'", AddLinkForm().withLink(text, offered).offersRole(offered))
        }
        WEB.forEach { text ->
            assertTrue("offered on '$text'", AddLinkForm().withLink(text, offered).offersRole(offered))
        }
    }

    /**
     * A pick lives only as long as the text can carry it: typing on within a web link keeps it, the
     * text turning into a note link clears it, and the chips coming back start again on "No role".
     */
    @Test fun aPickIsClearedWhenTheTextStopsBeingAWebLink() {
        val picked = AddLinkForm().withLink(MANUAL, offered).withRole(DocumentRole.USER_MANUAL, offered)
        assertEquals(DocumentRole.USER_MANUAL, picked.role)
        assertEquals(
            "still a web link, so the pick stays",
            DocumentRole.USER_MANUAL,
            picked.withLink("$MANUAL#page=4", offered).role,
        )

        val note = picked.withLink(NOTE, offered)
        assertNull("the pick is cleared once the text is a note link", note.role)

        val webAgain = note.withLink(MANUAL, offered)
        assertTrue(webAgain.offersRole(offered))
        assertNull("the chips come back on \"No role\", not the old pick", webAgain.role)
    }

    /** No chips are drawn, so no pick can be made; a pick that arrives anyway is not recorded. */
    @Test fun aPickIsIgnoredWhileNotOffered() {
        NOT_WEB.forEach { text ->
            val form = AddLinkForm().withLink(text, offered).withRole(DocumentRole.SERVICE_MANUAL, offered)
            assertNull("no pick recorded on '$text'", form.role)
        }
        val cleared = AddLinkForm().withLink(MANUAL, offered)
            .withRole(DocumentRole.SERVICE_MANUAL, offered)
            .withRole(null, offered)
        assertNull("\"No role\" is a pick too, and clears", cleared.role)
    }

    /** Whatever the form holds, Save sends a role only for a web link — so `RoleNotAllowed` is never met. */
    @Test fun theRoleAtSaveIsNullForANonWebLink() {
        NOT_WEB.forEach { text ->
            val held = AddLinkForm(link = text, role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT)
            assertNull("no role sent for '$text'", held.roleAtSave(offered))
        }
        WEB.forEach { text ->
            val held = AddLinkForm(link = text, role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT)
            assertEquals("the pick sent for '$text'", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, held.roleAtSave(offered))
        }
        assertNull("no pick, no role", AddLinkForm(link = MANUAL).roleAtSave(offered))
    }
}
