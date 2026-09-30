package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #86 (C20; R86-18) — the one read-only succession route and the `/v1/status` count, over the production router,
 * handlers and serializers.
 *
 * `GET /v1/assets/{id}/succession` answers 200 `{"replaces": <row>|null, "replacedBy": <row>|null}`, each row the
 * archive's own `{id, predecessorAssetId, successorAssetId, replacedOn, createdAt}`, both keys always present; an
 * asset that is not there is the shipped 404 `no_such_asset`, and any other verb is a 404 — the `/v1/assets/{id}/…`
 * sub-resource convention. **No route records a succession**: only the phone's Replace asset does (a merge apply
 * only inserts an archive's rows), so no verb or path here appends, amends or removes one. The answers are read as raw JSON, so the wire shape — its key
 * names, its explicit nulls — is what is pinned, not a Kotlin class that could drift with it.
 */
class SuccessionRoutesTest {

    private val graph = FakeGraph()
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun succession(id: String, predecessor: String, successor: String, replacedOn: String, createdAt: Long) =
        AssetSuccession(id, AssetId(predecessor), AssetId(successor), replacedOn, createdAt)
            .also { runBlocking { graph.assetSuccessions.append(it) } }

    /** The archive row's shape, written out key by key: the wire must say exactly this. */
    private fun wire(row: AssetSuccession): JsonObject = buildJsonObject {
        put("id", row.id)
        put("predecessorAssetId", row.predecessorAssetId.value)
        put("successorAssetId", row.successorAssetId.value)
        put("replacedOn", row.replacedOn)
        put("createdAt", row.createdAt)
    }

    private fun answer(replaces: JsonElement, replacedBy: JsonElement): JsonObject = buildJsonObject {
        put("replaces", replaces)
        put("replacedBy", replacedBy)
    }

    private fun read(asset: String): JsonObject {
        val response = api.call("GET", "/v1/assets/$asset/succession")
        assertEquals("GET /v1/assets/$asset/succession: ${response.bodyText()}", 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject
    }

    private fun stored(): List<AssetSuccession> = runBlocking { graph.assetSuccessions.all() }

    private fun counts(): JsonObject =
        Json.parseToJsonElement(api.call("GET", "/v1/status").bodyText()).jsonObject.getValue("counts").jsonObject

    @Test fun aPredecessorAnswersReplacedBy() {
        val old = api.asset("Example Water Heater")
        val new = api.asset("Example Water Heater (new)")
        val row = succession("s1", old, new, "2026-09-20", 1_758_960_000_000L)

        assertEquals(answer(replaces = JsonNull, replacedBy = wire(row)), read(old))
    }

    @Test fun aSuccessorAnswersReplaces() {
        val old = api.asset("Sample Pool Pump")
        val new = api.asset("Sample Pool Pump (new)")
        val row = succession("s1", old, new, "2026-09-18", 1_758_700_000_000L)

        assertEquals(answer(replaces = wire(row), replacedBy = JsonNull), read(new))
    }

    /** A → B → C is two rows (I3): the middle asset answers both, each from its own end — and the read writes nothing. */
    @Test fun aChainAnswersBoth() {
        val first = api.asset("Example Garage Door Opener")
        val second = api.asset("Example Garage Door Opener (2)")
        val third = api.asset("Example Garage Door Opener (3)")
        val earlier = succession("s-a", first, second, "2025-04-02", 1_743_600_000_000L)
        val later = succession("s-b", second, third, "2026-09-01", 1_756_700_000_000L)
        val before = stored()

        assertEquals(answer(replaces = wire(earlier), replacedBy = wire(later)), read(second))
        assertEquals(answer(replaces = JsonNull, replacedBy = wire(earlier)), read(first))
        assertEquals(answer(replaces = wire(later), replacedBy = JsonNull), read(third))
        assertEquals("a read writes nothing", before, stored())
    }

    /** Both keys are always there: an asset outside every succession answers two explicit nulls. */
    @Test fun anUnrelatedAssetAnswersNulls() {
        val old = api.asset("Example Water Heater")
        val new = api.asset("Example Water Heater (new)")
        succession("s1", old, new, "2026-09-20", 1_758_960_000_000L)
        val loner = api.asset("Sample Pool Pump")

        val body = read(loner)
        assertEquals(answer(replaces = JsonNull, replacedBy = JsonNull), body)
        assertEquals(listOf("replaces", "replacedBy"), body.keys.toList())
    }

    @Test fun anUnknownAssetIs404() {
        val old = api.asset("Example Water Heater")
        val new = api.asset("Example Water Heater (new)")
        succession("s1", old, new, "2026-09-20", 1_758_960_000_000L)

        val missing = api.call("GET", "/v1/assets/no-such-asset/succession")
        assertEquals(missing.bodyText(), 404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
    }

    /**
     * R86-18: no write route. Every other verb on the sub-resource is the convention's 404, and so is every path a
     * client would plausibly try to make or remove one — and none of them writes a row.
     */
    @Test fun aPostIs404() {
        val old = api.asset("Example Water Heater")
        val new = api.asset("Example Water Heater (new)")
        succession("s1", old, new, "2026-09-20", 1_758_960_000_000L)
        val spare = api.asset("Sample Pool Pump")
        val before = stored()
        val body = """{"predecessorAssetId":"$new","successorAssetId":"$spare","replacedOn":"2026-09-21"}"""

        for ((method, path) in listOf(
            "POST" to "/v1/assets/$new/succession",
            "PATCH" to "/v1/assets/$old/succession",
            "DELETE" to "/v1/assets/$old/succession",
            "POST" to "/v1/successions",
            "GET" to "/v1/successions",
            "DELETE" to "/v1/successions/s1",
            "GET" to "/v1/assets/$old/succession/s1",
        )) {
            val response = api.call(method, path, if (method == "GET" || method == "DELETE") "" else body)
            assertEquals("$method $path: ${response.bodyText()}", 404, response.status)
        }
        assertEquals("nothing was written", before, stored())
    }

    /** The count, under the archive's own list name, beside every shipped key: none on a fresh phone, each row counted. */
    @Test fun statusCountsAssetSuccessions() {
        assertEquals(0, counts().getValue("assetSuccessions").jsonPrimitive.int)

        val a = api.asset("Example Garage Door Opener")
        val b = api.asset("Example Garage Door Opener (2)")
        val c = api.asset("Example Garage Door Opener (3)")
        succession("s-a", a, b, "2025-04-02", 1_743_600_000_000L)
        succession("s-b", b, c, "2026-09-01", 1_756_700_000_000L)

        assertEquals(2, counts().getValue("assetSuccessions").jsonPrimitive.int)
    }
}
