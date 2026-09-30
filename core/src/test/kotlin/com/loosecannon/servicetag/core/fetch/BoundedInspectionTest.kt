package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.testing.BytesStagedReader
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Row 34 (C26): the one budget of a container inspection. A read past it refuses the file; it never truncates. */
class BoundedInspectionTest {

    private val hundred = ByteArray(100) { it.toByte() }

    @Test
    fun readsAreExactAndShortAtTheEnd() {
        val inspection = BoundedInspection(BytesStagedReader.of(hundred))
        assertContentEquals(hundred.copyOfRange(10, 15), inspection.readAt(10, 5))
        assertContentEquals(hundred.copyOfRange(95, 100), inspection.readAt(95, 10), "short at the end")
        assertContentEquals(ByteArray(0), inspection.readAt(100, 4), "empty at the end")
        assertContentEquals(ByteArray(0), inspection.readAt(1_000, 4), "empty past the end")
        assertEquals(4, inspection.reads)
        assertEquals(160, inspection.maxReads)
        assertEquals(524_288, inspection.maxBytes)
    }

    @Test
    fun theReadCapRefuses() {
        val reader = BytesStagedReader.of(hundred)
        val inspection = BoundedInspection(reader)
        repeat(160) { inspection.readAt(it % 100L, 1) }
        assertFailsWith<InspectionOverBudget> { inspection.readAt(0, 1) }
        assertEquals(160, reader.reads, "the 161st read never reaches the file")
    }

    @Test
    fun theByteCapRefuses() {
        val big = ByteArray(600 * 1_024)
        // a 600 KiB central directory asked for in two reads
        val twoReads = BytesStagedReader.of(big)
        val two = BoundedInspection(twoReads)
        assertEquals(300 * 1_024, two.readAt(0, 300 * 1_024).size)
        assertFailsWith<InspectionOverBudget> { two.readAt(300 * 1_024L, 300 * 1_024) }
        assertEquals(1, twoReads.reads, "the second read never reaches the file")
        // exactly the cap passes; one byte more does not
        val exact = BoundedInspection(BytesStagedReader.of(big))
        assertEquals(524_288, exact.readAt(0, 524_288).size)
        assertFailsWith<InspectionOverBudget> { exact.readAt(0, 1) }
        // one read over the cap is refused before the file is touched
        val oneRead = BytesStagedReader.of(big)
        assertFailsWith<InspectionOverBudget> { BoundedInspection(oneRead).readAt(0, 524_289) }
        assertEquals(0, oneRead.reads)
    }

    @Test
    fun aNegativePositionIsRefused() {
        val reader = BytesStagedReader.of(hundred)
        val inspection = BoundedInspection(reader)
        assertFailsWith<InspectionOverBudget> { inspection.readAt(-1, 1) }
        assertFailsWith<InspectionOverBudget> { inspection.readAt(0, -1) }
        assertEquals(0, reader.reads)
    }

    @Test
    fun overBudgetIsARefusalNotAFailedRead() {
        assertFalse(IOException::class.java.isAssignableFrom(InspectionOverBudget::class.java))
    }
}
