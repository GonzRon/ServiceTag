package com.loosecannon.servicetag.core.journal

import com.loosecannon.servicetag.core.model.AssetCategory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** The catalog read model (#74, C3; R74-10): built-ins first with their hints, the owner's rows after. */
class CategoryCatalogTest {

    private val builtIns = listOf(
        CategoryChoice("Generator", "generator", true, "power_equipment"),
        CategoryChoice("Lawn mower", "lawn mower", true, "power_equipment"),
        CategoryChoice("Snowblower", "snowblower", true, "power_equipment"),
        CategoryChoice("UPS", "ups", true, "ups"),
        CategoryChoice("Battery", "battery", true, null),
        CategoryChoice("Inverter / charger", "inverter / charger", true, null),
        CategoryChoice("Solar charge controller", "solar charge controller", true, null),
        CategoryChoice("RO system", "ro system", true, "ro_water"),
        CategoryChoice("Hot tub", "hot tub", true, "hot_tub"),
        CategoryChoice("HVAC", "hvac", true, null),
        CategoryChoice("Pump", "pump", true, null),
        CategoryChoice("Other", "other", true, "generic"),
    )

    private fun row(key: String, display: String) = AssetCategory(key, display, createdAt = 100L, updatedAt = 100L)

    /**
     * Built-ins in compiled order, then the rows by `CASE_INSENSITIVE_ORDER` on display — so a
     * lower-case `appliance` sorts before `Backup power`, which natural order would not do — and a
     * display tie by key. A row never carries a hint.
     */
    @Test
    fun builtInsFirstThenRowsAlphabeticallyWithoutHints() {
        val rows = listOf(
            row("water heater", "Water heater"),
            row("backup power", "Backup power"),
            row("appliance", "appliance"),
            row("same-b", "Same"),
            row("same-a", "same"),
        )
        assertEquals(
            builtIns + listOf(
                CategoryChoice("appliance", "appliance", false, null),
                CategoryChoice("Backup power", "backup power", false, null),
                CategoryChoice("same", "same-a", false, null),
                CategoryChoice("Same", "same-b", false, null),
                CategoryChoice("Water heater", "water heater", false, null),
            ),
            CategoryCatalog.choices(rows),
        )
        assertEquals(builtIns, CategoryCatalog.choices(emptyList()))
    }

    /** A row under a built-in's key (a built-in added by a later release) is dropped; the built-in wins. */
    @Test
    fun aRowUnderABuiltInsKeyIsDropped() {
        val choices = CategoryCatalog.choices(listOf(row("hot tub", "HOT TUB"), row("appliance", "Appliance")))
        assertEquals(builtIns + CategoryChoice("Appliance", "appliance", false, null), choices)
        assertEquals(listOf(CategoryChoice("Hot tub", "hot tub", true, "hot_tub")), choices.filter { it.key == "hot tub" })
    }

    /** `resolve` is by key: any spacing or case of a row's or a built-in's name finds it. */
    @Test
    fun resolveIsByKey() {
        val rows = listOf(row("water heater", "Water heater"))
        assertEquals(CategoryChoice("Water heater", "water heater", false, null), CategoryCatalog.resolve("  water   HEATER ", rows))
        assertEquals(CategoryChoice("Hot tub", "hot tub", true, "hot_tub"), CategoryCatalog.resolve("hot  TUB", rows))
        assertNull(CategoryCatalog.resolve("Chicken coop", rows))
        assertNull(CategoryCatalog.resolve("   ", rows))
    }
}
