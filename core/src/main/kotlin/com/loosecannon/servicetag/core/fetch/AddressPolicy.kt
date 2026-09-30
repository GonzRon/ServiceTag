package com.loosecannon.servicetag.core.fetch

/**
 * The resolver port (#85 C9, R85-7 amended): `:core` asks, the app's adapter answers, and every JVM test
 * stays local. An address is its raw bytes — 4 for IPv4, 16 for IPv6 — so no `java.net` type enters
 * `:core`.
 *
 * A literal host is resolved like a name (the adapter parses the canonical form), so literals and names
 * meet one rule. DNS rebinding between this answer and the connect is a recorded limit (plan §1), never a
 * reason to fall back to checking literals only.
 */
fun interface HostResolver {
    /**
     * Every address [host] resolves to now. Throws [TransportFailure] with `UNREACHABLE` when it resolves to
     * none, and `DENIED` when the network permission is not granted. An empty answer is read as unreachable.
     */
    suspend fun resolve(host: String): List<ByteArray>
}

/**
 * C9's address set, stated once and matched by row 10 — exactly this and nothing broader:
 * - IPv4 `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `172.16.0.0/12`,
 *   `192.168.0.0/16`, `224.0.0.0/4`, `240.0.0.0/4`;
 * - IPv6 `::/128`, `::1/128`, `fe80::/10`, `fec0::/10`, `fc00::/7`, `ff00::/8`;
 * - the IPv4 list applied to an embedded IPv4: the last four bytes of `::ffff:0:0/96`, `64:ff9b::/96`,
 *   `64:ff9b:1::/48` (RFC 8215) and `::/96`; bytes 2–5 of `2002::/16` (6to4);
 * - an address of any length other than 4 or 16 bytes.
 *
 * Everything else is allowed, **including the documentation ranges** `192.0.2.0/24`, `198.51.100.0/24`,
 * `203.0.113.0/24` and `2001:db8::/32` (owner, 2026-09-29), which the tests use as public stand-ins.
 */
fun isForbiddenAddress(address: ByteArray): Boolean = when (address.size) {
    4 -> isForbiddenIpv4(address, 0)
    16 -> isForbiddenIpv6(address)
    else -> true
}

private fun isForbiddenIpv4(bytes: ByteArray, at: Int): Boolean {
    val a = bytes.octet(at)
    val b = bytes.octet(at + 1)
    return a == 0 ||                        // 0.0.0.0/8
        a == 10 ||                          // 10.0.0.0/8
        (a == 100 && b in 64..127) ||       // 100.64.0.0/10
        a == 127 ||                         // 127.0.0.0/8
        (a == 169 && b == 254) ||           // 169.254.0.0/16
        (a == 172 && b in 16..31) ||        // 172.16.0.0/12
        (a == 192 && b == 168) ||           // 192.168.0.0/16
        a >= 224                            // 224.0.0.0/4 and 240.0.0.0/4
}

private fun isForbiddenIpv6(bytes: ByteArray): Boolean {
    val a = bytes.octet(0)
    val b = bytes.octet(1)
    return when {
        bytes.zeroIn(0, 16) -> true                                             // ::/128
        bytes.zeroIn(0, 15) && bytes.octet(15) == 1 -> true                     // ::1/128
        a == 0xfe && (b and 0xc0) == 0x80 -> true                               // fe80::/10
        a == 0xfe && (b and 0xc0) == 0xc0 -> true                               // fec0::/10
        (a and 0xfe) == 0xfc -> true                                            // fc00::/7
        a == 0xff -> true                                                       // ff00::/8
        bytes.zeroIn(0, 10) && bytes.octet(10) == 0xff && bytes.octet(11) == 0xff ->
            isForbiddenIpv4(bytes, 12)                                          // ::ffff:0:0/96
        bytes.zeroIn(0, 12) -> isForbiddenIpv4(bytes, 12)                       // ::/96
        bytes.startsWith(0x00, 0x64, 0xff, 0x9b) && bytes.zeroIn(4, 12) ->
            isForbiddenIpv4(bytes, 12)                                          // 64:ff9b::/96
        bytes.startsWith(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01) ->
            isForbiddenIpv4(bytes, 12)                                          // 64:ff9b:1::/48
        a == 0x20 && b == 0x02 -> isForbiddenIpv4(bytes, 2)                     // 2002::/16
        else -> false
    }
}

private fun ByteArray.octet(index: Int): Int = this[index].toInt() and 0xff

/** Every byte in `[from, until)` is zero. */
private fun ByteArray.zeroIn(from: Int, until: Int): Boolean = (from until until).all { this[it] == 0.toByte() }

private fun ByteArray.startsWith(vararg prefix: Int): Boolean = prefix.indices.all { octet(it) == prefix[it] }
