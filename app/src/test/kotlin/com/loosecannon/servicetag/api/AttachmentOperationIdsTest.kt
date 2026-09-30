package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.MimeTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #92 (C13, row 17) — the upload's derived id, against the golden vector `docs/api/attachment-operation-ids.json`
 * that the MCP's Python twin reads (B5a). The vector was computed independently of this code.
 */
class AttachmentOperationIdsTest {

    private val golden: JsonObject =
        Json.parseToJsonElement(repoFile("docs/api/attachment-operation-ids.json").readText()).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test fun theGoldenVectorDerives() {
        assertEquals(OPERATION_ID_PREFIX, golden.text("prefix"))
        val vectors = golden.getValue("vectors").jsonArray.map { it.jsonObject }
        assertTrue(vectors.size >= 4)
        for (v in vectors) {
            val id = attachmentOperationId(v.text("installationId"), AssetId(v.text("assetId")), v.text("operationKey"))
            assertEquals(v.toString(), v.text("attachmentId"), id.value)
        }
    }

    @Test fun theIdIsAVersion8UuidInCanonicalForm() {
        val id = attachmentOperationId("5f0c2a9e-81d4-4b7a-9c3e-2d6f1a8b0e47", AssetId("a1"), "op-1").value
        assertTrue(id, Regex("[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
    }

    @Test fun distinctInstallationsOrAssetsGiveDistinctIdsForOneKey() {
        val one = attachmentOperationId("install-one", AssetId("a1"), "op-1")
        assertEquals(one, attachmentOperationId("install-one", AssetId("a1"), "op-1"))
        assertNotEquals(one, attachmentOperationId("install-two", AssetId("a1"), "op-1"))
        assertNotEquals(one, attachmentOperationId("install-one", AssetId("a2"), "op-1"))
        assertNotEquals(one, attachmentOperationId("install-one", AssetId("a1"), "op-2"))
        // The separator is part of the input: a boundary moved between two fields is another id.
        assertNotEquals(
            attachmentOperationId("ab", AssetId("c"), "k"),
            attachmentOperationId("a", AssetId("bc"), "k"),
        )
    }

    @Test fun theGoldenKindCasesAreTheServersInference() {
        val kinds = golden.getValue("kinds").jsonArray.map { it.jsonObject }
        assertEquals(
            setOf(AttachmentKind.DOCUMENT, AttachmentKind.PHOTO, AttachmentKind.OTHER),
            kinds.map { AttachmentKind.valueOf(it.text("kind")) }.toSet(),
        )
        for (case in kinds) {
            val inferred = AttachmentKinds.inferFrom(MimeTypes.normalise(case.text("contentType")), fromCamera = false)
            assertEquals(case.toString(), case.text("kind"), inferred.name)
        }
    }

    @Test fun anOperationKeyIsOneTo128UnreservedCharacters() {
        for (good in listOf("a", "op-1", "sha256:0b9f:17", "A.b_c~d:e-f", "k".repeat(128))) assertTrue(good, isOperationKey(good))
        for (bad in listOf("", "k".repeat(129), "has space", "slash/", "é", "q?x", "a\n")) {
            assertTrue(bad, !isOperationKey(bad))
        }
    }
}
