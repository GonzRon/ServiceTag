# #70 — the overwrite confirmation names what the tag already identifies: plan and brief (rev 2, reviewed and RATIFIED 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md`. One brief (§8), one implementer, one task
> review, one batched fix round, at most one scoped re-review. Planned from issue #70's body under the
> owner's GO of 2026-09-26; rev 2 folds in the independent brief review (one MAJOR: the lookup must not
> suspend between recording the question and showing it; a package cycle; a bounded lookup; the
> placement's home). Owner ruling 2026-09-26: P70-1, P70-2, P70-3a, P70-3b, P70-6 RATIFIED verbatim; P70-4/P70-5
> stay the ratified inspect strings; R70-3 (the quiet line) and R70-5 (P70-6 distinct) RATIFIED; R70-1, R70-2,
> R70-4, R70-6, R70-7 stand; one editorial correction applied (a cancellation shows no sheet — it does not fall
> back to P70-6). GO.

**Goal:** when the tag being written already holds a different ServiceTag v1 identity, the overwrite
sheet says what that identity means to the owner — the Asset it is bound to, a spare tag, a lost or
retired tag, a pre-split link tag, or a ServiceTag tag this phone has no record of — with the tag id
and the tag's placement kept as one quiet shortened line, never as the explanation. The newer-app,
foreign and unreadable cases keep their present words. Nothing about overwrite authorisation or write
safety changes: every case still costs exactly one confirmation; a lookup that fails or takes too long
falls back to P70-6 and still asks; a lookup cancelled by the coroutine's lifecycle shows no sheet, writes
nothing, releases the tap and lets the next tap proceed (C7).

**Spec:** D12 (`docs/design/12-visual-design-apollo-service-binder.md`) §11 "Overwrite confirmation
(tag already holds something)", whose own sketch already names the asset on the tag, and G1
(`docs/design/g1/01-g1-visual-gate-report.md` §1.4 row "Overwrite confirmation": one warning line in
the due-soon container naming what is on the tag, Overwrite filled, Keep it outlined, brick never
used); D3 §9 (every way a scan can end is one `Resolution`; the UI switches on it and nothing else);
target §4.2 invariant 13 (the library builds no sentence); the consent invariant ratified 2026-09-17
and pinned by `WriteTagScreenConsentWordingTest` (confirming never writes through the stale handle;
the owner lifts the tag and holds it again); `TagWriteController`'s KDoc (invariant 10); issue #70's
safety invariant and AC 1–7.

## Global constraints

- **Write safety is untouched.** `OverwritePolicy.decide` (the library), `OverwriteReasons.decide`,
  `awaitingAnswer` / `confirmedOverwrite`, `busy`, `confirmOverwrite()`, `keepIt()`, the lift-and-retap
  contract and every write path stay byte-identical in behaviour. The lookup reaches the words and
  nothing else, and **no suspension point separates `awaitingAnswer = existing` from the state publish**.
- **One interpretation of tag identity.** The existing `ResolveTag` classification is reused through a
  read-only entry; no second resolver, no parallel `when` over `TagStatus` / `TagTarget` in the write flow.
- **Nothing is written by asking.** Resolving for the sheet records no scan and upserts no row; the
  store after the question equals the store before it.
- **Strings.** Every user-visible sentence is in §5, verbatim, ratified before dispatch. The three
  non-v1 sentences, the eyebrow, the explanation paragraph and both buttons are unchanged.
- Tests: JVM first (`:core`, `:app`); Compose instrumented tests on `emulator-5554` only, one class per
  Gradle invocation; no UI-driving harness. Hygiene (no e-mail addresses, `/home/<user>` paths,
  serials, private equipment names, locations, household nouns). Gitlink `libs/nfc-tag-core` stays
  `7e0377a`. Commits: one casual subject, no body, no trailers.

## 1. Audit (controller, read-only, done 2026-09-26; confirmed by the review)

**The write flow today.** `ui/scan/TagWriteController.handle()` inspects the tag, provisions the row,
decodes what the tag holds (`existingOn` → `TagPayload`) and asks the library
(`OverwriteReasons.decide(existing, row.id)`). On `OverwriteDecision.Confirm` it sets
`awaitingAnswer = existing` and publishes `WriteState.Confirm(OverwriteReasons.sentence(d))` — a
`String` — on adjacent lines with no suspension point between them (`:181-182`); the sheet then owns
`busy` until `confirmOverwrite()` or `keepIt()` (both public, `:217-232`) answers it. `WriteTagScreen`
draws the string as `"The tag already holds $reason."` in the due-soon surface of `OverwriteSheet`,
under the eyebrow `OVERWRITE THIS TAG?`, above the ratified explanation ("Replacing it will make the
tag identify <target>. The old content is lost. After you confirm, hold the same tag to the phone again
to write.") and the `Overwrite` / `Keep it` buttons. `core/nfc/OverwriteReasons.sentence()` words the
four confirmable reasons; for `OTHER_TAG_SAME_PRODUCT` it is `"a different ServiceTag tag (<full
uuid>)"` — the sentence #70 is about. The controller never resolves the existing id.

**The resolver.** `core/usecase/ResolveTag.run(payload)` maps a v1 payload through
`tags.findByPayload(V1, key)` to `OpenAsset(tag, asset)`, `Unbound(tag)` (status UNBOUND, target
`None`, or a dangling asset target), `Revoked(tag)` (status LOST or RETIRED — the row may still target
an existing asset), `PreSplitLink(tag)` (2.6 tombstone) or `UnknownV1(tagId)`; a newer payload to
`NeedsNewerApp`; foreign / malformed / empty to `NotOurs`. It runs inside `uow.write` and **records
the scan** (`copy(lastScannedAt = …)` + upsert) on a hit. Right for a scan, wrong for a question: the
write flow needs a read-only entry to the same classification (C1). `core/nfc` today depends only on
`core/model` and the library; `core/usecase` depends on `core/nfc` — so the wording that needs
`Resolution` lives in `core/usecase`, not `core/nfc` (C3).

**The vocabulary already ratified** (`ui/scan/TagResultSheet.kt`, `ScanViewModels.kt`): eyebrow
"ServiceTag tag" + the asset name + `identityLine()` = `"<first 8> · v1"` + an optional captioned
"Tag placement"; "Unregistered tag" / "This tag is not assigned to anything yet."; "Tag marked lost"
/ "Tag retired" / "This tag was taken out of service. Binding it again puts it back to work."; "This
ServiceTag tag is not in this phone's records."; `PRE_SPLIT_LINK_SENTENCE`. The product says
**phone**, not device. The tag id is shown shortened everywhere (G1 §3 correction a); the Written
state already appends words to the mono line (`"<8> · v1 · locked"`), the precedent for C5.

**Tests today.** `core/…/nfc/OverwriteReasonsTest` pins the four sentences (`differentV1IdConfirms`
and `reasonNamesTheTagThatIsThere` pin the uuid sentence); `core/…/usecase/ResolveTagTest` pins the
classification with in-memory repositories (`FakeUnitOfWork` counts `reads` and `commits`;
`InMemoryTagRepository.rows` is public); `app/…/ui/scan/TagWriteControllerTest` drives the controller
on the JVM over a Room-backed `FakeGraph` (`tags`, `assets`, `uow`, `clock`; no `resolveTag` member)
and a fake `TagIo`, reading `asked.reason` at `:149` and asserting `Confirm("unreadable …")` by value
at `:279`; `WriteTagScreenConsentWordingTest` passes `reason = state.reason` at `:41`. The only callers
of `sentence()` are the controller (`:182`) and its own test.

## 2. The behaviour (the brief's contract)

- **C1, a read-only resolution.** `ResolveTag` gains `suspend fun peek(payload: TagPayload): Resolution`:
  the same classification as `run()`, factored into one private suspend `classify(row)` both use
  (`run()` applies it to the row after its `lastScannedAt` copy, as today), executed inside `uow.read`,
  with no `lastScannedAt` update and no upsert. `run()` is unchanged in behaviour.
- **C2, the controller looks up first, then records and shows the question in one breath.** The
  controller gains a constructor parameter `resolveTag: ResolveTag` placed **before** the defaulted
  `ioDispatcher` (the `graph` constructor passes `graph.resolveTag`). In `handle()`'s `Confirm` branch,
  with `d` already decided: when `d.reason == OTHER_TAG_SAME_PRODUCT`, the lookup runs **first** —
  `withTimeoutOrNull(LOOKUP_BOUND) { resolveTag.peek(existing) }` inside `try` / `catch
  (CancellationException) { throw }` / `catch (Exception) { Log.w(...); null }` (the controller's own
  shape at `onTag`, `:128-133`; never `runCatching`, which would also catch `Error` and swallow
  cancellation), no `ioDispatcher` hop (Room manages its threads, as `provisionTag.begin` shows);
  `LOOKUP_BOUND = 2.seconds`, and a `null` from the bound is P70-6. For every other reason the
  resolution is `null`. **Then** `awaitingAnswer = existing` and
  `_state.value = WriteState.Confirm(OverwriteSubjects.of(d, resolution))` on adjacent lines with no
  suspension point between them, exactly as today. The lookup cannot change `d`, performs no tag I/O
  and no store write, and never touches `confirmedOverwrite` or the retap path. A cancelled lookup
  publishes nothing and releases `busy` through `onTag`'s existing `finally`; the next tap is handled.
- **C3, the presentation model, in `core/usecase`.** New file
  `core/usecase/OverwriteSubjects.kt` beside `Resolution`:
  `data class OverwriteSubject(val line: String, val identifier: String?)` — the one sentence the
  warning surface shows, and the quiet line under it (null when no id applies);
  `object OverwriteSubjects { fun of(c: OverwriteDecision.Confirm, resolution: Resolution?): OverwriteSubject }`:
  - `OTHER_TAG_SAME_PRODUCT`: `identifier = "<first 8 of c.detail> · v1"` — the rule of the app's
    `identityLine(key)` (pinned equal by test; `c.detail` is the payload key, and a known row's id
    equals its key by `ProvisionTag` and `BindTag`) — and, for a known row whose `label` is non-blank
    (the rule `ProvisionTag` and `placementOrNull()` use), `" · <placement>"` appended: `"<8> · v1 ·
    <placement>"`, the Written state's own shape. `line` by resolution: `OpenAsset(tag, asset)` → P70-1
    with the asset's name; `Unbound` → P70-2; `Revoked` with status LOST → P70-3a, RETIRED → P70-3b;
    `PreSplitLink` → P70-5; `UnknownV1` → P70-4; `null` → P70-6; `NeedsNewerApp` / `NotOurs` cannot
    arise from a v1 payload and map to P70-6 (honest, never a crash). No asset name can reach any
    non-bound line: `Unbound` and `Revoked` carry no `Asset`.
  - `SAME_PRODUCT_UNSUPPORTED`, `FOREIGN`, `UNREADABLE`: `line = "The tag already holds " +
    OverwriteReasons.sentence(c) + "."`, `identifier = null` — today's words, moved from the screen.
  - P70-1 is a function of the name (a template); the fixed sentences are `const val`s in the same
    file, each literal on one line.
  - `OverwriteReasons.sentence(c)` keeps the three non-v1 sentences verbatim; its
    `OTHER_TAG_SAME_PRODUCT` branch is retired to `error(...)` beside `EMPTY_TAG` / `SAME_TAG` (R70-4).
- **C4, the state carries the model.** `WriteState.Confirm(val subject: OverwriteSubject)` replaces
  `Confirm(val reason: String)`. `WriteStatus`'s `Confirm` arm (the `NfcSheet` with "Answer here, then
  hold the same tag to the phone again.") is unchanged.
- **C5, the sheet.** `OverwriteSheet(subject: OverwriteSubject, target, onOverwrite, onKeepIt)`: inside
  the due-soon surface, a `Column` keeping the existing `padding(12.dp)` holds `subject.line`
  (bodyMedium, as today) and, when `subject.identifier` is non-null, one more line under it in the
  theme's `MonoText` style (`ui/theme/Type.kt`, imported explicitly) at the surface's content colour —
  the id and placement stay inside the "what is on the tag" block, quiet and secondary. The eyebrow,
  the explanation paragraph and both buttons are unchanged. No captioned placement block (R70-3).
- **C6, nothing written by the question.** Around the whole ask — tap, sheet, Keep it, or Overwrite +
  retap — the tag rows other than the provisioned one, `lastScannedAt` included, are byte-identical
  before and after (the provisioned row's lifecycle is today's, unchanged).
- **C7, the guard under failure.** A resolver that throws → the tap still ends in
  `WriteState.Confirm` (P70-6 + identifier), the sheet owns `busy`, `keepIt()` writes nothing, and on
  a fresh tap `confirmOverwrite()` + retap writes exactly as today. A resolver that hangs → after
  `LOOKUP_BOUND` the same P70-6 sheet; the tap is never dropped for good. A lookup cancelled → no
  sheet, no write, `busy` released, the next tap handled. While the lookup runs, `keepIt()` and
  `confirmOverwrite()` are no-ops (`awaitingAnswer` is still null) and the sheet, when it comes, works.

## 3. Files (indicative; the implementer owns the placement)

**Modify**
- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ResolveTag.kt` — `peek()`, `classify(row)`.
- `core/src/main/kotlin/com/loosecannon/servicetag/core/nfc/OverwriteReasons.kt` — `sentence()`'s
  retired v1 branch (nothing else; `core/nfc` keeps its dependencies).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/scan/TagWriteController.kt` — the parameter, the
  bounded lookup, the state.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/scan/WriteTagScreen.kt` — `OverwriteSheet`'s
  parameter and the mono line; the `asking` wiring at `:104-110`.
- Tests (mechanical re-anchors first): `app/src/test/…/ui/scan/TagWriteControllerTest.kt` (`controller()`
  supplies `resolveTag = ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock)`; `:149` reads
  `asked.subject.line`; `:279` asserts the new `Confirm` value), `app/src/androidTest/…/ui/scan/WriteTagScreenConsentWordingTest.kt`
  (`subject = state.subject`; the fixture), `core/…/nfc/OverwriteReasonsTest.kt` (the two uuid cases
  re-anchored), `core/…/usecase/ResolveTagTest.kt`; then the new cases of §4.

**Create**
- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/OverwriteSubjects.kt` (C3).
- `core/src/test/kotlin/com/loosecannon/servicetag/core/usecase/OverwriteSubjectsTest.kt`.
- `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/scan/OverwriteSheetSubjectTest.kt`.

**Untouched:** `OverwritePolicy` (the library), `ScanViewModels.kt` (`ScanViewModel`,
`TagResultViewModel`, `WriteTagViewModel`, `identityLine`, `PRE_SPLIT_LINK_SENTENCE`),
`TagResultSheet.kt`, `ProvisionTag`, `BindTag`, the repositories, the schema, the backup format, the
API, the MCP, `AppGraph.kt` (it already holds `resolveTag`), `FakeGraph.kt`.

## 4. Test matrix (hazards; the brief fixes names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the words per state (AC 1–4) | `OverwriteSubjectsTest` · `namesTheBoundAsset` (P70-1 with the name; a labelled row → identifier `"<8> · v1 · <placement>"`, an unlabelled or blank-labelled row → `"<8> · v1"`), `keepsASpareTagUnassigned` (P70-2; the identifier carries the placement when set), `keepsALostAndARetiredTagRevoked` (P70-3a / P70-3b for rows that still target an existing asset; no asset name in the line), `saysAnUnknownV1IsNotInTheRecords` (P70-4, identifier without placement), `namesAPreSplitLinkTag` (P70-5), `isHonestWhenTheLookupFailed` (`null` → P70-6), `keepsTheThreeNonV1Sentences` (the exact three lines, `identifier == null`) | swap the Revoked and Unbound branches; drop the placement; drop the trailing full stop |
| the identifier (AC 6) | `OverwriteSubjectsTest` · `theIdentifierIsTheShortIdAndOnlyForV1`: eight characters + " · v1" for every v1 case, null otherwise; the full uuid appears in no `line` and no `identifier` | use the full id; add it to the line |
| read-only resolution (C1, C6) | `ResolveTagTest` · `peekClassifiesLikeRun` (bound; unbound; LOST and RETIRED rows that still target an existing asset; pre-split link; unknown; newer; foreign — compared by outcome kind plus asset, `peek` called before `run` in each fixture), `peekRecordsNothing` (rows seeded with `lastScannedAt = null`; every `rows` entry equal after `peek`; `uow.reads == 1 && uow.commits == 0`), `runStillRecordsTheScan` (existing behaviour: `lastScannedAt` becomes the clock's value, one commit) | make `peek` upsert; run `peek` in `uow.write`; make `run` stop recording |
| the controller asks and names (AC 1–5, 7) | `TagWriteControllerTest` · `aTagBoundToAnAssetIsNamedInTheQuestion` (an asset and a bound row through `graph.tags`; a tap on a tag holding that id → `Confirm.subject.line == P70-1(name)`; `identifier == identityLine(key)`), `aPlacementRidesOnTheIdentifierLine` (`"<8> · v1 · <placement>"`), `anUnknownV1TagIsSaidToBeNotInTheRecords` (P70-4), `aLostTagIsSaidToBeLost` (P70-3a), `aNewerTagKeepsTheNewerAppLine` (`NewerVersion` → the unchanged line, `identifier == null`, 0 writes), `aFailedLookupStillAsksAndKeepItWritesNothing` (a `ResolveTag` over a `TagRepository` delegate whose `findByPayload` throws: `Confirm` with P70-6; `keepIt()` → 0 writes, rows unchanged), `aFailedLookupStillAsksAndOverwriteWritesOnTheRetap` (fresh tap → `confirmOverwrite()` → retap → written, as the existing happy path proves), `aCancelledLookupAsksNothingAndReleasesTheTap` (`findByPayload` throws `CancellationException`: state not `Confirm`, 0 writes, the next tap handled — `inspectCount == 2`), `answeringWhileTheLookupRunsIsInert` (a delegate whose `findByPayload` awaits a `CompletableDeferred`; `keepIt()` and `confirmOverwrite()` before completion are no-ops; complete it → `Confirm` shown, `keepIt()` works, 0 writes), `aHangingLookupIsBoundedToTheHonestLine` (never complete; `advanceTimeBy(LOOKUP_BOUND + 1)` → `Confirm` with P70-6), `askingWritesNothingToTheStore` (rows seeded with `lastScannedAt = null`; byte-identical after the question); the existing cases re-anchored, `foreignContentAsksFirstAndKeepItWritesNothing` and `confirmedOverwriteIsHonouredOnTheNextTapWithoutAskingAgain` otherwise unchanged | set `awaitingAnswer` before the lookup (the inert case goes red: `keepIt()` releases `busy` and the sheet sticks); swallow `CancellationException` (the cancelled case publishes P70-6); drop the bound (the hanging case never shows a sheet); write in `peek` |
| the lines agree with the inspect vocabulary | `TagWriteControllerTest` · `theOverwriteWordsMatchTheScanWords`: `OverwriteSubjects.PRE_SPLIT_LINK == PRE_SPLIT_LINK_SENTENCE`; `OverwriteSubjects.NOT_IN_RECORDS` equals the inspect sheet's literal (itself pinned by `NfcIdentityDeviceProofTest`); `of(...).identifier == identityLine(key)` for an unlabelled row | drift one literal |
| the sheet (AC 1, 6) | `OverwriteSheetSubjectTest` · `theSheetShowsTheLineAndTheQuietIdentifier`: `OverwriteSheet(OverwriteSubject(P70-1("Pump 3"), "a0c19962 · v1 · Pump house"), "Pump 3", …)` → both texts displayed, the explanation paragraph and both buttons displayed; `aSubjectWithoutAnIdentifierShowsOnlyTheLine` (the foreign line; `onAllNodesWithText(" · v1", substring = true)` count 0) | drop the mono line; draw the identifier when null |
| the consent invariant (regression) | `WriteTagScreenConsentWordingTest` · both cases unchanged; the fixture becomes `WriteState.Confirm(OverwriteSubject(P70-4, "a0c19962 · v1"))` and the sheet takes `subject = state.subject` | — |
| the scan path (regression) | `InspectNamesABoundTagTest` unchanged and green (a real scan still names and records) | — |

## 5. Strings — for ratification (the sheet's warning line, one per state; the quiet line beneath)

`<asset>` and `<placement>` are substituted; `<n>` / `<detail>` are the existing ones.

| id | state | text | note |
|---|---|---|---|
| P70-1 | bound to an Asset | `This tag currently identifies <asset>.` | the issue's shape without the parenthetical (R70-3) |
| P70-2 | known, unbound (spare; or a target that no longer exists) | `This tag is in this phone's records but is not assigned to anything yet.` | mirrors the ratified "not assigned to anything yet" |
| P70-3a | known, status LOST | `This tag was marked lost and taken out of service.` | mirrors "Tag marked lost" / "taken out of service" |
| P70-3b | known, status RETIRED | `This tag was retired and taken out of service.` | mirrors "Tag retired" |
| P70-4 | valid v1, not in this phone's records | `This ServiceTag tag is not in this phone's records.` | **reused verbatim** from the inspect sheet; nothing new |
| P70-5 | known, pre-split link tombstone | `This tag points at a note link from before the product split. ServiceTag no longer opens links; NoteTag does.` | **reused verbatim** (`PRE_SPLIT_LINK_SENTENCE`); nothing new |
| P70-6 | lookup failed or timed out (never a cancellation, which shows no sheet) | `This is a ServiceTag tag, but its record could not be checked just now.` | honest; never claims "not in the records" (R70-5) |
| P70-7 | the quiet line under P70-1…6 | `<first 8 of the id> · v1`, and for a known row with a placement `<first 8 of the id> · v1 · <placement>` | **reused shape** (`identityLine`, G1 §3 correction a; the Written state's `· locked` precedent) — no new sentence, one new suffix form |
| — | newer app | `The tag already holds a ServiceTag tag written by a newer app (format <n>).` | unchanged words |
| — | foreign NDEF | `The tag already holds foreign NDEF content (<detail>).` | unchanged words |
| — | unreadable | `The tag already holds unreadable NDEF content (<detail>).` | unchanged words |
| — | eyebrow, explanation, buttons, the NfcSheet line | `OVERWRITE THIS TAG?` · `Replacing it will make the tag identify <target>. The old content is lost. After you confirm, hold the same tag to the phone again to write.` · `Overwrite` · `Keep it` · `Answer here, then hold the same tag to the phone again.` | unchanged |

New sentences to ratify: **P70-1, P70-2, P70-3a, P70-3b, P70-6** (five) and the **P70-7 placement
suffix**. Retired: `The tag already holds a different ServiceTag tag (<uuid>).`

Alternatives the owner may prefer for the placement (R70-3): (i) inside the sentence as the issue
wrote it, `This tag currently identifies <asset> (<placement>).` — ambiguous when an asset's own name
ends in a parenthetical; (ii) `This tag currently identifies <asset> (tag placement: <placement>).`;
(iii) the inspect sheet's captioned "Tag placement" block under the surface (two more lines).

A case worth knowing: writing Asset X over a tag already bound to X reads "This tag currently
identifies X." above "Replacing it will make the tag identify X." — honest and useful (the old tag
row is not the one being written; the tag gets a new identity).

## 6. Rulings (controller, 2026-09-26; the owner reads these before dispatch and may override any)

- **R70-1, the pre-write read records no scan.** `peek()` writes nothing (`uow.read`, no upsert).
  Today's write flow records nothing either; the inspect flow's `run()` keeps recording.
- **R70-2, resolve first, then record and show the question in one breath.** The lookup runs in the
  `Confirm` branch after the decision `d` is final and before `awaitingAnswer` is set, so
  `awaitingAnswer = existing` and the state publish stay adjacent with no suspension point between
  them — the public `keepIt()` / `confirmOverwrite()` can never act on a question that is not yet on
  screen, and a cancelled lookup leaves no stale consent state. Rev 1 had the order reversed; the
  review showed the window. Rejected: resolving in the view model or the screen (a second place that
  knows about tag identity); publishing the sheet first and re-wording it later (a flash of the wrong
  words).
- **R70-3, the placement rides on the quiet line, not in the sentence.** `"<8> · v1 · <placement>"` is
  the Written state's own shape (`· locked`), keeps every sentence a single template, avoids the
  "X (Y) (Z)" ambiguity of a parenthetical, and shows the placement for every known row (bound, spare,
  revoked — a revoked tag's placement is still where it was stuck). The sheet gains at most one short
  line. The alternatives are in §5. **RATIFIED 2026-09-26: the quiet line** — cleaner than prose, no
  `Asset (qualifier) (placement)` ambiguity, an existing metadata pattern rather than a new block.
- **R70-4, one answer per case.** `sentence()` stops wording `OTHER_TAG_SAME_PRODUCT`; `OverwriteSubjects.of`
  is the sheet's only entry for the v1 case. The two `OverwriteReasonsTest` cases that pinned the uuid
  sentence are re-anchored.
- **R70-5, the failure words.** P70-6 is distinct from P70-4: when the lookup failed, "not in this
  phone's records" would tell the owner a tag bound to an asset is unknown, which invites an overwrite.
  **RATIFIED 2026-09-26: P70-6 distinct** — a failed lookup means unknown right now, not definitively absent.
- **R70-6, a bounded lookup.** Two seconds, `withTimeoutOrNull` (a `withTimeout` would raise a
  cancellation and kill the tap); past the bound the sheet shows P70-6. A Room read never takes that
  long; the bound exists so that "changes only the words" is true even if it did.
- **R70-7, proof surface.** The state logic and the words are proven on the JVM (core and the
  Room-backed controller test with virtual time); the emulator proves only the sheet's drawing and
  the consent regression. No real phone; the owner sees it on the phones with the next release.

## 7. What this plan does not do

No change to `OverwritePolicy`, to what proceeds without asking (an empty tag, a retry of the same
identity), to the consent flow, to the inspect sheet or its words or constants (moving
`PRE_SPLIT_LINK_SENTENCE` into core is a later tidy; the equality is pinned by test), to
`ProvisionTag`, to what happens to the old row after an overwrite (out of scope, pre-existing), to
the API or MCP; no version bump (#70 rides with the next feature-bearing release); no new screen.

## 8. The brief (one implementer)

**Read first:** §1–§7 above; issue #70's body (the safety invariant and AC 1–7); D12 §11's overwrite
sketch; G1 §1.4 row "Overwrite confirmation"; the KDoc of `TagWriteController` (invariant 10, the
consent contract, the `busy` ownership) and of `WriteTagScreenConsentWordingTest`.
**Lane:** alone, branched from master after this plan's ratified commit. **Blocked on:** the owner's
ratification of §5 and the R70-3 / R70-5 answers.

### Files

§3 verbatim.

### Interfaces

```kotlin
// core/usecase/ResolveTag.kt
suspend fun run(payload: TagPayload): Resolution        // unchanged: records the scan on a hit
suspend fun peek(payload: TagPayload): Resolution       // new: same classification, uow.read, writes nothing
private suspend fun classify(row: TagBinding): Resolution   // shared by both

// core/usecase/OverwriteSubjects.kt (new)
data class OverwriteSubject(val line: String, val identifier: String?)
object OverwriteSubjects {
    fun of(c: OverwriteDecision.Confirm, resolution: Resolution?): OverwriteSubject
    fun identifies(assetName: String): String              // P70-1
    const val NOT_ASSIGNED, MARKED_LOST, RETIRED, NOT_IN_RECORDS, PRE_SPLIT_LINK, COULD_NOT_CHECK   // P70-2, 3a, 3b, 4, 5, 6
}

// core/nfc/OverwriteReasons.kt
fun sentence(c: OverwriteDecision.Confirm): String   // the three non-v1 sentences; OTHER_TAG_SAME_PRODUCT → error(...)

// app/ui/scan/TagWriteController.kt
class TagWriteController(provisionTag, appScope, io, codec, target, label, scope, resolveTag: ResolveTag, ioDispatcher = Dispatchers.IO)
constructor(graph: AppGraph, io, target, label, scope)   // passes graph.resolveTag
sealed interface WriteState { data class Confirm(val subject: OverwriteSubject) : WriteState /* … */ }
private val LOOKUP_BOUND = 2.seconds   // R70-6

// app/ui/scan/WriteTagScreen.kt
internal fun OverwriteSheet(subject: OverwriteSubject, target: String, onOverwrite: () -> Unit, onKeepIt: () -> Unit)
```

### Contracts

C1–C7 of §2 verbatim. The P70 strings of §5 verbatim, as ratified. Each fixed literal on one line.

### Test matrix

§4 verbatim; each case's RED mutation shown and quoted in the report. Tests hard-code the expected
sentences (never call `of()` to build the oracle).

### Gate

- `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero
  failures, zero skips; the report quotes counts before and after.
- Connected, on `emulator-5554` only, one class per invocation:
  `com.loosecannon.servicetag.ui.scan.OverwriteSheetSubjectTest`, then
  `com.loosecannon.servicetag.ui.scan.WriteTagScreenConsentWordingTest`, then
  `com.loosecannon.servicetag.ui.scan.InspectNamesABoundTagTest`.
- Anchored `git grep -nF` over `core/src/main` and `app/src/main`, one fixed fragment per string, each
  expected exactly once and in `OverwriteSubjects.kt`: `"This tag currently identifies "`,
  `"This tag is in this phone's records but is not assigned to anything yet."`,
  `"This tag was marked lost and taken out of service."`, `"This tag was retired and taken out of
  service."`, `"This ServiceTag tag is not in this phone's records."` (→ 2: `OverwriteSubjects.kt` and
  `TagResultSheet.kt`), `"This is a ServiceTag tag, but its record could not be checked just now."`.
  `git grep -nE '"a different ServiceTag tag \('` → 0; `git grep -nE 'copy\(lastScannedAt = '` in
  `ResolveTag.kt` → 1; `git grep -nE 'resolveTag\.run\('` in `TagWriteController.kt` → 0;
  `git grep -nE '"The tag already holds \$'` in `WriteTagScreen.kt` → 0.
- `git diff <base> --stat -- libs tools docs/api app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/di app/src/test/kotlin/com/loosecannon/servicetag/testing/FakeGraph.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ProvisionTag.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/BindTag.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/scan/TagResultSheet.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/scan/ScanViewModels.kt` → empty.
- `grep -rnE '(^|[^.[:alnum:]_])assert\(' app/src/androidTest` → no output. Hygiene; gitlink `7e0377a`;
  `git status` clean.

### Strings

§5 verbatim. Nothing else. A state that seems to need words is NEEDS_CONTEXT.

### Must NOT

Change `OverwritePolicy`, `OverwriteReasons.decide`, the consent fields, `confirmOverwrite()`,
`keepIt()`, `write()`, or the retap contract; call `resolveTag.run()` from the write flow; put any
suspension point between `awaitingAnswer = existing` and the state publish; use `runCatching` or
`withTimeout` around the lookup; let a lookup failure, bound or cancellation skip, auto-answer or
shortcut the sheet when a sheet is due; write, upsert or touch `lastScannedAt` in `peek()`; place
`OverwriteSubject` or its words in `core/nfc`; show the full uuid; add a captioned placement block;
change the inspect sheet or its constants; invent a sentence; drive the UI outside the Compose test
APIs; use any device but `emulator-5554`.

### Review focus

The lookup before `awaitingAnswer` and the two adjacent lines after it; `try`/`catch` rethrowing
`CancellationException`, `withTimeoutOrNull` with the bound; `peek()` sharing `classify(row)` with
`run()` and writing nothing under `uow.read`; the wording in `core/usecase` (no `core/nfc` →
`core/usecase` import); the ratified lines verbatim and one-per-state; the identifier eight characters
+ " · v1" (+ " · <placement>" only for a known labelled row) and equal to `identityLine` when
unlabelled; no asset name in any non-bound line; the three non-v1 lines byte-identical to today's
words; the sheet unchanged except the line and the quiet mono line; every existing controller and
consent test still green; the inert, cancelled and hanging cases genuinely red under their mutations.

### Size

Medium: two core files modified and one created, two app files, five test files touched, two test
classes new.
