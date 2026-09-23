package com.loosecannon.servicetag.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The JVM half of the missing-handler hazard (plan §18.8). The other half is drawn: the References
 * section's snackbar is read off the semantics tree in `ReferencesSectionTest`, which a `Toast`
 * could never be — this case is what keeps the two from drifting, because the section shows
 * [NO_HANDLER_MESSAGE] itself.
 *
 * `NO_HANDLER_MESSAGE` is a `const`, so the compiler inlines it here and nothing loads
 * `LinkLauncher` itself — which is just as well, the object being all `Activity` and `Toast`.
 */
class LinkLauncherTest {

    @Test fun theMissingHandlerRefusalIsTheRatifiedSentenceAndNamesNoUri() {
        assertEquals("No app can open this link", NO_HANDLER_MESSAGE)
    }

    /**
     * The shipped line was `"No app can open this link:\n$uri"`, so the two things that made it
     * name a URI are what this asserts are gone: an interpolation or a format placeholder, and the
     * second row it was printed on.
     */
    @Test fun theRefusalCarriesNoPlaceholderForAUriToBeDroppedBackInto() {
        assertFalse("no interpolation", NO_HANDLER_MESSAGE.contains("$"))
        assertFalse("no format placeholder", NO_HANDLER_MESSAGE.contains("%"))
        assertFalse("one line", NO_HANDLER_MESSAGE.contains("\n"))
        assertFalse("nothing trails it", NO_HANDLER_MESSAGE.trimEnd().endsWith(":"))
    }
}
