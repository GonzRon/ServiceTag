package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.usecase.LoanProblem
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate

/**
 * #72 (C21; R72-3, R72-4, R72-11, R72-15–R72-18) — the five loan routes over the production router,
 * handlers and serializers.
 *
 * `GET /v1/assets/{id}/loans` and `GET /v1/loans/{id}` read; `POST /v1/loans`, `PATCH /v1/loans/{id}` and
 * `POST /v1/loans/{id}/return` each call one use case. A loan answers as [LoanDto]: the archive row's
 * fields less the contact lookup URI, plus `contactLinked` — the link never crosses this API, and no body
 * takes one. The checks run in the use cases' order: a missing asset or loan (404), then the state
 * refusal (409 `asset_already_lent`, `loan_returned`), then every problem (422 `loan_validation`); a
 * malformed body is the shipped 400 before all of them. Nothing deletes or relinks a loan, and no handler
 * reconciles a reminder.
 */
class LoanRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-09-01")
        today = LocalDate.parse("2026-09-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun lendBody(asset: String, extra: String = "", borrower: String = "Sample Borrower", lentOn: String = "2026-09-10") =
        """{"assetId":"$asset","borrowerName":"$borrower","lentOn":"$lentOn"$extra}"""

    private fun lend(asset: String, extra: String = "", lentOn: String = "2026-09-10"): LoanDto =
        api.ok(LoanResponse.serializer(), "POST", "/v1/loans", lendBody(asset, extra, lentOn = lentOn), status = 201).loan

    private fun markReturned(loan: String, on: String = "2026-09-19"): LoanDto =
        api.ok(LoanResponse.serializer(), "POST", "/v1/loans/$loan/return", """{"returnedOn":"$on"}""").loan

    private fun readBack(loan: String): LoanDto = api.ok(LoanResponse.serializer(), "GET", "/v1/loans/$loan").loan

    private fun listed(asset: String): List<LoanDto> =
        api.ok(LoanListResponse.serializer(), "GET", "/v1/assets/$asset/loans").loans

    private fun stored(id: String): AssetLoan = runBlocking { graph.loans.get(AssetLoanId(id)) }!!

    private fun allLoans(): List<AssetLoan> = runBlocking { graph.loans.all() }

    private fun retire(asset: String) =
        api.ok(AssetResponse.serializer(), "POST", "/v1/assets/$asset/retire", """{"retiredOn":"2026-09-15"}""")

    /**
     * The lend: 201 `{loan}`, open, name-only, exactly the fields sent — trimmed, a blank `dueOn` no due
     * date, a mode left out `NONE`. `assetId`, `borrowerName` and `lentOn` have no default; an unknown key
     * (a lookup URI, a return date) is the shipped 400, and a missing asset the shipped 404.
     */
    @Test fun postLoanLendsAnOpenNameOnlyLoanWithExactlyWhatWasSent() {
        val drill = api.asset("Example Drill")
        val lent = lend(drill, ""","dueOn":"2026-09-25","reminderMode":"ONCE","notes":"With the long bit"""")
        val expected = LoanDto(
            id = lent.id, assetId = drill, borrowerName = "Sample Borrower", contactLinked = false,
            lentOn = "2026-09-10", dueOn = "2026-09-25", reminderMode = "ONCE", returnedOn = null,
            notes = "With the long bit", createdAt = graph.now, updatedAt = graph.now,
        )
        assertEquals(expected, lent)
        assertEquals("the answer is the row as stored", expected, stored(lent.id).toLoanDto())
        assertEquals(expected, readBack(lent.id))
        assertEquals(listOf(expected), listed(drill))

        val ladder = api.asset("Example Ladder")
        val bare = api.ok(
            LoanResponse.serializer(), "POST", "/v1/loans",
            """{"assetId":"$ladder","borrowerName":"  Sample Borrower  ","lentOn":" 2026-09-12 ","dueOn":"  "}""", status = 201,
        ).loan
        assertEquals("Sample Borrower", bare.borrowerName)
        assertEquals("2026-09-12", bare.lentOn)
        assertNull("a blank dueOn is no due date", bare.dueOn)
        assertEquals("a mode left out is NONE", "NONE", bare.reminderMode)
        assertEquals("", bare.notes)
        assertNull(bare.returnedOn)

        val before = allLoans()
        val spare = api.asset("Example Saw")
        for (body in listOf(
            """{"assetId":"$spare","borrowerName":"Sample Borrower"}""",
            """{"assetId":"$spare","lentOn":"2026-09-10"}""",
            """{"borrowerName":"Sample Borrower","lentOn":"2026-09-10"}""",
            lendBody(spare, ""","reminderMode":"once","dueOn":"2026-09-25""""),
            lendBody(spare, ""","returnedOn":"2026-09-11""""),
            lendBody(spare, ""","status":"OPEN""""),
        )) {
            val refused = api.call("POST", "/v1/loans", body)
            assertEquals("$body: ${refused.bodyText()}", 400, refused.status)
        }
        val missing = api.call("POST", "/v1/loans", lendBody("no-such-asset"))
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
        assertEquals("a refused lend writes nothing", before, allLoans())
    }

    /**
     * Every field sent is the field stored and read back, on the lend, the full-replace `PATCH` and the
     * return: distinct values each time — no two dates alike — so a dropped or swapped mapping line cannot
     * pass; checked on the response, the stored row and the `GET`. The borrower and `createdAt` never
     * move; a key the `PATCH` leaves out is cleared; the stored terms sent again write nothing.
     */
    @Test fun everyFieldSentIsStoredAndReadBack() {
        val drill = api.asset("Example Drill")
        val lent = lend(drill, ""","dueOn":"2026-09-25","reminderMode":"ONCE","notes":"notes-a"""")
        val createdAt = graph.now

        graph.now += 86_400_000L
        val replaced = api.ok(
            LoanResponse.serializer(), "PATCH", "/v1/loans/${lent.id}",
            """{"lentOn":"2026-09-11","dueOn":"2026-09-30","reminderMode":"UNTIL_RETURNED","notes":"notes-b"}""",
        ).loan
        val second = LoanDto(
            id = lent.id, assetId = drill, borrowerName = "Sample Borrower", contactLinked = false,
            lentOn = "2026-09-11", dueOn = "2026-09-30", reminderMode = "UNTIL_RETURNED", returnedOn = null,
            notes = "notes-b", createdAt = createdAt, updatedAt = graph.now,
        )
        assertEquals(second, replaced)
        assertEquals(second, stored(lent.id).toLoanDto())
        assertEquals(second, readBack(lent.id))

        graph.now += 86_400_000L
        val again = api.ok(
            LoanResponse.serializer(), "PATCH", "/v1/loans/${lent.id}",
            """{"lentOn":"2026-09-11","dueOn":"2026-09-30","reminderMode":"UNTIL_RETURNED","notes":"notes-b"}""",
        ).loan
        assertEquals("the stored terms sent again write nothing", second, again)

        val cleared = api.ok(LoanResponse.serializer(), "PATCH", "/v1/loans/${lent.id}", """{"lentOn":"2026-09-13"}""").loan
        assertEquals("2026-09-13", cleared.lentOn)
        assertNull("a dueOn left out is cleared", cleared.dueOn)
        assertEquals("a mode left out is NONE", "NONE", cleared.reminderMode)
        assertEquals("notes left out are cleared", "", cleared.notes)
        assertEquals("Sample Borrower", cleared.borrowerName)

        graph.now += 86_400_000L
        val returned = markReturned(lent.id, on = "2026-09-18")
        val third = cleared.copy(returnedOn = "2026-09-18", updatedAt = graph.now)
        assertEquals(third, returned)
        assertEquals(third, stored(lent.id).toLoanDto())
        assertEquals(third, readBack(lent.id))
        assertEquals("the row stays, as history", listOf(third), listed(drill))
    }

    /**
     * The two reads: an asset's loans, open and returned, the latest lent first then by id — the
     * repository's order — and one loan; each writes nothing. The asset's new sub-resource answers 404 for a
     * verb it does not take, as every one of them does; the three loan shapes answer 405.
     */
    @Test fun theTwoReadsAnswerTheRowsInTheRepositoryOrder() {
        val drill = api.asset("Example Drill")
        val first = lend(drill, lentOn = "2026-09-01")
        markReturned(first.id, on = "2026-09-05")
        val second = lend(drill, lentOn = "2026-09-10")

        val rows = listed(drill)
        assertEquals(listOf(second.id, first.id), rows.map { it.id })
        assertEquals(listOf(stored(second.id).toLoanDto(), stored(first.id).toLoanDto()), rows)
        assertEquals(stored(first.id).toLoanDto(), readBack(first.id))
        assertEquals("""{"loans":[]}""", api.call("GET", "/v1/assets/${api.asset("Example Saw")}/loans").bodyText())

        val missing = api.call("GET", "/v1/assets/no-such-asset/loans")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
        for (method in listOf("POST", "PATCH", "DELETE")) {
            assertEquals("$method on the asset's loans", 404, api.call(method, "/v1/assets/$drill/loans", "{}").status)
        }
        for ((method, path) in listOf(
            "GET" to "/v1/loans", "PATCH" to "/v1/loans", "DELETE" to "/v1/loans",
            "POST" to "/v1/loans/${second.id}", "DELETE" to "/v1/loans/${second.id}",
            "GET" to "/v1/loans/${second.id}/return", "PATCH" to "/v1/loans/${second.id}/return",
            "DELETE" to "/v1/loans/${second.id}/return",
        )) {
            assertEquals("$method $path", 405, api.call(method, path, if (method == "GET") "" else "{}").status)
        }
    }

    /** A loan that is not there is `no_such_loan` on every route that names one, and nothing is written. */
    @Test fun aMissingLoanIs404() {
        val before = allLoans()
        for ((method, path, body) in listOf(
            Triple("GET", "/v1/loans/no-such-loan", ""),
            Triple("PATCH", "/v1/loans/no-such-loan", """{"lentOn":"2026-09-10"}"""),
            Triple("POST", "/v1/loans/no-such-loan/return", """{"returnedOn":"2026-09-19"}"""),
        )) {
            val missing = api.call(method, path, body)
            assertEquals("$method $path: ${missing.bodyText()}", 404, missing.status)
            assertEquals(ApiErrorDetail("no_such_loan", "no such loan"), missing.errorDetail())
        }
        assertEquals(before, allLoans())
    }

    /**
     * R72-4, R72-16: a loan the phone linked answers `contactLinked: true` on every route, and the lookup
     * URI is on no answer and in no request — [LoanDto] has no field for it (checked by reflection), the
     * bodies never spell it, and a body naming it is the unknown-field 400. The API never moves a link: an
     * edit and a return keep the stored one.
     */
    @Test fun theResponseSaysContactLinkedAndNeverCarriesTheUri() {
        val drill = api.asset("Example Drill")
        val linked = runBlocking {
            graph.lendAsset.run(
                AssetId(drill), "Example Rentals Ltd", LoanTerms("2026-09-10", "2026-09-25", LoanReminderMode.ONCE), LINK,
            )
        }
        val id = linked.id.value
        fun assertLinkedAndClean(response: ApiResponse, what: String) {
            val text = response.bodyText()
            assertEquals("$what: $text", 200, response.status)
            assertTrue("$what says contactLinked: $text", "\"contactLinked\":true" in text)
            for (banned in listOf(LINK, "lookup", "contactLookupUri", "content://")) {
                assertFalse("$what carries $banned: $text", banned in text)
            }
        }
        assertLinkedAndClean(api.call("GET", "/v1/assets/$drill/loans"), "the list")
        assertLinkedAndClean(api.call("GET", "/v1/loans/$id"), "the read")
        assertLinkedAndClean(api.call("PATCH", "/v1/loans/$id", """{"lentOn":"2026-09-11","dueOn":"2026-09-26","reminderMode":"ONCE"}"""), "the edit")
        assertEquals("an edit keeps the link", LINK, stored(id).contactLookupUri)
        assertLinkedAndClean(api.call("POST", "/v1/loans/$id/return", """{"returnedOn":"2026-09-19"}"""), "the return")
        assertEquals("a return keeps the link", LINK, stored(id).contactLookupUri)
        assertFalse("a name-only loan says so", lend(api.asset("Example Saw")).contactLinked)

        val fields = LoanDto::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }
        assertTrue("reflection sees the fields: $fields", "contactLinked" in fields && "borrowerName" in fields)
        assertTrue("no field carries a link: $fields", fields.none { LINKISH.containsMatchIn(it) })
        for (descriptor in listOf(
            LoanCreateRequest.serializer().descriptor, LoanUpdateRequest.serializer().descriptor,
            LoanReturnRequest.serializer().descriptor,
        )) {
            val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            assertTrue("${descriptor.serialName} takes no link: $names", names.none { LINKISH.containsMatchIn(it) || "contact" in it })
        }

        val saw = api.asset("Example Sander")
        val before = allLoans()
        val smuggled = api.call("POST", "/v1/loans", lendBody(saw, ""","contactLookupUri":"$LINK""""))
        assertEquals(smuggled.bodyText(), 400, smuggled.status)
        val open = lend(api.asset("Example Router"))
        val relink = api.call("PATCH", "/v1/loans/${open.id}", """{"lentOn":"2026-09-10","contactLookupUri":"$LINK"}""")
        assertEquals(relink.bodyText(), 400, relink.status)
        assertNull(stored(open.id).contactLookupUri)
        assertEquals(before.size + 1, allLoans().size)
    }

    /**
     * R72-2: an asset holds at most one open loan, so a second lend is 409 `asset_already_lent` and writes
     * nothing — and so is a malformed one, because no field could change that answer (B1's check order: a
     * missing asset first, then the state refusal, then the problems). A returned loan does not block the
     * next.
     */
    @Test fun aSecondOpenLoanIs409() {
        val drill = api.asset("Example Drill")
        val open = lend(drill)
        val before = allLoans()
        for (body in listOf(
            lendBody(drill),
            lendBody(drill, borrower = " ", lentOn = "yesterday"),
        )) {
            val refused = api.call("POST", "/v1/loans", body)
            assertEquals("$body: ${refused.bodyText()}", 409, refused.status)
            assertEquals(
                ApiErrorDetail(
                    "asset_already_lent", "this asset is already lent out; return its open loan first",
                    listOf("AssetAlreadyLent(openLoanId=${open.id})"),
                ),
                refused.errorDetail(),
            )
        }
        assertEquals(before, allLoans())
        val malformedAndMissing = api.call("POST", "/v1/loans", lendBody("no-such-asset", borrower = " "))
        assertEquals(404, malformedAndMissing.status)

        markReturned(open.id)
        val next = lend(drill, lentOn = "2026-09-19")
        assertTrue(stored(next.id).isOpen)
    }

    /**
     * R72-18: a returned loan is frozen. An edit and a second return are 409 `loan_returned` — a malformed
     * one too, the state refusal coming before the problems — and the row is untouched.
     */
    @Test fun writesToAReturnedLoanAre409LoanReturned() {
        val drill = api.asset("Example Drill")
        val loan = lend(drill, ""","dueOn":"2026-09-25","notes":"kept"""").id
        markReturned(loan)
        val frozen = stored(loan)
        for ((method, path, body) in listOf(
            Triple("PATCH", "/v1/loans/$loan", """{"lentOn":"2026-09-11","notes":"changed"}"""),
            Triple("PATCH", "/v1/loans/$loan", """{"lentOn":"later"}"""),
            Triple("POST", "/v1/loans/$loan/return", """{"returnedOn":"2026-09-20"}"""),
            Triple("POST", "/v1/loans/$loan/return", """{"returnedOn":"soon"}"""),
        )) {
            val refused = api.call(method, path, body)
            assertEquals("$method $body: ${refused.bodyText()}", 409, refused.status)
            assertEquals(
                ApiErrorDetail("loan_returned", "this loan has been returned, and a returned loan never changes"),
                refused.errorDetail(),
            )
        }
        assertEquals(frozen, stored(loan))
    }

    /**
     * N10: a reminder needs a due date. A lend or an edit asking for a mode with no `dueOn` is refused —
     * 422, `field` `reminderMode` — and never quietly reset to `NONE`: the stored loan keeps its terms.
     */
    @Test fun aModeWithoutADueDateIs422() {
        val drill = api.asset("Example Drill")
        for (extra in listOf(""","reminderMode":"ONCE"""", ""","reminderMode":"UNTIL_RETURNED","dueOn":"  """")) {
            val refused = api.call("POST", "/v1/loans", lendBody(drill, extra))
            assertEquals(refused.bodyText(), 422, refused.status)
            assertEquals(
                ApiErrorDetail(
                    "loan_validation", "a reminderMode other than NONE needs a dueOn", listOf("ReminderWithoutDueDate"), "reminderMode",
                ),
                refused.errorDetail(),
            )
        }
        assertEquals(emptyList<AssetLoan>(), allLoans())

        val loan = lend(drill, ""","dueOn":"2026-09-25","reminderMode":"ONCE"""").id
        val kept = stored(loan)
        val edit = api.call("PATCH", "/v1/loans/$loan", """{"lentOn":"2026-09-10","reminderMode":"ONCE"}""")
        assertEquals(edit.bodyText(), 422, edit.status)
        assertEquals(listOf("ReminderWithoutDueDate"), edit.errorDetail().problems)
        assertEquals("the mode is never reset", kept, stored(loan))
    }

    /** A `reminderMode` outside `NONE`, `ONCE`, `UNTIL_RETURNED` — its case included — is the shipped 400. */
    @Test fun anUnknownModeIs400() {
        val drill = api.asset("Example Drill")
        val refused = api.call("POST", "/v1/loans", lendBody(drill, ""","dueOn":"2026-09-25","reminderMode":"WEEKLY""""))
        assertEquals(refused.bodyText(), 400, refused.status)
        assertTrue(refused.bodyText(), "reminderMode must be one of NONE, ONCE, UNTIL_RETURNED" in refused.bodyText())
        val loan = lend(drill).id
        val kept = stored(loan)
        val lower = api.call("PATCH", "/v1/loans/$loan", """{"lentOn":"2026-09-10","dueOn":"2026-09-25","reminderMode":"once"}""")
        assertEquals(lower.bodyText(), 400, lower.status)
        assertEquals(kept, stored(loan))
    }

    /** R72-11: the phone offers "Lend out" in service only; the API lends an asset of any lifecycle. */
    @Test fun aRetiredAssetCanBeLent() {
        val drill = api.asset("Example Drill")
        retire(drill)
        assertEquals(drill, lend(drill).assetId)
        val ladder = api.asset("Example Ladder")
        api.ok(AssetResponse.serializer(), "POST", "/v1/assets/$ladder/archive", """{"archived":true}""")
        assertEquals(ladder, lend(ladder).assetId)
        assertEquals(2, allLoans().count { it.isOpen })
    }

    /**
     * R72-15: API loan writes settle at the phone's next sweep at or after the digest hour — no handler
     * reconciles — and R72-3, R72-17: none relinks or deletes. Structural, like the warranty's: the handler
     * holds only the three use cases and the two repositories, which the reflection below reads.
     */
    @Test fun noLoanHandlerReconciles() {
        val held = LoanHandlers::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.type.simpleName }
        assertEquals(
            "reflection sees the collaborators",
            setOf("AssetRepository", "AssetLoanRepository", "LendAsset", "UpdateLoan", "ReturnLoan"),
            held.toSet(),
        )
        assertTrue(
            "no sweep, relink or contact is reachable: $held",
            held.none { Regex("Reconcile|ReminderRuns|ReminderProvider|AppGraph|Relink|Contact").containsMatchIn(it) },
        )
    }

    /**
     * The family: one lower-snake code, `field` the body key the first problem is about, `problems` every
     * problem by the domain's own name in the loan's field order; a refusal writes nothing.
     */
    @Test fun theFamilysCodeAndFields() {
        val drill = api.asset("Example Drill")
        for ((body, expected) in lendRefusals(drill)) {
            val refused = api.call("POST", "/v1/loans", body)
            assertEquals("$body: ${refused.bodyText()}", 422, refused.status)
            assertEquals(body, expected, refused.errorDetail())
        }
        assertEquals("a refused lend writes nothing", emptyList<AssetLoan>(), allLoans())

        val loan = lend(drill).id
        val kept = stored(loan)
        for ((body, expected) in updateRefusals()) {
            val refused = api.call("PATCH", "/v1/loans/$loan", body)
            assertEquals("$body: ${refused.bodyText()}", 422, refused.status)
            assertEquals(body, expected, refused.errorDetail())
        }
        for ((body, expected) in returnRefusals()) {
            val refused = api.call("POST", "/v1/loans/$loan/return", body)
            assertEquals("$body: ${refused.bodyText()}", 422, refused.status)
            assertEquals(body, expected, refused.errorDetail())
        }
        assertEquals("a refused edit or return writes nothing", kept, stored(loan))
    }

    /** `/v1/status` counts every loan, open and returned, under the archive's own list name. */
    @Test fun statusCountsTheLoans() {
        val drill = api.asset("Example Drill")
        markReturned(lend(drill, lentOn = "2026-09-01").id, on = "2026-09-05")
        lend(drill)
        lend(api.asset("Example Ladder"))
        assertEquals(3, api.ok(StatusResponse.serializer(), "GET", "/v1/status").counts["assetLoans"])
    }

    /**
     * The contract names the five routes, each on exactly one anchored row; every row of the family with
     * the sentence the wire actually sends — the unreachable `ContactLinkInvalid` from the mapper, since no
     * route can raise it; the 404 and the two 409s; and the numbers the build now carries.
     */
    @Test fun theApiDocumentNamesTheFiveRoutesAndTheFamily() {
        val text = repoFile("docs/api/v1.md").readText()
        assertEquals(
            5,
            Regex("""^\| `(GET|POST|PATCH)` \| `/v1/(assets/\{id\}/loans|loans)""", RegexOption.MULTILINE).findAll(text).count(),
        )
        for (row in listOf(
            """^\| `GET` \| `/v1/assets/\{id\}/loans` \|""",
            """^\| `POST` \| `/v1/loans` \|""",
            """^\| `GET` \| `/v1/loans/\{id\}` \|""",
            """^\| `PATCH` \| `/v1/loans/\{id\}` \|""",
            """^\| `POST` \| `/v1/loans/\{id\}/return` \|""",
        )) {
            assertEquals(row, 1, Regex(row, RegexOption.MULTILINE).findAll(text).count())
        }

        val drill = api.asset("Example Drill")
        val open = lend(drill)
        val returned = lend(api.asset("Example Ladder")).id.also { markReturned(it) }
        // What the wire sends, never the expectations above: the document drifts only with this red.
        val wire = lendRefusals(api.asset("Example Saw")).map { api.call("POST", "/v1/loans", it.first).errorDetail() } +
            updateRefusals().map { api.call("PATCH", "/v1/loans/${open.id}", it.first).errorDetail() } +
            returnRefusals().map { api.call("POST", "/v1/loans/${open.id}/return", it.first).errorDetail() }
        val unreachable = loanRefusal(LoanProblem.ContactLinkInvalid)
        val familyRows = wire.map { detail ->
            "| 422 | `${detail.code}` | `${detail.problems.first()}` | `${detail.field}` | `${detail.message}` |"
        }.toSet() + "| 422 | `${unreachable.code}` | `ContactLinkInvalid` | | `${unreachable.message}` |"
        assertEquals("ten problem rows", 10, familyRows.size)
        for (line in familyRows) {
            assertTrue(
                "docs/api/v1.md is missing the row: $line",
                Regex("^" + Regex.escape(line) + "$", RegexOption.MULTILINE).containsMatchIn(text),
            )
        }
        assertEquals(10, Regex("""^\| 422 \| `loan_validation`""", RegexOption.MULTILINE).findAll(text).count())

        val states = listOf(
            api.call("GET", "/v1/loans/no-such-loan").errorDetail(),
            api.call("POST", "/v1/loans", lendBody(drill)).errorDetail(),
            api.call("POST", "/v1/loans/$returned/return", """{"returnedOn":"2026-09-19"}""").errorDetail(),
        )
        for ((status, detail) in listOf(404, 409, 409).zip(states)) {
            val problem = detail.problems.firstOrNull()?.let { "`" + it.replace(Regex("""=[^)]*\)"""), "=…)") + "`" } ?: ""
            val line = "| $status | `${detail.code}` | $problem | | `${detail.message}` |".replace("|  |", "| |")
            assertTrue(
                "docs/api/v1.md is missing the row: $line",
                Regex("^" + Regex.escape(line) + "$", RegexOption.MULTILINE).containsMatchIn(text),
            )
        }
        assertTrue("the status line says 13 since #72 (loans)", "13 since #72 (loans)" in text)
        for (key in listOf("assetLoans", "loans", "LoanDto", "contactLinked", "ASSET_ALREADY_LENT")) {
            assertTrue("docs/api/v1.md does not name $key", "`$key`" in text)
        }
    }

    private fun refusal(message: String, field: String, vararg problems: String) =
        ApiErrorDetail("loan_validation", message, problems.toList(), field)

    /** Every lend refusal, first problem first; the borrower and the dates in the loan's field order. */
    private fun lendRefusals(asset: String): List<Pair<String, ApiErrorDetail>> = listOf(
        lendBody(asset, ""","dueOn":"2026-09-01"""", borrower = "  ") to refusal(
            "a loan needs a borrowerName", "borrowerName", "BorrowerRequired", "DueBeforeLent",
        ),
        lendBody(asset, lentOn = "10/09/2026") to refusal("lentOn must be an ISO YYYY-MM-DD date", "lentOn", "BadDate(field=lentOn)"),
        lendBody(asset, lentOn = "2026-09-21") to refusal("lentOn may not be later than today", "lentOn", "LentAfterToday"),
        lendBody(asset, ""","dueOn":"25 Sep"""") to refusal("dueOn must be an ISO YYYY-MM-DD date", "dueOn", "BadDate(field=dueOn)"),
        lendBody(asset, ""","dueOn":"2026-09-09"""") to refusal("dueOn may not be before lentOn", "dueOn", "DueBeforeLent"),
        lendBody(asset, ""","reminderMode":"ONCE"""") to refusal(
            "a reminderMode other than NONE needs a dueOn", "reminderMode", "ReminderWithoutDueDate",
        ),
    )

    /** Every edit refusal: the same terms, the borrower fixed. */
    private fun updateRefusals(): List<Pair<String, ApiErrorDetail>> = listOf(
        """{"lentOn":"2026-9-10","reminderMode":"UNTIL_RETURNED"}""" to refusal(
            "lentOn must be an ISO YYYY-MM-DD date", "lentOn", "BadDate(field=lentOn)", "ReminderWithoutDueDate",
        ),
        """{"lentOn":"2026-09-10","dueOn":"2026-09-01"}""" to refusal("dueOn may not be before lentOn", "dueOn", "DueBeforeLent"),
    )

    /** Every return refusal. */
    private fun returnRefusals(): List<Pair<String, ApiErrorDetail>> = listOf(
        """{"returnedOn":"19 Sep"}""" to refusal("returnedOn must be an ISO YYYY-MM-DD date", "returnedOn", "BadDate(field=returnedOn)"),
        """{"returnedOn":"2026-09-09"}""" to refusal("returnedOn may not be before lentOn", "returnedOn", "ReturnedBeforeLent"),
        """{"returnedOn":"2026-09-21"}""" to refusal("returnedOn may not be later than today", "returnedOn", "ReturnedAfterToday"),
    )

    private companion object {
        /** A fictional lookup link on the rule's shape: the key is made up and resolves to nobody. */
        const val LINK = "content://com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7"
        val LINKISH = Regex("(?i)uri|lookup|link(?!ed)")
    }
}
