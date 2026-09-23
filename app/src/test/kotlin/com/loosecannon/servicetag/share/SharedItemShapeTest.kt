package com.loosecannon.servicetag.share

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two hierarchies are mapped in **one** direction, by `SharedShare.asSharedItem`, and every
 * argument there is named — so a field added to either side is a compile error rather than a value
 * silently dropped. This pins the other half of that: the member sets and the field sets
 * themselves, so a field added to **both** sides and forgotten in the mapping still fails, and so
 * does a fifth member of either sealed interface.
 *
 * Read off the compiled classes rather than the source, so it cannot be satisfied by a comment.
 */
class SharedItemShapeTest {

    private fun fields(type: Class<*>): Map<String, String> = type.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .associate { it.name to it.type.simpleName }

    private fun members(type: Class<*>): Set<String> =
        type.declaredClasses.map { it.simpleName }.toSet()

    @Test fun bothSealedInterfacesCarryExactlyTheSameFourMembers() {
        assertEquals(setOf("Link", "Bytes", "PlainText", "Refused"), members(SharedItem::class.java))
        assertEquals(setOf("Link", "Bytes", "PlainText", "Refused"), members(ShareContent::class.java))
    }

    @Test fun theThreeUriFreeMembersAreFieldForFieldIdentical() {
        assertEquals(fields(ShareContent.Link::class.java), fields(SharedItem.Link::class.java))
        assertEquals(
            fields(ShareContent.PlainText::class.java),
            fields(SharedItem.PlainText::class.java),
        )
        assertEquals(fields(ShareContent.Refused::class.java), fields(SharedItem.Refused::class.java))
    }

    /** The one declared difference, and the reason both types exist: the accepted stream's `Uri`. */
    @Test fun bytesDiffersByExactlyTheUri() {
        assertEquals(
            fields(ShareContent.Bytes::class.java) + ("uri" to "Uri"),
            fields(SharedItem.Bytes::class.java),
        )
    }

    /** `SharedShare` is what the lift hands back, so its own shape is worth saying out loud too. */
    @Test fun theLiftHandsBackTheDecisionTheUriAndTheSource() {
        assertEquals(
            setOf("content", "streamUri", "bytes"),
            fields(SharedShare::class.java).keys,
        )
    }
}
