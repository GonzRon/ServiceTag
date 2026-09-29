package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure

/**
 * A [HostResolver] that never touches a network: each host answers from [answers], in the order given;
 * a host it was not told about is `UNREACHABLE`, as a name that resolves to nothing is. [failure], when
 * set, is thrown for every host instead. [asked] records every host, in order, so a test can prove the
 * resolver was (or was never) consulted, and for which name.
 */
class FakeHostResolver(
    private val answers: Map<String, List<ByteArray>> = emptyMap(),
    var failure: Throwable? = null,
) : HostResolver {
    val asked = mutableListOf<String>()

    override suspend fun resolve(host: String): List<ByteArray> {
        asked += host
        failure?.let { throw it }
        return answers[host] ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
    }

    companion object {
        /** [host] resolving to each of [addresses], written as text ([address]). */
        fun of(vararg entries: Pair<String, List<String>>) =
            FakeHostResolver(entries.associate { (host, addresses) -> host to addresses.map(::address) })
    }
}

/**
 * The raw bytes of an address written as text: a dotted quad (4 bytes), or IPv6 text (16 bytes) with an
 * optional trailing dotted quad. Test-side and deliberately independent of the production parser — and
 * not `InetAddress`, which turns `::ffff:a.b.c.d` into a 4-byte address and would hide the unwrap.
 */
fun address(text: String): ByteArray {
    if (':' !in text) {
        val parts = text.split('.').map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 }) { "not a dotted quad: $text" }
        return ByteArray(4) { parts[it].toByte() }
    }
    val quad = if ('.' in text) address(text.substringAfterLast(':')) else null
    val hex = if (quad == null) text else text.substringBeforeLast(':') + ":0:0"
    val halves = hex.split("::")
    val head = halves[0].split(':').filter { it.isNotEmpty() }
    val tail = halves.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
    val groups = head + List(8 - head.size - tail.size) { "0" } + tail
    require(groups.size == 8) { "not IPv6 text: $text" }
    val bytes = ByteArray(16)
    groups.forEachIndexed { i, group ->
        val value = group.toInt(16)
        bytes[2 * i] = (value shr 8).toByte()
        bytes[2 * i + 1] = value.toByte()
    }
    quad?.copyInto(bytes, 12)
    return bytes
}
