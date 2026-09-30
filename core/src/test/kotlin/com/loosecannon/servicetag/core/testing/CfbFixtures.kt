package com.loosecannon.servicetag.core.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OLE2 compound files for rows 38–39 (#85 C29), laid out by hand to MS-CFB §2.2–2.6: the header, sector 0
 * the one FAT sector, the directory from sector 1 (as many sectors as its entries need, chained in the FAT),
 * then one sector of filler standing in for the streams, which the validator never reads. Names are the
 * formats' own; every stream holds filler only.
 */
object CfbFixtures {
    const val END = 0xFFFF_FFFEL
    const val FREE = 0xFFFF_FFFFL
    private const val FAT_SECTOR = 0xFFFF_FFFDL
    private const val FILLER = "Example Pool Pump manual"
    private val signature = listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).map { it.toByte() }.toByteArray()

    /** Header field offsets (MS-CFB §2.2). */
    const val MAJOR = 26
    const val BYTE_ORDER = 28
    const val SECTOR_SHIFT = 30
    const val MINI_SHIFT = 32
    const val FIRST_DIRECTORY = 48
    const val FIRST_DIFAT = 68
    const val DIFAT_COUNT = 72
    const val DIFAT_SLOTS = 76

    class Entry(val name: String, val type: Int)

    fun stream(name: String) = Entry(name, 2)

    fun storage(name: String) = Entry(name, 1)

    /** A version 3 ([shift] 9: 512-byte sectors) or version 4 ([shift] 12: 4,096) file of a root entry and [entries]. */
    fun cfb(vararg entries: Entry, shift: Int = 9): ByteArray {
        val size = 1 shl shift
        val all = listOf(Entry("Root Entry", 5)) + entries
        val directory = (all.size * 128 + size - 1) / size
        val filler = directory + 1
        val b = ByteBuffer.allocate((filler + 2) * size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(signature).putShort(24, 0x3E).putShort(MAJOR, (if (shift == 12) 4 else 3).toShort())
            .putShort(BYTE_ORDER, 0xFFFE.toShort()).putShort(SECTOR_SHIFT, shift.toShort()).putShort(MINI_SHIFT, 6)
            .putInt(40, if (shift == 12) directory else 0).putInt(44, 1).putInt(FIRST_DIRECTORY, 1).putInt(56, 4_096)
            .putInt(60, END.toInt()).putInt(FIRST_DIFAT, END.toInt())
        for (k in 0 until 109) b.putInt(DIFAT_SLOTS + 4 * k, if (k == 0) 0 else FREE.toInt())
        for (i in 0 until size / 4) {
            val next = when {
                i == 0 -> FAT_SECTOR
                i < directory -> i + 1L
                i <= filler -> END
                else -> FREE
            }
            b.putInt(fatEntry(i, shift), next.toInt())
        }
        all.forEachIndexed { i, e ->
            val at = directoryEntry(i, shift)
            e.name.toByteArray(Charsets.UTF_16LE).forEachIndexed { j, byte -> b.put(at + j, byte) }
            b.putShort(at + 64, ((e.name.length + 1) * 2).toShort()).put(at + 66, e.type.toByte()).put(at + 67, 1)
            b.putInt(at + 68, FREE.toInt()).putInt(at + 72, if (i in 1 until all.lastIndex) i + 1 else FREE.toInt())
                .putInt(at + 76, if (i == 0 && all.size > 1) 1 else FREE.toInt())
                .putInt(at + 116, if (i == 0) filler else 0).putInt(at + 120, if (i == 0) size else FILLER.length)
        }
        FILLER.toByteArray().forEachIndexed { j, byte -> b.put((filler + 1) * size + j, byte) }
        return b.array()
    }

    /** Where FAT entry [id] sits in a [cfb] file (the FAT is sector 0). */
    fun fatEntry(id: Int, shift: Int = 9) = (1 shl shift) + 4 * id

    /** Where directory entry [index] starts in a [cfb] file (entry 0 is the root; the directory starts at sector 1). */
    fun directoryEntry(index: Int, shift: Int = 9) = 2 * (1 shl shift) + 128 * index

    /** A copy of [file] with [value] written little-endian over [width] bytes at [at]. */
    fun patched(file: ByteArray, at: Int, value: Long, width: Int = 4): ByteArray =
        file.copyOf().also { b -> repeat(width) { b[at + it] = (value shr (8 * it)).toByte() } }
}
