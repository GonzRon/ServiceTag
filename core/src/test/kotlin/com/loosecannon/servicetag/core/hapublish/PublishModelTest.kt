package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #113/#114 (C1, C14, C14a; rows 43–45) — the outbound model holds no secret, maps #16's transport kinds
 * exhaustively, allows exactly two service actions and declares the invalid date once.
 */
class PublishModelTest {

    private val packagePath = "com/loosecannon/servicetag/core/hapublish"

    /** Every class compiled from `C/hapublish`'s main sources: the directory [PublishBinding] was loaded from. */
    private fun mainClasses(): List<Class<*>> {
        val root = File(PublishBinding::class.java.protectionDomain.codeSource.location.toURI())
        val dir = File(root, packagePath)
        val loader = PublishBinding::class.java.classLoader
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { file ->
                val relative = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                Class.forName(relative, false, loader)
            }
            .toList()
    }

    // Row 43 — G4: no model type carries a token.

    @Test
    fun noModelTypeHasASecretField() {
        val classes = mainClasses()
        val names = classes.map { it.simpleName }.toSet()
        for (expected in listOf("PublishBinding", "PublishDelivery", "DecommissionTombstone", "ValidationResult", "RemoteEntity", "Snap")) {
            assertTrue(expected in names, "the scan found $expected")
        }
        val forbidden = listOf("token", "secret", "bearer", "password", "authorization")
        for (type in classes) {
            for (field in type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }) {
                assertTrue(field.type != Secret::class.java, "${type.name}.${field.name} is a Secret")
                // A yes/no answer (the snapshot's `hasToken`) carries nothing of a token; any other type could.
                if (field.type == Boolean::class.javaPrimitiveType) continue
                val name = field.name.lowercase()
                assertTrue(forbidden.none { it in name }, "${type.name}.${field.name}")
            }
        }
    }

    @Test
    fun everyKindMapsFromSyncErrorKindExhaustively() {
        val expected = mapOf(
            SyncErrorKind.ENDPOINT_REFUSED to PublishErrorKind.ENDPOINT_REFUSED,
            SyncErrorKind.DENIED to PublishErrorKind.DENIED,
            SyncErrorKind.UNREACHABLE to PublishErrorKind.UNREACHABLE,
            SyncErrorKind.TIMED_OUT to PublishErrorKind.TIMED_OUT,
            SyncErrorKind.AUTH_REFUSED to PublishErrorKind.AUTH_REFUSED,
            SyncErrorKind.ENTITY_NOT_FOUND to PublishErrorKind.ENTITY_NOT_FOUND,
            SyncErrorKind.REDIRECTED to PublishErrorKind.REDIRECTED,
            SyncErrorKind.HTTP_ERROR to PublishErrorKind.HTTP_ERROR,
            SyncErrorKind.MALFORMED to PublishErrorKind.MALFORMED,
            SyncErrorKind.UNSUPPORTED_STATE to PublishErrorKind.MALFORMED,
            SyncErrorKind.NEEDS_TOKEN to PublishErrorKind.NEEDS_TOKEN,
            SyncErrorKind.NOT_MAINTAINED_HERE to PublishErrorKind.MALFORMED,
            SyncErrorKind.NOT_MANUAL to PublishErrorKind.MALFORMED,
            SyncErrorKind.DATE_BEFORE_HISTORY to PublishErrorKind.MALFORMED,
            SyncErrorKind.NOT_ON_LOCAL_NETWORK to PublishErrorKind.NOT_ON_LOCAL_NETWORK,
            SyncErrorKind.NAME_NOT_LOCAL to PublishErrorKind.NAME_NOT_LOCAL,
            SyncErrorKind.TLS_FAILED to PublishErrorKind.TLS_FAILED,
        )
        assertEquals(SyncErrorKind.entries.toSet(), expected.keys, "every #16 kind is in the table")
        for ((sync, publish) in expected) assertEquals(publish, publishKindOf(sync), "$sync")

        // Every shared transport member keeps its name.
        val shared = PublishErrorKind.entries.map { it.name }.toSet()
        for (sync in SyncErrorKind.entries.filter { it.name in shared }) {
            assertEquals(sync.name, publishKindOf(sync).name)
        }
    }

    // Row 44 — G2, C14a: the allowlist is two actions, no more.

    @Test
    fun theAllowlistHasExactlyTwoActions() {
        assertEquals(
            listOf(
                Triple("input_select", "select_option", "option"),
                Triple("input_datetime", "set_datetime", "date"),
            ),
            HaServiceAction.entries.map { Triple(it.domain, it.service, it.valueKey) },
        )
        assertEquals(listOf("SELECT_OPTION", "SET_DATE"), HaServiceAction.entries.map { it.name })
    }

    @Test
    fun actionOfIsExhaustive() {
        assertEquals(HaServiceAction.SELECT_OPTION, actionOf(PublishKind.EQUIPMENT_STATUS))
        assertEquals(HaServiceAction.SET_DATE, actionOf(PublishKind.SEASON_SERVICE_DATE))
        assertEquals(HaServiceAction.entries.toSet(), PublishKind.entries.map(::actionOf).toSet(), "each action has its kind")
    }

    // Row 45 — C1: the invalid date, declared once.

    @Test
    fun invalidDateIsDeclaredOnce() {
        assertEquals("1970-01-01", INVALID_SERVICE_DATE)
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(sources.isNotEmpty(), "core's main sources are readable from the test")
        val literal = "\"1970-01-01\""
        val sites = sources.flatMap { file -> file.readLines().filter { literal in it }.map { "${file.name}: ${it.trim()}" } }
        assertEquals(listOf("PublishModel.kt: const val INVALID_SERVICE_DATE = $literal"), sites)
    }
}
