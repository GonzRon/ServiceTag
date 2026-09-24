# #63 — an unreadable foreign stream goes straight to the read-failure dead end

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md` (one task review; controller inspection for
> mechanical fixes). One brief, one implementer.

**Goal:** when another app shares a `content://` stream that ServiceTag cannot read at all — the
provider is not visible (Android package-visibility filtering, before that package has ever granted
ServiceTag a URI) or has no facts to give — the intake draws the ratified **"Could not read what was
shared"** dead end at read time, never the byte-share form with an empty Received line.

**Spec:** GitHub issue #63 (found by #62's `ShareBoundaryTest`), the share-intake spec
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §4.3 (a read failure is
`UNREADABLE`), and the owner's ruling of 2026-09-24: "a null provider/query result for an unreadable
foreign stream should go directly to the existing UNREADABLE dead end; after the fix, remove the
boundary test's artificial 'grant one URI first' precondition."

## Global constraints

- **No new string.** `IntakeStrings.UNREADABLE` is reused; nothing else is drawn or added.
- **No manifest, API, MCP or schema change.** `share/**` only, plus its tests.
- The fix lives in the **read** path (the lift, `SharedItem.kt`), not in the view model's save
  path: the first frame after the read must already be the dead end.
- Commits by `git -c user.name=GonzRon -c user.email="$(git log -1 --format=%ae master)"`, one casual
  single-line subject each, no body, no trailers, no attribution. Fictional nouns only.
- Every changed behaviour is shown RED first by a named mutation; the report says which.

## Task 1 — the read path

**Files:** modify `app/src/main/kotlin/com/loosecannon/servicetag/share/SharedItem.kt` (the
stream-facts step that today lets a `null` query result through as "no facts"); modify
`app/src/test/kotlin/com/loosecannon/servicetag/share/SharedItemReaderTest.kt`.

**Contract.** In the byte arm, after I-9's own-authority refusal and before any open: if the resolver's
query for the stream's facts returns **null** (no provider, or the provider is not visible to this
package), or the cursor yields no row, the share is `Refused(UNREADABLE)`. Nothing is opened, nothing
is staged. A provider that answers facts but then refuses `openInputStream` stays the read failure it
already is (the save-time `SecurityException` path is unchanged and still tested).

**Invariant (I-11 of the share intake, new):** a stream whose facts cannot be read is UNREADABLE at
read time; the byte-share form is drawn only for a stream with a name, a type and a readable size
answer (a size may still be absent — an undeclared size is probed later, as 1.3.0 ships).

**Test matrix (JVM, `SharedItemReaderTest`):**

| hazard | test | RED mutation |
|---|---|---|
| a null query result renders a form | `aStreamWhoseProviderCannotBeQueriedIsUnreadableBeforeAnyOpen` — a fake resolver whose `query` returns null: the decision is `Refused(UNREADABLE)` and the source is never opened (`opened == 0`) | revert the null check |
| an empty cursor renders a form | `aStreamWhoseFactsComeBackEmptyIsUnreadable` — a cursor with zero rows | revert the empty-cursor check |
| the fix over-reaches | the existing declared-size and undeclared-size cases stay green (a provider that answers facts is not affected) | — |

## Task 2 — the boundary proof loses its crutch

**Files:** modify `app/src/androidTest/kotlin/com/loosecannon/servicetag/share/ShareBoundaryTest.kt`
(and `TestSender.kt` only if the precondition helper lives there).

Remove the bad-grant case's "one granted share first" precondition: from a fresh install, an
ungranted foreign stream now lands on the dead end on the first attempt. Keep the three cases, the
20 s timeouts and the assertions. Update the case's KDoc (it cites #63 today as the reason for the
precondition; it now cites #63 as the fix). RED proof: with the production fix reverted and the
precondition removed, the case fails on "Could not read what was shared" not being displayed (the
empty form shape); with the fix, 3/0 from a fresh install (`pm clear` both packages first, so no
prior grant exists).

## Gate

- [ ] `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` green; app
      unit count moves by exactly the two new cases.
- [ ] `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.share.ShareBoundaryTest`
      3/0 after `pm clear` of both packages; then `ShareIntakeScreenTest` and `SharedItemLiftTest`
      once each (unchanged behaviour), then the whole suite once.
- [ ] `git diff --stat master..HEAD -- app/src/main/AndroidManifest.xml core docs/api tools` → empty;
      no string literal added under `share/**` (`git diff master..HEAD -- app/src/main | grep -c '^+.*"'`
      reported and explained).
- [ ] Hygiene; gitlink `7e0377a`; `git status` clean.

## This brief must NOT

Add a sentence. Touch the save path or the view model's catches. Change the sender, the runbook, the
tripwire or any test outside the three files named. Bump the version (the fix ships with the next
release).
