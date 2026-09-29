package com.loosecannon.servicetag.ui.replace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #86 (C18; row 17) — the ratified P86 templates as data: each names the assets it is given, verbatim, and every
 * `<date>` is an ISO day drawn `d MMM uuuu` (P77-33's precedent). Fictional names only.
 */
class ReplaceStringsTest {

    @Test fun eachTemplateNamesItsAssets() {
        assertEquals(
            "Example Water Heater will be retired with its history, documents and service record kept as they are.",
            ReplaceStrings.willBeRetired("Example Water Heater"),
        )
        assertEquals("Add to Backyard", ReplaceStrings.addTo("Backyard"))
        assertEquals(
            "Example Pump Motor stays part of Sample Pool Pump.",
            ReplaceStrings.childrenStay(listOf("Example Pump Motor"), "Sample Pool Pump"),
        )
        assertEquals(
            "Example Filter Housing, Example Pump Motor stay part of Sample Pool Pump.",
            ReplaceStrings.childrenStay(listOf("Example Pump Motor", "Example Filter Housing"), "Sample Pool Pump"),
        )
        assertEquals(null, ReplaceStrings.childrenStay(emptyList(), "Sample Pool Pump"))
        assertEquals("Sample Pool Pump is lent out. The loan stays with it.", ReplaceStrings.lentOut("Sample Pool Pump"))
        assertEquals("Create Sample Pool Pump II", ReplaceStrings.create("Sample Pool Pump II"))
        assertEquals("Could not replace Sample Pool Pump. Nothing was changed.", ReplaceStrings.couldNotReplace("Sample Pool Pump"))
        assertEquals("Replaces Example Water Heater", ReplaceStrings.replaces("Example Water Heater"))
    }

    @Test fun theDateReadsDMmmUuuu() {
        assertEquals(
            "Example Water Heater was retired on 1 Mar 2026. Its history, documents and service record are kept as they are.",
            ReplaceStrings.wasRetiredOn("Example Water Heater", "2026-03-01"),
        )
        assertEquals("Retire Example Water Heater on 1 Mar 2026", ReplaceStrings.retireOn("Example Water Heater", "2026-03-01"))
        assertEquals(
            "Example Water Heater stays retired from 14 Jun 2026",
            ReplaceStrings.staysRetiredFrom("Example Water Heater", "2026-06-14"),
        )
        assertEquals(
            "Replaced by Sample Water Heater on 1 Mar 2026",
            ReplaceStrings.replacedBy("Sample Water Heater", "2026-03-01"),
        )
        assertEquals("Was the new asset in season on 15 Jun 2026?", ReplaceStrings.wasInSeasonOn("2026-06-15"))
    }
}
