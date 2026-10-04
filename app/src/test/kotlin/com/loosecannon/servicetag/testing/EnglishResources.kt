package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.l10n.LocalizedText

/**
 * #102 — the JVM tests' default [LocalizedText]: the English source in `src/main/res/values/` ([ResourcePack.english]),
 * read the way Android reads it. `META-INF/services` registers it, so a unit test with no Android resources renders
 * every word the app would render on an English device, and the tests that pin ratified English wording keep pinning
 * it after the words moved out of Kotlin. A test that installs a language pack puts this back when it is done.
 */
class EnglishResources : LocalizedText by ResourcePack.english
