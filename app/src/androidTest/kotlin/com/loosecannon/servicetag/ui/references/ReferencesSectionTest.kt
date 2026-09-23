package com.loosecannon.servicetag.ui.references

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REFERENCES, drawn. `ReferencesList` and the three sheets are pure functions of their arguments —
 * no `AppGraph`, no Room — so every case renders one of them directly, the way
 * `AssetTagsSectionTest` renders `TagsSection`. What only a device can show is the wording that
 * reaches the semantics tree, which is exactly what §10 ratifies.
 *
 * The open path is here rather than in the JVM suite for one reason: a refusal routed through a
 * `Toast` cannot be read at all, so the section shows a **snackbar** and this file proves the
 * ratified sentence arrives in it.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ReferencesSectionTest {

    @get:Rule val rule = createComposeRule()

    private var opened: String? = null
    private var edited: String? = null
    private var removed: String? = null
    private var addTaps = 0

    private fun row(
        id: String,
        name: String,
        kind: ReferenceKind = ReferenceKind.WEB_URL,
        description: String = "",
        uri: String = "https://example-mower.invalid/$id",
        launchable: Boolean = true,
    ) = ReferenceRowState(
        id = id,
        displayName = name,
        description = description,
        uri = uri,
        kind = kind,
        launchable = launchable,
    )

    private fun draw(vararg rows: ReferenceRowState, handled: Boolean = true) {
        rule.setContent {
            ServiceTagTheme {
                val snackbars = remember { SnackbarHostState() }
                Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                    Column(Modifier.padding(padding)) {
                        ReferencesList(
                            state = ReferencesSectionState(rows = rows.toList()),
                            snackbars = snackbars,
                            onOpen = { uri -> opened = uri; handled },
                            onEdit = { edited = it.id },
                            onRemove = { removed = it.id },
                            onAddLink = { addTaps += 1 },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * Every string the composition draws, as a set. A node that merges its descendants carries
     * their text as well as its own, so the union over every node with a text property is the
     * whole of what is on screen — which is what an "and no other" claim has to be asserted
     * against, rather than against the strings the test already expects to find.
     */
    private fun everyStringDrawn(): Set<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .toSet()

    /** The overflow for the row at [index]; the section's rows are the only ones drawn here. */
    private fun openMenu(index: Int = 0) {
        rule.onAllNodesWithContentDescription("More")[index].performClick()
        rule.waitForIdle()
    }

    /**
     * An empty section that drew nothing would leave the owner with no way to reach "Add link" at
     * all, which is the only way a reference is created without a share.
     */
    @Test fun anEmptySectionStillNamesItselfAndOffersAddLink() {
        draw()

        // `SectionHeader` renders its title uppercased, so that is what the tree carries.
        rule.onNodeWithText("REFERENCES").assertIsDisplayed()
        rule.onNodeWithText("No references yet").assertIsDisplayed()
        rule.onNodeWithText("Add link").performClick()
        assertEquals(1, addTaps)
    }

    /** The count is the shipped DOCUMENTS header's shape; a hardcoded title would hide it. */
    @Test fun theHeaderCarriesTheCountOnceThereAreRows() {
        draw(row("a", "Deck manual"), row("b", "Parts list"), row("c", "Service bulletin"))

        rule.onNodeWithText("REFERENCES · 3").assertIsDisplayed()
        rule.onAllNodesWithText("REFERENCES").assertCountEquals(0)
    }

    /** A `kind.name` rendered raw would show `WEB_URL` to a person. */
    @Test fun eachKindDrawsItsOwnRatifiedWord() {
        draw(
            row("a", "Deck manual", ReferenceKind.WEB_URL),
            row("b", "Teardown note", ReferenceKind.NOTE_LINK, uri = "joplin://x-callback-url/o"),
            row("c", "Zotero item", ReferenceKind.OTHER, uri = "zotero://select/items/0"),
        )

        rule.onNodeWithText("Web link").assertIsDisplayed()
        rule.onNodeWithText("Note").assertIsDisplayed()
        rule.onNodeWithText("Other").assertIsDisplayed()
    }

    /**
     * The description is the thing the share path collects and had nowhere to show. A row without
     * one draws no second line at all — not an empty one.
     *
     * A row is clickable, so it is **one merged semantics node** and its text list is the whole of
     * what it draws. That is what makes the second assertion bite: an unguarded description line
     * would put a third, empty entry in it.
     */
    @Test fun aDescriptionIsDrawnWhenThereIsOneAndNoLineAtAllWhenThereIsNot() {
        draw(
            row("a", "Deck manual", description = "Section 4 covers the pump seal"),
            row("b", "Parts list"),
        )

        rule.onNode(hasTextExactly("Deck manual", "Web link", "Section 4 covers the pump seal"))
            .assertIsDisplayed()
        rule.onNode(hasTextExactly("Parts list", "Web link")).assertIsDisplayed()
    }

    /**
     * A policy applied only at save would let a URI that became illegal be fired at `ACTION_VIEW`.
     * The row is still listed — it is the owner's — and Open says so with the **open**-time line,
     * not the save-time one: nothing is being saved here.
     */
    @Test fun aRowTheBlockListNowRefusesIsShownAndNeverHandedOn() {
        draw(row("a", "Was legal once", uri = "javascript:alert(1)", launchable = false))

        rule.onNodeWithText("Was legal once").assertIsDisplayed()
        openMenu()
        rule.onNodeWithText("Open").performClick()

        rule.awaitText("ServiceTag will not open that kind of link.")
        assertNull("nothing may reach the launcher", opened)
        rule.onAllNodesWithText("ServiceTag will not save that kind of link.").assertCountEquals(0)
    }

    /** A second confirmation at launch would contradict spec §4.2: one question, once, at save. */
    @Test fun aStoredUnknownSchemeOpensWithNoSecondConfirmation() {
        draw(row("a", "Zotero item", ReferenceKind.OTHER, uri = "zotero://select/items/0"))

        openMenu()
        rule.onNodeWithText("Open").performClick()
        rule.waitForIdle()

        assertEquals("zotero://select/items/0", opened)
        rule.onAllNodesWithText("Save this link?").assertCountEquals(0)
    }

    /**
     * The hazard this feature introduces: a URI that nothing on the phone can open. The line is
     * ratified, it names no URI, and it is a snackbar precisely so it can be read here.
     */
    @Test fun aMissingHandlerSaysSoWithoutNamingTheUri() {
        draw(row("a", "Deck manual", uri = "https://example-mower.invalid/a"), handled = false)

        openMenu()
        rule.onNodeWithText("Open").performClick()

        rule.awaitText("No app can open this link")
        assertEquals("https://example-mower.invalid/a", opened)
        rule.onAllNodesWithText("example-mower.invalid", substring = true).assertCountEquals(0)
    }

    /** Edit and Remove reach the section's own callbacks, and neither is the other. */
    @Test fun theOverflowOffersOpenEditAndRemoveAndEachReachesItsOwnCallback() {
        draw(row("a", "Deck manual"), row("b", "Parts list"))

        openMenu(index = 1)
        rule.onNodeWithText("Edit").performClick()
        rule.waitForIdle()
        assertEquals("b", edited)

        openMenu(index = 0)
        rule.onNodeWithText("Remove").performClick()
        rule.waitForIdle()
        assertEquals("a", removed)
    }

    /** I-1: a URI field on the sheet would make "never edited after creation" unenforceable. */
    @Test fun theEditSheetHasExactlyTwoFieldsAndTheUriIsNotOneOfThem() {
        var saved: UpdateReferenceCommand? = null
        rule.setContent {
            ServiceTagTheme {
                ReferenceEditSheet(
                    row = row("a", "Deck manual", description = "Section 4"),
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Edit reference").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Description").assertIsDisplayed()
        rule.onAllNodesWithText("Link").assertCountEquals(0)
        rule.onAllNodesWithText("example-mower.invalid", substring = true).assertCountEquals(0)

        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(UpdateReferenceCommand("Deck manual", "Section 4"), saved)
    }

    /**
     * A typed confirmation for one metadata row is out of all proportion, and no confirmation at
     * all is destructive: a plain question, and Cancel writes nothing.
     */
    @Test fun removeAsksAPlainQuestionAndCancelRemovesNothing() {
        var removes = 0
        var dismisses = 0
        rule.setContent {
            ServiceTagTheme {
                RemoveReferenceDialog(onRemove = { removes += 1 }, onDismiss = { dismisses += 1 })
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Remove this reference?").assertIsDisplayed()
        rule.onNodeWithText(
            "The link is removed from this asset. Nothing in the other app is changed.",
        ).assertIsDisplayed()
        // Not the typed-REPLACE dialog: there is nothing to type into.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, removes)
        assertEquals(1, dismisses)
    }

    /**
     * The five ratified strings, and **no sixth**: the sheet title is the action's own wording,
     * and §1.9 makes "a string §10 does not list is a finding for the controller" the contract
     * this case holds. So the whole text inventory is asserted as an exact set — a supporting
     * line, a helper text or a placeholder added later turns this red rather than passing.
     */
    @Test fun theAddLinkSheetShipsItsFiveRatifiedStringsAndNoOther() {
        var saved: Triple<String, String, String>? = null
        rule.setContent {
            ServiceTagTheme {
                AddLinkSheet(
                    onSave = { uri, name, description -> saved = Triple(uri, name, description) },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        assertEquals(
            setOf("Add link", "Link", "Name", "Description", "Save", "Cancel"),
            everyStringDrawn(),
        )
        rule.onAllNodesWithText("Add link").assertCountEquals(1)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(3)

        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(Triple("", "", ""), saved)
    }

    /** The scheme is named, because the person is being asked about that scheme and no other. */
    @Test fun theUnknownSchemeQuestionNamesTheSchemeAndOffersSaveOrCancel() {
        var saves = 0
        rule.setContent {
            ServiceTagTheme {
                UnknownSchemeDialog(scheme = "zotero", onSave = { saves += 1 }, onDismiss = {})
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Save this link?").assertIsDisplayed()
        rule.onNodeWithText(
            "ServiceTag does not recognise \"zotero\" links. " +
                "It will be saved as written and opened with whatever app claims it.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()

        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(1, saves)
    }
}
