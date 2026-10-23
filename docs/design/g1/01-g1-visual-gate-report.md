# G1 — Apollo Service Binder visual-design gate

Date 2026-09-14. Authority: D12 (approved). Inputs: D12 §2–§11, the source-data inventory in
`00-source-data-inventory.md`, D4 §3/§6, D5 §1, D13. Output: four screen specifications, a
rendered mockup page, a cross-screen assessment, D12 corrections, and a verdict.

**Mockups (light + dark, with grayscale and red–green-deficiency toggles):**
https://claude.ai/artifact/97wtYcYQYVSEUkXBM6g3Hu — private artifact; the toggles are the
accessibility test, not a description of it. Nothing in it is production code.

Scope discipline: no Compose was written; no theme was redesigned; every hex value is D12's.

---

## 1. Screen specifications

Common to all four: `background` canvas, Roboto for text, Roboto Mono (tabular) for identifiers
and readings, D12 shape scale (2/4/8/12/16dp), 16dp side gutter, 8dp vertical rhythm, section
titles 12sp SemiBold uppercase 0.06em over a 1dp `outlineVariant` rule, metadata labels 11sp
Medium uppercase over 14–15sp values, badges 11sp Bold uppercase in a 4dp-radius rectangle with
a 16dp glyph. Touch targets ≥ 44dp (48dp in production). TalkBack: every badge exposes
"label, value, state, target" as one description.

### 1.1 Asset detail (rack UPS)

| Aspect | Specification |
|---|---|
| Hierarchy | App bar (‹ ASSETS, overflow) → **Identity Plate** → status block (only if a schedule is DUE/OVERDUE) → 2×2 quick actions → CURRENT grid → SERVICE RECORD ledger (3 most recent + "All N") |
| Plate | `surfaceContainerLow` fill, 1dp `outlineVariant`, 8dp radius, 14dp padding. Row 1: category eyebrow (11sp SemiBold uppercase, "BATTERY / POWER · SERVER RACK") left, category icon right. Model 21sp Medium; friendly name 15sp `onSurfaceVariant`. A 1dp rule, then a 2×2 label/value grid: SERIAL (mono), NFC TAG (mono: first 8 chars of `nfc_tag.id` + " · v1"), INSTALLED, DOCUMENTS (count). No photo unless the user attached one. |
| Status block | Overdue semantic container + foreground, 4dp left rule, `warning` glyph, "OVERDUE" 12sp Bold, schedule name 15sp Medium `onSurface`, "12 days overdue · originally due 2 Sep 2026" 13sp `onSurfaceVariant`. DUE uses the due family and a full 1dp border; DUE SOON is not shown on the asset screen (it appears in CURRENT as a value). |
| Quick actions | Outlined (`outline`, `primary` text) for the two logging verbs, tonal (`secondaryContainer`) for navigation verbs: Log maintenance · Record reading / History · Documents. 44dp, 6dp radius, icon + label. **No FAB on this screen** (see §3 correction c). |
| CURRENT | 2×2 label-over-value grid; values 17sp Medium; readings in mono with a 12sp unit. Contents come from the asset profile (Phase 2); 1C shows only what 1A/1B know (installed date, tag, documents count). |
| Ledger | Each entry: 64dp date column (day+month 12sp SemiBold, year 12sp Regular below), title 15sp Medium with an optional result badge (Pass = in-range family), detail 13sp `onSurfaceVariant` (readings in mono), attachments as plain words. 1dp `outlineVariant` between entries, none around the block. |
| Light/dark | Plate: `#F4F2EC` on `#F7F5EF` / `#151D23` on `#10171D`; the plate reads as a plate in both because of the outline, not the fill. |
| Empty asset | Plate with "—" values; status block replaced by one quiet line "No schedule yet"; ledger "No entries yet · Log maintenance to start". No red, no illustration. |

### 1.2 Dashboard

| Aspect | Specification |
|---|---|
| Hierarchy | App bar ("noteNFC", search, overflow) → sections in fixed order **ATTENTION · UPCOMING · CURRENT · OUT OF SEASON** → bottom navigation (Dashboard · Assets · Scan). Sections with no rows are omitted. |
| Row anatomy | 28dp glyph · text block · chevron; 11dp vertical padding; 6dp radius; state label 11sp Bold uppercase **with the duration in it** ("OVERDUE · 12 DAYS", "DUE SOON · 5 DAYS"); schedule name 15sp; asset + date 13sp `onSurfaceVariant`. |
| State carriers | OVERDUE: overdue container, 4dp left rule + 1dp border, bold name. DUE: due container, 1dp border, bold name. DUE SOON: due-soon container, no border, medium name. OK: no fill, hairline `outlineVariant` border, regular name. OUT OF SEASON: `surfaceContainerLow`, name in `onSurfaceVariant`, section at the bottom. NO BASELINE: season-inactive family + a sentence saying what to enter. |
| Navigation | Material 3 `NavigationBar`, three destinations, indicator pill squared to 6dp (`secondaryContainer`). Scan is a destination, so the dashboard has no FAB. |
| Grayscale test | Fill luminance order OVERDUE < DUE < DUE SOON < OK(none); plus rule/border/weight differences. Passes with the toggle on. |
| Red–green | OK is Signal blue, never green; OVERDUE is brick. Under deuteranopia simulation the two stay distinct and the words remain. |

### 1.3 Water test — structured measurement entry

| Aspect | Specification |
|---|---|
| Hierarchy | App bar (✕, "WATER TEST · HOT TUB") → TESTED label + timestamp (editable) → column header READING / VALUE / TARGET → one **measurement row per definition** in the profile → TREATMENT ADDED section → notes field → filled "Record entry" ("Save entry" when editing), the form's one commit control (#118: the app bar has no Save). |
| Measurement row | 3-column grid (1fr / 92dp / 72dp); name 15sp Medium; state badge under the name; value is an **outlined field** (`outline`, 6dp, `surface` fill) holding a 22sp mono number with a 12sp unit inside the field; target column shows a 10sp "TARGET" eyebrow over the range. A 3dp left rail in the state colour spans the row. Nothing else in the row is tinted. |
| States | `↑ HIGH`, `↓ LOW`, `✓ IN RANGE` from D12 §5, plus **`NO TARGET SET`** (neutral, `surfaceContainerHigh` / `onSurfaceVariant`) for a definition without a configured range — required by the owner's data (TDS logged without a range) and missing from D12 (§3 correction f). |
| Treatment | Separate section; rows "product · quantity (mono) unit (small) · remove"; "+ Add treatment" outlined full-width; quantities keep the unit the user chose (oz, ppm, g). Measurements and treatments save as one event with one timestamp (D4 §7), matching how the owner's log interleaves them. |
| Keyboard | Numeric keypad for value fields; focus moves down the column; the one commit button ends the scrolling column, so it sits at the foot of the form and is reachable once the keyboard closes; #121 (1.8.2) makes it reachable while typing. |
| Light/dark | Value fields are the brightest surfaces on the sheet in both themes (`surface`), so the numbers read first. |

### 1.4 NFC interaction

Six states, all rendered as bottom sheets (`surface`, 14dp top radius, 16dp padding, elevation
used here because sheets are transient): eyebrow (12sp Bold uppercase with glyph) → one
sentence 18sp Medium → identifier line in 12sp mono → actions stacked, filled first.

| State | Surface / colour | Copy and actions |
|---|---|---|
| Ready to scan | `tertiaryContainer`; `contactless` glyph 64dp inside a 96dp halo at 18% tertiary; halo breathes 3.2 s, disabled under reduced motion; no radar | "READY TO SCAN — Hold the top of your phone near the equipment tag." Cancel (text). |
| Tag detected (asset) | `surface`; eyebrow in OK family | Model, name, mono id, "Opening asset…". Haptic tick. Transitions to the asset without a tap. A **link** tag shows no sheet at all: it launches the note (R-7). |
| Unregistered tag | `surface` with 1dp due-soon-family border; eyebrow in due-soon foreground; `help` glyph | "This tag is not assigned to anything yet." Bind to asset or note (filled) · Cancel. Not an error. |
| Overwrite confirmation | `surface`; one warning line in due-soon container naming what is on the tag (foreign / legacy / another noteNFC tag) | "Replacing it will make the tag identify Hot tub. The old content is lost. Hold the tag to the phone while confirming." Overwrite (filled) · Keep it (outlined). Brick is not used: nothing is an error. |
| Tag written | `surface`; eyebrow OK family; verification as its own OK-container line | "Read back byte-identical", mono id with lock state. Done. Haptic confirmation. |
| Legacy tag | `surface`; eyebrow in `tertiary` (instrument blue); `history` glyph | "This tag uses the 2024 noteNFC identifier." Rewrite in format v1 (filled) · Bind as-is (outlined). Per D13 §3; no re-link. |

---

## 2. Cross-screen assessment

**Does it look like serious equipment-maintenance software rather than a NASA novelty?** Yes.
Nothing on the four screens references NASA; there is no logo, no display typeface, no
imagery, no stripes, no dial. The character comes from four devices used consistently: uppercase
eyebrow labels over larger values, mono identifiers and readings, rules instead of cards, and
duration-bearing state labels. A viewer would call it "technical" before "themed".

**Are the three signature devices coherent?** Yes, because they are the same device at three
scales. The Identity Plate is a label-over-value grid inside one outlined block; the Service
Ledger is date-over-year at the left of label-over-detail rows; the Instrument Measurement is a
value-over-state cell next to a label-over-range cell. One vocabulary, one badge, one mono face.

**Is density right for real records?** The asset screen holds 14 data points above the fold on a
360dp width without a card stack; the dashboard holds 8 schedules with their durations; the
water test holds 5 readings + 2 treatments + notes on one sheet. This matches the owner's notes,
which are tables and lists, not feeds. The risk is in the other direction: 1C must resist
adding whitespace "for calm". D12's spacing is the calm.

**Both themes?** Dark keeps the surface ladder visible (`#10171D` → `#151D23` → `#1A2229`), the
overdue container is the deepest brick and still reads as a fill, and mono identifiers stay
bright. One correction (h below) about dark secondary text.

**Grayscale and red–green?** The toggles on the mockup page demonstrate it: hierarchy is carried
by position, fill luminance, rule weight, glyph and wording, and OK was never green. Passes.

**Still native Android?** Top app bars, bottom navigation with an indicator pill, outlined text
fields, filled/outlined/tonal/text buttons, bottom sheets, snackbar roles: all stock Material 3
with the smaller shape scale. Nothing requires a custom component to feel right; 1C can build
this from `androidx.compose.material3` plus a theme.

**Rules that look good in prose but fail on a real screen?** Four, listed in §3 (c, e, g, h).
None invalidates the system; all are refinements.

---

## 3. Recommended D12 corrections

| # | Where | Finding | Correction |
|---|---|---|---|
| a | §8 plate example | Shows a hardware-UID style value ("04:A7:91:2C:…") for NFC TAG; D4 §3 and the note under §8 say the plate shows the tag id | Plate shows `nfc_tag.id` prefix + format ("41c11b73 · v1"); hardware UID only in the tag detail sheet, labelled "Chip UID (informational)". |
| b | §5 light DUE vs DUE SOON | Containers `#F7D3AD` and `#F6E5C3` sit ~6% apart in luminance; on the dashboard they separate only by border and weight | Acceptable as designed (border + weight are mandated), but deepen light DUE to `#F3C89A` so the fill alone also orders them. Dark values are fine. |
| c | §7 FAB vs §8 action grid | §7 names "Log maintenance" as a FAB example; §8 puts it in the 2×2 action grid on the same screen | Rule: no FAB on asset detail (the grid is the primary action); one FAB app-wide at most, on the History/Ledger screen ("Log maintenance"). Dashboard has none; Scan is a navigation destination. |
| d | §11 "Existing tag already bound" and the D6 §5 parenthetical | After 1B, a bound tag simply opens its target; the "LINKED TAG — Open asset" sheet only makes sense inside the *write* flow, and "re-link by sharing the note" was dropped by D13 | Rename the state "Overwrite confirmation (tag already bound)"; delete the D6 §5 reference; Unknown legacy tag offers rewrite / bind-as-is (D13 §3). |
| e | §6 "Major measurement 28–32sp" | Five such rows do not fit an entry sheet at 360dp with a field, unit and target column | Two sizes: **entry** 22sp mono in the outlined field; **hero** 28–32sp reserved for a single featured reading on the asset screen (e.g. battery voltage). |
| f | §5 state table | No state for a measurement with no configured range; the owner logs TDS without one | Add **`NO TARGET SET`** — neutral (`surfaceContainerHigh` / `onSurfaceVariant`), no glyph, never amber; the badge invites setting a range. D4 §6 `range_low/range_high` nullable already permits it. |
| g | §10 "SEASONAL / IN SEASON" | "IN SEASON" is not in the §5 state table and the owner's seasonal machines are *out* of season most of the year | Season is only shown when inactive: an OUT OF SEASON section at the bottom, in-season schedules flow into the normal sections. Drop the IN SEASON badge. |
| h | §4 dark `onSurfaceVariant #D9E0E4` | Nearly identical to `onSurface #E5E7E8`, so dark-mode metadata does not recede; the ledger's date column and units lose their hierarchy | Use `#B9C3C9` (≈9.5:1 on `#151D23`, still well above 4.5:1). Keep `onSurface` as is. |
| i | (absent) navigation | D12 has no navigation structure | Lock: bottom navigation with Dashboard · Assets · Scan; asset detail and sheets are pushed on top; deep links land in that structure (D3 §13). |
| j | (absent) edge-to-edge | Android 15+/target 36 draw behind system bars; D12 does not say what the status-bar region looks like | App bar and sheets pad by the insets; the canvas colour runs under the status bar in both themes; no separate status-bar colour. |

None of these changes a palette, a signature device, or the restraint rules.

---

## 4. Verdict

**PASS WITH CHANGES.** The written system works as a real Android interface across the four
hardest archetypes. Apply corrections a–j to D12 (a one-commit edit) before the 1C plan cites it.

---

## 5. Locked for Phase 1C (treat as fixed)

1. Palettes §2, §3, §4 verbatim, with correction h (dark `onSurfaceVariant`) and b (light DUE container).
2. Semantic state table §5 plus `NO TARGET SET`; every state = position + wording + glyph + colour; OK is cool blue; brick only for OVERDUE, errors and destructive actions.
3. Shape scale 2/4/8/12/16dp; badges 4dp; buttons and fields 6dp; plate 8dp; sheets 14dp top.
4. Typography roles §6 with the two measurement sizes (22sp entry, 28–32sp hero); Roboto + Roboto Mono; uppercase eyebrows at 11–12sp with 0.06em tracking.
5. Identity Plate anatomy (eyebrow + icon / model / name / rule / 2×2 mono-label grid) on `surfaceContainerLow` with a 1dp `outlineVariant` border.
6. Service Ledger anatomy (64dp date column, title + optional result badge, detail, attachments as words, hairline rules, no cards).
7. Instrument Measurement anatomy (label + badge / outlined mono value with inline unit / "TARGET" eyebrow + range; 3dp left rail).
8. Dashboard section order ATTENTION · UPCOMING · CURRENT · OUT OF SEASON, duration inside the state label, row carriers per §1.2.
9. NFC sheet anatomy and the six states' copy; a link tag launches with no sheet; "Ready to scan" is the only `tertiaryContainer` surface; the halo is the only animation.
10. Navigation: bottom bar Dashboard · Assets · Scan; no FAB on dashboard or asset detail.
11. Cards are rare; sections are rules; elevation only for sheets, dialogs, menus.

## 6. Deliberately flexible until Phase 2 or 3

- The CURRENT grid's contents and the hero reading (driven by asset profiles, Phase 2).
- Measurement column widths, unit handling for oz/ppm/g, and the treatment product picker (Phase 2 supplies/consumables).
- Dashboard section thresholds ("due soon" horizon), NO BASELINE wording, and how many rows before "See all" (Phase 3 scheduling).
- Empty-state copy for assets with no history (Phase 2 templates may seed a first schedule instead).
- Attachment presentation on the plate and in the ledger (Phase 4); for 1C, a count only.
- Dynamic-colour setting UI (§13; Phase 7).
- Whether the ledger gets a filter chip row (measurements / service / documents) — decide with real data volume in Phase 2.

## 7. What 1C should build from this

The theme (`ui/theme/*` per D12 §15) with the corrected tokens; the three signature composables
(`IdentityPlate`, `LedgerEntry`, `MeasurementRow`) as the only custom components; `StatusBadge`;
the navigation shell; asset detail (1A/1B data only), asset list, dashboard skeleton (no
schedules yet, so CURRENT shows assets with "No schedule yet"), and the six NFC sheets replacing
the interim `ui.interim` screens. The water-test sheet is specified here but built in Phase 2.
