package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS
import com.loosecannon.servicetag.core.references.ShareTextParser
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.RecordingReferenceRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one place a reference is ever created, so the one place every refusal lives (I-2). The step
 * order is the contract: structural validity (I-10), then the length cap, then the tier, then the
 * name, then the owner, then the second identity — each arm has to stay reachable.
 */
class AddReferenceTest {

    private val references = RecordingReferenceRepository()
    private val assets = InMemoryAssetRepository()
    private val uow = RecordingUnitOfWork(assets, references)
    private var now = 5_000L
    private var seq = 0
    private val ids = IdGenerator { "ref-${++seq}" }
    private val add = AddReference(
        references, assets, LinkLaunchPolicy(), uow, ids, Clock { now },
    )

    private val asset = AssetId("a1")
    private val other = AssetId("a2")

    private suspend fun haveAsset(id: AssetId = asset) {
        assets.upsert(Asset(id = id, name = "Cub Cadet XT1", createdAt = 1L, updatedAt = 1L))
    }

    private fun cmd(
        uri: String,
        name: String = "Deck belt",
        description: String = "",
        confirmed: Boolean = false,
    ) = AddReferenceCommand(
        uri = uri,
        displayName = name,
        description = description,
        confirmedUnknownScheme = confirmed,
    )

    private fun refusal(result: ReferenceResult<AssetReference>): ReferenceProblem {
        assertTrue(result is ReferenceResult.Refused, "expected a refusal, got $result")
        return result.problem
    }

    private fun saved(result: ReferenceResult<AssetReference>): AssetReference {
        assertTrue(result is ReferenceResult.Ok, "expected a saved row, got $result")
        return result.value
    }

    /** Structurally valid URIs, one per hard-blocked scheme, so the *tier* is what refuses them. */
    private val blocked = listOf(
        "javascript:alert(1)",
        "file://localhost/etc/passwd",
        "content://com.android.providers.downloads.documents/document/17",
        "intent://scan#Intent;scheme=zxing;end",
        "android-app://com.example.reader",
        "tel:+15550100",
        "sms:+15550100",
        "mailto:parts@example-mower.invalid",
    )

    @Test
    fun aStructurallyBrokenUriIsNotALinkAndAnOpaqueSchemeIsFine() = runTest {
        haveAsset()
        for (broken in listOf("https://", "http:///path", "notaurl", "", "   ", "joplin:")) {
            assertEquals(
                ReferenceProblem.NotALink,
                refusal(add.run(asset, cmd(broken))),
                "$broken should not be a link",
            )
        }
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)

        val opaque = "joplin:x-callback-url/openNote?id=0f1e2d3c4b5a6978"
        assertEquals(opaque, saved(add.run(asset, cmd(opaque))).uri)
    }

    /**
     * The "it parses at all" arm of I-10, which nothing else reaches: each of these carries a
     * scheme and a host, so only the whitespace-and-control-character rule can refuse them.
     *
     * The raw-space case is the **accepted** consequence the controller ruled on: a URI with an
     * unencoded space does not parse, so `obsidian://open?vault=My Vault` is refused as "That is
     * not a link." rather than saved. That is #35's shipped rule restored verbatim, not an
     * oversight, and a share cannot produce one — its tokens are whitespace-delimited.
     */
    @Test
    fun aUriCarryingRawWhitespaceOrAControlCharacterDoesNotParseAndIsNotALink() = runTest {
        haveAsset()
        val unparseable = listOf(
            "https://example-mower.invalid/deck belt",
            "https://example-mower.invalid/x\u0000",
            "https://example-mower.invalid/x\ty",
            "obsidian://open?vault=My Vault&file=Mower",
        )
        for (uri in unparseable) {
            assertEquals(
                ReferenceProblem.NotALink,
                refusal(add.run(asset, cmd(uri))),
                "$uri does not parse",
            )
        }
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)
    }

    @Test
    fun anOverLongUriIsRefusedAndTheLastLegalLengthIsSaved() = runTest {
        haveAsset()
        val prefix = "https://example-mower.invalid/"
        val tooLong = prefix + "a".repeat(MAX_REFERENCE_URI_CHARS + 1 - prefix.length)
        assertEquals(MAX_REFERENCE_URI_CHARS + 1, tooLong.length)
        assertEquals(ReferenceProblem.UriTooLong, refusal(add.run(asset, cmd(tooLong))))
        assertEquals(0, references.upserts)

        val longest = tooLong.dropLast(1)
        assertEquals(MAX_REFERENCE_URI_CHARS, saved(add.run(asset, cmd(longest))).uri.length)
    }

    @Test
    fun everyHardBlockedSchemeIsRefusedAtSave() = runTest {
        haveAsset()
        for (uri in blocked) {
            assertEquals(
                ReferenceProblem.SchemeBlocked,
                refusal(add.run(asset, cmd(uri))),
                "$uri should be blocked",
            )
        }
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)
    }

    /**
     * A URI with no scheme has no tier to be judged by, so it is the structural refusal and not the
     * unknown-scheme one (spec §4.2). `LinkLaunchPolicy` still classifies it `Blocked`; this use
     * case never gets that far.
     */
    @Test
    fun aUriWithNoSchemeIsRefusedByTheStructuralRuleBeforeTheTierLookup() = runTest {
        haveAsset()
        assertEquals(LinkDecision.Blocked, LinkLaunchPolicy().classify("example-mower.invalid/xt1"))
        assertEquals(
            ReferenceProblem.NotALink,
            refusal(add.run(asset, cmd("example-mower.invalid/xt1"))),
        )
        assertEquals(0, references.upserts)
    }

    @Test
    fun everyAllowedSchemeSavesWithTheKindItsSchemeImplies() = runTest {
        haveAsset()
        val expected = mapOf(
            "http://example-mower.invalid/xt1" to ReferenceKind.WEB_URL,
            "https://example-mower.invalid/xt1" to ReferenceKind.WEB_URL,
            "joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978" to ReferenceKind.NOTE_LINK,
            "obsidian://open?vault=Shed&file=Mower" to ReferenceKind.NOTE_LINK,
            "logseq://graph/shed?page=Mower" to ReferenceKind.NOTE_LINK,
        )
        for ((uri, kind) in expected) {
            val row = saved(add.run(asset, cmd(uri)))
            assertEquals(kind, row.kind, uri)
            assertEquals(uri, row.uri)
        }
        assertEquals(expected.size, references.upserts)
    }

    @Test
    fun anUnknownSchemeTakesOneConfirmationAndThenSaves() = runTest {
        haveAsset()
        val uri = "zotero://select/items/0"
        assertEquals(
            ReferenceProblem.UnknownSchemeNeedsConfirmation("zotero"),
            refusal(add.run(asset, cmd(uri))),
        )
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)

        val row = saved(add.run(asset, cmd(uri, confirmed = true)))
        assertEquals(ReferenceKind.OTHER, row.kind)
        assertEquals("zotero", row.scheme)
    }

    @Test
    fun theSchemeIsDerivedLowercasedAndTheUriIsStoredVerbatim() = runTest {
        haveAsset()
        val uri = "HTTPS://Example-Mower.invalid/XT1?a=1&b=2#frag"
        val row = saved(add.run(asset, cmd("  $uri  ")))
        assertEquals(uri, row.uri)
        assertEquals("https", row.scheme)
        assertEquals(ReferenceKind.WEB_URL, row.kind)
        assertEquals(now, row.createdAt)
        assertEquals(now, row.updatedAt)
    }

    @Test
    fun aQueryAndAFragmentRoundTripFromASharesTextByteIdentically() = runTest {
        haveAsset()
        val uri = "https://example-mower.invalid/xt1?a=1&b=2#frag"
        val parsed = ShareTextParser.firstUri("Deck belt: $uri")
        assertEquals(uri, parsed?.uri)
        assertEquals(uri, saved(add.run(asset, cmd(parsed!!.uri))).uri)
    }

    @Test
    fun aBlankNameIsRefusedAfterSanitisationAndNotBefore() = runTest {
        haveAsset()
        val uri = "https://example-mower.invalid/xt1"
        for (name in listOf("", "   ", "\u0000", "\n\t ")) {
            assertEquals(ReferenceProblem.BlankName, refusal(add.run(asset, cmd(uri, name = name))))
        }
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)
    }

    @Test
    fun theNameAndTheDescriptionAreSanitisedAndCapped() = runTest {
        haveAsset()
        val row = saved(
            add.run(
                asset,
                cmd(
                    uri = "https://example-mower.invalid/xt1",
                    name = "a".repeat(MAX_REFERENCE_NAME_CHARS + 1),
                    description = "b".repeat(MAX_REFERENCE_DESCRIPTION_CHARS + 1),
                ),
            ),
        )
        assertEquals(MAX_REFERENCE_NAME_CHARS, row.displayName.length)
        assertEquals(MAX_REFERENCE_DESCRIPTION_CHARS, row.description.length)
    }

    @Test
    fun aDuplicateOnTheSameAssetIsRefusedWhileASecondAssetMaySaveIt() = runTest {
        haveAsset(asset)
        haveAsset(other)
        val uri = "https://example-mower.invalid/xt1"
        saved(add.run(asset, cmd(uri)))
        assertEquals(ReferenceProblem.DuplicateUri, refusal(add.run(asset, cmd(uri))))
        assertEquals(1, references.upserts)

        assertEquals(other, saved(add.run(other, cmd(uri))).assetId)
        assertEquals(2, references.upserts)
    }

    @Test
    fun anAssetThatIsNotThereIsRefusedRatherThanLeftToTheForeignKey() = runTest {
        assertEquals(
            ReferenceProblem.OwnerMissing,
            refusal(add.run(asset, cmd("https://example-mower.invalid/xt1"))),
        )
        assertEquals(0, references.upserts)
        assertEquals(0, uow.writesEntered)
    }

    /** No `kind`, no `provenance`, and no owner: the command is these four fields and no others. */
    @Test
    fun theCommandCarriesNoKindForACallerToDisagreeWith() {
        assertEquals(
            setOf("uri", "displayName", "description", "confirmedUnknownScheme"),
            AddReferenceCommand::class.java.declaredFields.map { it.name }.toSet(),
        )
    }
}
