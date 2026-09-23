# D12 — Visual design: Apollo Service Binder

Status: **approved visual-design direction**, recorded 2026-09-14 as part of the design authority.
Supplied by the product owner; preserved substantially as written (formatting normalised,
cross-references added). It does not reopen any architectural decision in D3/D4/D5 and it does
not change the approved implementation sequence in D7.

## Scope rule (binding)

- **Phase 0 stays behaviour-preserving.** No Compose colours, typography, shapes, components,
  mockups, or screen redesigns are implemented in Phase 0.
- **Phase 1C** implements the foundational Compose design system: Material 3 light/dark colour
  schemes (§3, §4), the `NoteNfcSemanticColors` layer (§5, §15), typography (§6), shapes (§7),
  the app shell/navigation surfaces, and the visual treatment needed for the initial
  asset/link/NFC screens (§8 identity plate skeleton, §11 NFC states).
- **Phase 2** implements/refines the Asset Identity Plate, the Service Ledger, Instrument
  Measurement presentation, event-entry forms, and the hot-tub/power-equipment screens (§8, §9).
- **Phase 3** implements/refines the dashboard attention hierarchy, due/due-soon/overdue/inactive
  states, local-reminder surfaces, and Reminder Health (§10, §5).
- Later phases reuse the same semantic system; no feature lane invents its own colour conventions.
- **Visual-design gate before Phase 1C (G1, D8 §2):** review 3–4 representative screens — asset
  detail, dashboard, structured water-test entry, NFC scan/write — to confirm the written system
  works as a real Android interface and that the Apollo influence is restrained. Those mockups are
  not produced in Phase 0 unless separately requested. **G1 was run on 2026-09-14: PASS WITH
  CHANGES** — corrections a–j from `g1/01-g1-visual-gate-report.md` are applied in this document.
- Naming: `Apollo Service Binder` is the internal design-system/theme name. It is not assumed to
  be a public subtitle or brand.

## Signature devices (must survive every later refinement)

1. **Asset Identity Plate**
2. **Service Ledger**
3. **Instrument Measurement**

## Non-negotiables carried into acceptance criteria

- Operational state is always communicated through **position + wording + icon + colour**;
  red-vs-green distinction is never required (§5, §14).
- Theme policy (§13): `Apollo Service Binder` is the default palette; Material You / dynamic
  colour is an optional appearance setting; dynamic colour never replaces the operational
  semantic colours for due/overdue, measurement low/high, reminder failure, sync failure, and
  destructive actions.
- Restraint: NASA technical publication + equipment data plate + professional field instrument —
  **not** spacecraft cockpit or retro-futuristic fan theme. No NASA logos, worm typography,
  spacecraft imagery, fake instrumentation, CRT effects, warning-stripe decoration, or
  control-panel skeuomorphism.

Cross-references: D3 §4 (Compose decision, the UI toolkit this system is built on), D3 §6
(data-driven profiles the measurement-entry screen renders), D4 §6 (`measurement_definition`
ranges that drive LOW / IN RANGE / HIGH), D5 §1 (status vocabulary OK / DUE_SOON / DUE / OVERDUE /
INACTIVE_SEASON / PAUSED / NO_DATA that §5 and §10 render), D3 §7.3 (reminder-health findings
that §5 "Scheduler failure" / "Sync problem" render), D6 §4–§5 (legacy-tag resolutions that §11
"Legacy tag" renders), D7 Phases 1C/2/3.

---

# noteNFC — Apollo Service Binder

## Design thesis

**Apollo Service Binder** treats noteNFC as the digital service record physically attached to a
machine.

The reference is not spacecraft cockpits or retro-futurism. It is the quieter visual system around
serious technical work: Apollo-era procedure books, NASA technical publications, aerospace
equipment labels, inspection records, test instrumentation, maintenance binders, serial-number
plates, and field-service documentation. NASA's 1976 graphics system is particularly relevant
because it was built around consistent typography, publication grids, technical information and
repeatable communication rather than decoration.

The resulting application should feel like something that belongs beside a generator, UPS, HVAC
unit or water system: warm aircraft-gray/ivory surfaces; deep navy labels; restrained instrument
blue; occasional service amber; extremely clear numbers; compact metadata; disciplined grids;
modestly squared components.

The historical reference should remain almost subliminal. There should be **no NASA logos, worm
typography, spacecraft imagery, warning stripes, fake switches, CRT effects or control-panel
skeuomorphism**. We borrow the logic of technical documentation rather than NASA's actual brand
identity. NASA itself treats its identifiers and logotypes as controlled brand assets, another
good reason to keep this inspiration abstract.

The defining visual metaphor is:

> **Every asset has an identity plate. Every intervention becomes a service record. Every
> measurement looks like an instrument reading.**

---

## 1. Core visual language

Apollo Service Binder is built around four ideas.

**Identity plates.** The top portion of every asset screen should feel analogous to a
manufacturer's equipment plate: asset name, manufacturer/model, serial number, location and NFC
identity are tightly organized into one recognizable block.

**Service records.** Maintenance history is not a pile of cards. It should resemble an ordered
technical record: date, event, reading, material/part used, notes and attachments aligned
consistently.

**Instrument readings.** Measurements should receive more visual authority than ordinary body
text. Large numerals, aligned units, clear reference ranges and compact status annotations make
pH, voltage, hours, runtime and percentages immediately readable.

**Procedure hierarchy.** Labels, secondary metadata and operational instructions follow a strict
hierarchy. The design should look carefully engineered even when screens contain a great deal of
information.

Material 3 is a strong foundation because Compose exposes color, typography and shapes
semantically through `MaterialTheme`; the app can therefore keep the standard component behavior
while imposing this much more particular visual system.

---

## 2. Foundational palette

The palette intentionally separates **brand color** from **maintenance-state color**.

Instrument blue should not mean "healthy." Amber should not simply mean "selected." Brick red
should remain relatively rare.

| Family           | Token         |       HEX | Use                              |
| ---------------- | ------------- | --------: | -------------------------------- |
| Mission Navy     | Navy 900      | `#102F4A` | Dark text on blue containers     |
|                  | Navy 700      | `#1F4E78` | Main light-theme primary         |
|                  | Navy 600      | `#2F648E` | Interactive emphasis             |
|                  | Navy 200      | `#A9CBE5` | Dark-theme primary               |
|                  | Navy 100      | `#D8E7F2` | Light primary container          |
| Instrument Steel | Steel 900     | `#25313A` | Dark secondary foreground        |
|                  | Steel 700     | `#52606A` | Secondary                        |
|                  | Steel 300     | `#B9C6CE` | Dark secondary                   |
|                  | Steel 100     | `#DDE3E7` | Secondary containers             |
| Signal Blue      | Signal 900    | `#173A4A` | Dark text                        |
|                  | Signal 700    | `#336B87` | Tertiary / NFC / instrumentation |
|                  | Signal 300    | `#A4C6D5` | Dark tertiary                    |
|                  | Signal 100    | `#D7EAF1` | Tertiary container               |
| Service Amber    | Amber 900     | `#3A2500` | Deep foreground                  |
|                  | Amber 700     | `#8A5A12` | Due-soon family                  |
|                  | Amber 600     | `#9B5C0B` | Warning foreground               |
|                  | Amber 300     | `#E4B45F` | Dark warning                     |
|                  | Amber 100     | `#F6DEB1` | Warning container                |
| Service Brick    | Brick 900     | `#601410` | Error foreground                 |
|                  | Brick 700     | `#8C2E2A` | Overdue/error                    |
|                  | Brick 600     | `#A43D36` | Destructive action               |
|                  | Brick 300     | `#F2B8B5` | Dark error                       |
|                  | Brick 100     | `#F8DAD6` | Error container                  |
| Binder Neutral   | Carbon        | `#10171D` | Dark background                  |
|                  | Ink           | `#1B1F22` | Primary light text               |
|                  | Graphite      | `#444B50` | Secondary text                   |
|                  | Outline       | `#737B80` | Light outlines                   |
|                  | Rule          | `#C1C7CA` | Quiet dividers                   |
|                  | Aircraft Gray | `#DFE0DB` | Raised neutral surface           |
|                  | Binder Gray   | `#ECEBE5` | Surface container                |
|                  | Warm Ivory    | `#F7F5EF` | Main light background            |
|                  | Paper White   | `#FCFAF5` | Light surface                    |

This deliberately avoids pure white as the dominant application canvas and pure black as the dark
canvas.

---

## 3. Material 3 light theme

The light theme should feel like a clean technical binder under workshop lighting rather than a
white SaaS webpage.

| Material role             |       HEX | Purpose                                                |
| ------------------------- | --------: | ------------------------------------------------------ |
| `primary`                 | `#1F4E78` | Primary actions, active navigation, important controls |
| `onPrimary`               | `#FFFFFF` | Content over primary                                   |
| `primaryContainer`        | `#D8E7F2` | Selected/important informational containers            |
| `onPrimaryContainer`      | `#102F4A` | Content over primary container                         |
| `secondary`               | `#52606A` | Secondary controls and quiet emphasis                  |
| `onSecondary`             | `#FFFFFF` | Content over secondary                                 |
| `secondaryContainer`      | `#DDE3E7` | Neutral technical containers                           |
| `onSecondaryContainer`    | `#25313A` | Content over secondary container                       |
| `tertiary`                | `#336B87` | NFC, instrumentation and complementary emphasis        |
| `onTertiary`              | `#FFFFFF` | Content over tertiary                                  |
| `tertiaryContainer`       | `#D7EAF1` | NFC/instrument informational surfaces                  |
| `onTertiaryContainer`     | `#173A4A` | Content over tertiary container                        |
| `error`                   | `#A43D36` | Actual error/destructive conditions                    |
| `onError`                 | `#FFFFFF` | Error foreground                                       |
| `errorContainer`          | `#F8DAD6` | Persistent error/overdue container                     |
| `onErrorContainer`        | `#3B0807` | Error-container text                                   |
| `background`              | `#F7F5EF` | Overall canvas                                         |
| `onBackground`            | `#1B1F22` | Primary text                                           |
| `surface`                 | `#FCFAF5` | Sheets, dialogs, important reading surfaces            |
| `onSurface`               | `#1B1F22` | Main surface text                                      |
| `surfaceVariant`          | `#E2E5E6` | Legacy/compatible variant role                         |
| `onSurfaceVariant`        | `#444B50` | Metadata and secondary text                            |
| `surfaceDim`              | `#D7D8D3` | Dimmed structural surface                              |
| `surfaceBright`           | `#FFFFFF` | Highest light surface                                  |
| `surfaceContainerLowest`  | `#FFFFFF` | Rare highest-level card                                |
| `surfaceContainerLow`     | `#F4F2EC` | Subtle grouping                                        |
| `surfaceContainer`        | `#ECEBE5` | Default structured panels                              |
| `surfaceContainerHigh`    | `#E6E5DF` | Raised information group                               |
| `surfaceContainerHighest` | `#DFE0DB` | Highest neutral elevation                              |
| `outline`                 | `#737B80` | Functional borders                                     |
| `outlineVariant`          | `#C1C7CA` | Dividers and quiet rules                               |
| `inverseSurface`          | `#2E3438` | Snackbars/inverse surfaces                             |
| `inverseOnSurface`        | `#F4F2EC` | Inverse content                                        |
| `inversePrimary`          | `#A9CBE5` | Inverse primary                                        |
| `surfaceTint`             | `#1F4E78` | M3 surface tint where required                         |
| `scrim`                   | `#000000` | Modal scrim only                                       |

The principal foreground/background combinations here comfortably exceed the sensible 4.5:1
normal-text target. Android's own app-quality guidance uses 4.5:1 for smaller text and 3:1 for
large text/graphics, consistent with WCAG guidance.

---

## 4. Material 3 dark theme

Dark Apollo Service Binder should resemble a **well-lit instrument panel**, not a black OLED
videogame HUD.

The blue-gray surface ladder is intentionally visible so hierarchy survives without excessive
borders.

| Material role             |       HEX | Purpose                          |
| ------------------------- | --------: | -------------------------------- |
| `primary`                 | `#A9CBE5` | Primary actions and active state |
| `onPrimary`               | `#0A3452` | Primary-button content           |
| `primaryContainer`        | `#214E70` | Strong selected surface          |
| `onPrimaryContainer`      | `#D7E9F6` | Container content                |
| `secondary`               | `#B9C6CE` | Secondary emphasis               |
| `onSecondary`             | `#243139` | Secondary foreground             |
| `secondaryContainer`      | `#394750` | Technical neutral container      |
| `onSecondaryContainer`    | `#DDE6EB` | Container text                   |
| `tertiary`                | `#A4C6D5` | NFC/instrument accent            |
| `onTertiary`              | `#173A4A` | Accent content                   |
| `tertiaryContainer`       | `#2A5267` | NFC/instrument containers        |
| `onTertiaryContainer`     | `#D7EAF1` | Container content                |
| `error`                   | `#F2B8B5` | Error/destructive foreground     |
| `onError`                 | `#601410` | Content over error               |
| `errorContainer`          | `#8C2E2A` | Critical persistent condition    |
| `onErrorContainer`        | `#FFDAD7` | Error-container content          |
| `background`              | `#10171D` | Main dark canvas                 |
| `onBackground`            | `#E5E7E8` | Primary text                     |
| `surface`                 | `#151D23` | Standard surface                 |
| `onSurface`               | `#E5E7E8` | Surface text                     |
| `surfaceVariant`          | `#3D474E` | Variant surface                  |
| `onSurfaceVariant`        | `#B9C3C9` | Metadata (G1 correction h: must recede from `onSurface`; ≈9.5:1 on `surface`) |
| `surfaceDim`              | `#10171D` | Lowest surface                   |
| `surfaceBright`           | `#353F46` | Highest highlighted surface      |
| `surfaceContainerLowest`  | `#0C1217` | Recessed dark surface            |
| `surfaceContainerLow`     | `#151D23` | Low surface                      |
| `surfaceContainer`        | `#1A2229` | Default panels                   |
| `surfaceContainerHigh`    | `#202A32` | Raised grouping                  |
| `surfaceContainerHighest` | `#27323A` | Prominent neutral surface        |
| `outline`                 | `#8D979E` | Functional border                |
| `outlineVariant`          | `#3F4A51` | Quiet rules                      |
| `inverseSurface`          | `#E5E7E8` | Inverse surface                  |
| `inverseOnSurface`        | `#2C3134` | Inverse content                  |
| `inversePrimary`          | `#1F4E78` | Inverse accent                   |
| `surfaceTint`             | `#A9CBE5` | Surface tint                     |
| `scrim`                   | `#000000` | Modal scrim                      |

Dark mode should actually be preferable in a dark equipment room or beside a hot tub at night:
low overall luminance, little large-area white, but text and important controls remain high
contrast.

---

## 5. Application semantic system

These should be **separate semantic tokens**, not improvised calls to `Color.Green`,
`Color.Red`, etc.

Every state has four channels:

**position + wording + icon + color.**

Color is reinforcement.

| State                | Light foreground / container | Dark foreground / container | Icon + explicit label                     | Structural cue                        |
| -------------------- | ---------------------------- | --------------------------- | ----------------------------------------- | ------------------------------------- |
| Maintenance OK       | `#245B78` / `#DCEBF3`        | `#91BED6` / `#17384B`       | `check_circle` — **OK**                   | Normal weight, quiet container        |
| Due soon             | `#7A4B0A` / `#F6E5C3`        | `#E4B45F` / `#4A320D`       | `schedule` — **DUE SOON**                 | Clock + remaining time                |
| Due                  | `#8C4700` / `#F3CA9D`        | `#F0A15D` / `#573015`       | `event` — **DUE**                         | Strong label and medium-weight border |
| Overdue              | `#8C2E2A` / `#F8DAD6`        | `#F2B8B5` / `#4E1C1A`       | `warning` — **OVERDUE**                   | Strong left rule + overdue duration   |
| Season inactive      | `#586269` / `#E6E8E8`        | `#B1B8BC` / `#2A3136`       | `calendar_month` — **OUT OF SEASON**      | De-emphasized placement               |
| Paused               | `#5B4D6F` / `#E8E3EF`        | `#C4B4D3` / `#342C3B`       | `pause_circle` — **PAUSED**               | Pause glyph + reason                  |
| Measurement low      | `#4F5F9A` / `#E2E5F6`        | `#B5C1F0` / `#2A3152`       | `arrow_downward` — **LOW**                | Down arrow and range                  |
| Measurement in range | `#245B78` / `#DCEBF3`        | `#91BED6` / `#17384B`       | `check` — **IN RANGE**                    | Check + reference range               |
| Measurement high     | `#8C4700` / `#F7D3AD`        | `#F0A15D` / `#573015`       | `arrow_upward` — **HIGH**                 | Up arrow and range                    |
| Measurement no target| `#444B50` / `#E6E5DF`        | `#D9E0E4` / `#202A32`       | (no glyph) — **NO TARGET SET**            | Neutral; invites configuring a range   |
| Reminder healthy     | Same as OK                   | Same as OK                  | `notifications_active` — **ACTIVE**       | Normal treatment                      |
| Scheduler failure    | Error family                 | Error family                | `notifications_off` — **REMINDER FAILED** | Explicit failure text                 |
| Sync problem         | `#684682` / `#E9DFF2`        | `#CFB3E5` / `#3B2C46`       | `cloud_off` — **SYNC ISSUE**              | Provider name + retry state           |
| Destructive action   | `#A43D36` / `#F8DAD6`        | `#F2B8B5` / `#4E1C1A`       | `delete_forever` etc.                     | Confirmation required                 |

The semantic foreground/container combinations above were selected with roughly **4.9:1–8.2:1**
contrast, depending on state and theme.

Phase 1C closure correction: the light **Due** container was `#F3C89A` in the approved D12, which
measures 4.49:1 against `#8C4700` — a hair under the 4.5:1 target the table claims. Task 1 nudged
it 4% toward WarmIvory to `#F3CA9D` (4.56:1) and the table above now carries the shipped value;
`ContrastTest` in `:app` asserts it. No other value in this table changed.

Most importantly:

`LOW` ≠ blue because blue inherently means low.
`HIGH` ≠ orange because orange inherently means high.

They are distinguishable because they literally contain **↓ LOW** and **↑ HIGH**.

That also complies with the broader accessibility principle that color should not be the sole
visual carrier of information.

Mapping to the domain vocabulary (D5 §1): OK → `OK`; Due soon → `DUE_SOON`; Due → `DUE`;
Overdue → `OVERDUE`; Season inactive → `INACTIVE_SEASON`; Paused → `PAUSED`; `NO_DATA` renders
with the Season-inactive treatment plus the wording **NO BASELINE**. Measurement low / in range /
high derive from `measurement_definition.range_low/range_high` (D4 §6); a definition whose
range is null renders **NO TARGET SET** (G1 correction f — the owner logs readings such as TDS
without a target). Scheduler failure and
Sync problem render the reminder-health findings of D3 §7.3.

---

## 6. Typography

### Primary typeface

Use **Roboto/system sans** for the vast majority of noteNFC.

Do not turn the theme into a retro typography exercise.

The Apollo character should come from:

* hierarchy
* spacing
* condensed information
* uppercase micro-labels
* numeric alignment
* ledger-like composition

rather than an imitation aerospace font.

### Technical typeface

Use **Roboto Mono**, bundled with the application if exact consistency is important, or
`FontFamily.Monospace` initially.

Monospace is appropriate for:

* `XYZ12345`
* `OR2200LCDRT2U`
* NFC tag IDs
* MAC-like identifiers
* voltages
* meter readings
* runtime values where columns align

It should not be used for paragraphs, button labels or entire screens.

### Type hierarchy

| Content             | Treatment                                     |
| ------------------- | --------------------------------------------- |
| Asset name          | 24sp, Medium/SemiBold, normal sans            |
| Manufacturer/model  | 16–18sp, Medium                               |
| Section title       | 12–13sp, SemiBold, uppercase, ~0.6sp tracking |
| Metadata label      | 11–12sp, Medium, uppercase or title case      |
| Metadata value      | 14–15sp, Regular                              |
| Serial/model/NFC ID | 13–14sp monospace                             |
| Measurement (entry) | 22sp, Medium, monospace tabular numerals, inside the outlined value field |
| Measurement (hero)  | 28–32sp, Medium, monospace tabular numerals — one featured reading per asset screen only |
| Unit                | 13–14sp sans, medium                          |
| Event title         | 15–16sp Medium                                |
| Event timestamp     | 12sp, `onSurfaceVariant`                      |
| Status badge        | 11–12sp Bold/SemiBold                         |
| Body                | 15–16sp Regular, generous line height         |

One useful Apollo-like device is the **small technical label above a larger value**:

```text
LAST RUNTIME
42 min

BATTERY INSTALLED
10 JUN 2024
```

That gives the interface character without requiring decorative graphics.

---

## 7. Shape and component language

Material 3's shape system is customizable precisely so products do not all need to use the same
degree of roundedness.

For noteNFC:

```text
extraSmall   2dp
small        4dp
medium       8dp
large       12dp
extraLarge  16dp
```

### Cards

Cards should be relatively rare.

Prefer:

```text
section heading
────────────────────────
data row
data row
data row
────────────────────────
```

over:

```text
rounded card
rounded card
rounded card
rounded card
```

Use cards when a block has independent meaning: upcoming maintenance, an asset identity block, a
document preview, or an actionable warning.

### Borders

A **1dp outline** is more characteristic of Apollo Service Binder than elevation.

Equipment-information panels can use `outlineVariant`.

Important service panels can use `outline`.

### Elevation

Very restrained.

Use elevation for:

* dialogs
* bottom sheets
* floating menus
* FAB
* transient overlays

Do not elevate every section.

### Chips

Use chips for filters and compact categorical states.

Avoid giant rounded status pills.

A status badge should preferably be a mildly rounded rectangle, approximately 4–6dp radius.

### Buttons

Primary buttons: filled Mission Navy.

Secondary: outlined.

Low-priority utility actions: text or tonal.

Override Material's highly pill-shaped defaults with the theme's smaller corner treatment.

### FAB

Use a FAB only for the genuinely dominant creation/logging action.

Examples:

* Log maintenance
* Add measurement

Do not have several competing FABs.

G1 ruling (correction c): **no FAB on the asset detail screen** — its 2×2 action grid already
leads with "Log maintenance" — and none on the dashboard, where Scan is a navigation
destination. At most one FAB exists app-wide, on the History/Ledger screen.

### Text fields

6–8dp corners.

Outlined fields are appropriate for:

* serials
* readings
* part numbers
* notes

Measurement entry can use a stronger custom layout described below.

---

## 8. Asset detail screen

### CyberPower Rack UPS

The screen should begin with a quiet app bar:

```text
‹ ASSETS                         ⋮
```

Below it sits the defining **Asset Identity Plate**.

```text
CYBERPOWER                           UPS icon

CyberPower OR2200LCDRT2U
Server Rack

SERIAL
XYZ12345

NFC TAG
41c11b73 · v1
```

Surface: `surfaceContainerLow`.

Border: `outlineVariant`.

Asset model: 20–22sp medium.

Serial and NFC ID: monospace.

No decorative image is required unless the user has actually attached a useful equipment photo.

Immediately below:

```text
⚠ OVERDUE

LOAD TEST
12 days overdue
Originally due 2 Sep 2026
```

This uses the overdue semantic container and brick accent, but the words **OVERDUE** and the
warning glyph do most of the work.

Then a horizontal or 2×2 utility-action region:

```text
[ wrench  Log maintenance ]
[ gauge   Run test        ]

[ history History         ]
[ file    Documents       ]
```

These should be outlined/tonal controls, not four colorful tiles.

### Current information

Use a technical information grid:

```text
BATTERY INSTALLED        LAST RUNTIME
10 JUN 2024              42 min

LOAD                     NEXT REPLACEMENT
38 %                     Jun 2028
```

The headings are tiny technical labels; the values are larger.

### Recent service record

Use a chronological ledger:

```text
10 JUN 2024
Battery replaced
CyberPower RB1290X2
Receipt · Photo

03 SEP 2025
Load test
Runtime 46 min · Pass

14 MAR 2026
Inspection
No visible swelling
```

Dates anchor the left edge or top of each row.

A subtle rule separates entries.

This should look much more like a **maintenance record** than an activity feed.

Note on the NFC line of the plate: the value shown is the first 8 characters of the tag's
`nfc_tag.id` plus the format ("41c11b73 · v1") or the 8-char legacy key ("63b37acf · legacy").
The hardware UID (`physical_uid`) is informational only and appears solely in the tag detail
sheet, labelled "Chip UID" — never on the plate (G1 correction a; see D4 §3).

---

## 9. Hot-tub water-test screen

Do not make this look like a health-monitoring application.

It should look like entering values into a field test sheet.

```text
WATER TEST
14 SEP 2026 · 14:42

                    VALUE       RANGE

pH                   7.8        7.2–7.6
                     ↑ HIGH

Free chlorine        0.8 ppm    2.0–4.0
                     ↓ LOW

Alkalinity           110 ppm    80–120
                     ✓ IN RANGE

Calcium              200 ppm    150–250
                     ✓ IN RANGE
```

The ranges above are illustrative; actual state should always derive from the asset's configured
thresholds.

Each measurement row has:

1. descriptive name
2. large aligned numeric value
3. unit
4. reference interval
5. explicit `LOW`, `HIGH` or `IN RANGE`

Do not fill the entire pH row orange.

Instead, use a small status marker or narrow 3–4dp left rail plus status text.

Then place treatment separately:

```text
TREATMENT ADDED

Chlorine                  1.0 oz
pH reducer                0.5 oz

[ + Add treatment ]
```

This separation is important conceptually:

**measurement ≠ intervention.**

The resulting screen feels like a water-testing worksheet rather than a medical alarm display.

---

## 10. Dashboard hierarchy

Order primarily by required attention, not asset category.

Example:

```text
ATTENTION
─────────────────────────────────────

⚠ OVERDUE
Generator annual service
12 days overdue                         ›

■ DUE
Hot tub water test
Due today                               ›

UPCOMING
─────────────────────────────────────

◷ DUE SOON
UPS load test
5 days                                  ›

CURRENT
─────────────────────────────────────

✓ OK
RO filters
Next replacement in 74 days             ›

OUT OF SEASON
─────────────────────────────────────

▦ OUT OF SEASON
Snowblower pre-season check
Resumes 1 Nov                           ›
```

Section order is fixed: ATTENTION · UPCOMING · CURRENT · OUT OF SEASON; empty sections are
omitted. The duration lives inside the state label ("OVERDUE · 12 DAYS", "DUE SOON · 5 DAYS").
Season is shown only when *inactive*; an in-season schedule flows into the normal sections and
there is no IN SEASON badge (G1 correction g).

The hierarchy is carried by several simultaneous dimensions.

| Priority  | Position | Container                | Icon             | Typography |
| --------- | -------- | ------------------------ | ---------------- | ---------- |
| Overdue   | Top      | Strongest tinted surface | Warning triangle | Bold       |
| Due       | Top      | Warm container           | Calendar/event   | Bold       |
| Due soon  | Upcoming | Lighter warm treatment   | Clock            | Medium     |
| Out of season | Bottom | Recessed neutral, lighter text | Calendar    | Regular    |
| OK        | Current  | Quiet blue/neutral       | Check            | Normal     |

Even if every color were converted to grayscale, the hierarchy should still be obvious.

That is the acceptance test.

---

## 11. NFC interaction

NFC should be one of the few places where noteNFC feels slightly animated.

Not futuristic—**immediate**.

### Ready to scan

Large NFC icon.

`tertiaryContainer`.

```text
READY TO SCAN

Hold the top of your phone
near the equipment tag.
```

A very subtle slow breathing scale or halo is acceptable.

No radar animation.

### Tag detected

Immediate haptic tick.

```text
✓ TAG DETECTED

CyberPower Rack UPS
OR2200LCDRT2U
```

Use the cool/instrument semantic family.

Transition directly into the asset if identification is certain.

### Unknown tag

This is not an error.

```text
? UNREGISTERED TAG

This NFC tag is not assigned
to a noteNFC asset.

[ Bind to asset ]
[ Cancel ]
```

Use neutral/amber informational treatment rather than error red.

### Write tag

Use a deliberate confirmation sheet:

```text
WRITE NFC TAG

Asset
Generator

Tag will identify this asset
when scanned.

[ Write tag ]
```

### Successful write

Haptic confirmation plus check.

```text
✓ TAG WRITTEN

Generator
Tag verified successfully.
```

### Overwrite confirmation (tag already holds something)

A bound tag that is *scanned* simply opens its target (a link tag launches with no sheet at
all, R-7). This sheet appears only inside the **write** flow, when the tag being written already
holds content — another noteNFC tag, a legacy tag, or foreign NDEF data:

```text
OVERWRITE THIS TAG?

ⓘ The tag already holds: Honda EU7000is

Replacing it will make the tag identify
Hot tub. The old content is lost.

[ Overwrite ]
[ Keep it ]
```

One informational line in the due-soon family names what is on the tag. Brick is not used:
nothing here is an error.

### Legacy tag detected

```text
LEGACY TAG

This tag uses an earlier
noteNFC identifier.

Its asset record was found.

[ Rewrite in format v1 ]
[ Bind as-is ]
```

This should look like a migration opportunity, not damaged data.

(These states map onto the `Resolution` outcomes of D3 §9 as implemented in Phase 1B. Re-link
was dropped by D13; an unknown legacy tag offers "Rewrite in format v1" (primary) and "Bind
as-is" (secondary), per D13 §3.)

---

## 12. Iconography

Use Material Symbols/Icons as the baseline.

Android supports Material icon resources directly; the extended icon library is useful during
development, although Google notes its larger dependency/build impact, so frequently used icons
can later be brought in as fixed vector resources.

Use four icon classes.

### Asset/category icons

Broad categories only:

```text
vehicle
HVAC
water
electrical
computer/network
tool
outdoor equipment
battery/power
other
```

Do not create icons for:

```text
Toro mower
Honda generator
CyberPower UPS
Pentair pump
DeWalt drill
```

The asset name supplies that specificity.

### Action icons

Literal verbs:

```text
build / wrench
add
edit
history
camera
description
calculate
notifications
schedule
delete
```

### Status icons

Reserve a tiny vocabulary:

```text
check_circle       OK
schedule           due soon
event              due
warning            overdue
pause_circle       paused
arrow_downward     low
arrow_upward       high
cloud_off          sync problem
notifications_off  reminder failure
```

These must remain semantically stable.

### Integration/provider icons

Provider marks such as Todoist should be visually separated from noteNFC status icons.

An integration logo means **provider identity**, never application status.

---

## 13. Dynamic color decision

### Recommendation: C

**noteNFC palette is the default; dynamic color is available as a setting.**

Material 3 supports wallpaper-derived dynamic light/dark `ColorScheme`s on Android 12+, with a
custom fallback scheme below that.

For most consumer applications, allowing that personalization is attractive.

For noteNFC, however, visual semantics have operational meaning.

If someone's wallpaper turns:

* primary purple
* tertiary pink
* containers green
* accents orange

the carefully constructed relationship between equipment information, NFC interaction,
maintenance urgency and application identity becomes weaker.

Therefore:

```text
Appearance

Theme
○ Apollo Service Binder
○ Dynamic Material You

Mode
○ System
○ Light
○ Dark
```

When Dynamic Material You is selected:

**Allow dynamic color to replace:**

* primary
* secondary
* tertiary
* ordinary containers
* selected navigation

**Do not dynamically recolor:**

* overdue
* due
* low/high measurement
* reminder failure
* sync failure
* destructive actions

The app-specific semantic layer remains controlled.

This gives personalization without sacrificing operational semantics.

---

## 14. Accessibility audit

### Red-green color blindness

The design does not require discrimination between red and green.

In fact, the normal/healthy state is deliberately **cool blue**, not green.

Critical distinctions are:

```text
✓ IN RANGE
↓ LOW
↑ HIGH

◷ DUE SOON
■ DUE
⚠ OVERDUE
```

The symbol and text carry the meaning.

### Contrast

The principal Material foreground/background combinations were chosen above the normal-text
4.5:1 target.

Examples:

```text
Light primary / onPrimary        ~8.7:1
Light background / onBackground ~15.2:1
Light tertiary / onTertiary      ~5.8:1

Dark primary pairing             ~7.6:1
Dark background pairing         ~14.6:1
Dark tertiary pairing            ~6.7:1
```

Android's core-quality guidance specifies at least 4.5:1 for smaller text and 3:1 for large text
and graphics.

### Warning versus error

Amber = maintenance attention.

Brick = overdue, actual error or destructive operation.

But even this distinction is never trusted by itself.

### Disabled controls

Disabled controls may be visually subdued, but **information must not be hidden merely because
its action is disabled**.

Example:

Bad:

```text
[ Run test ]  ← gray and unexplained
```

Better:

```text
Run test
Unavailable while maintenance session is open.
```

### Dark mode

Avoid extremely thin gray typography.

Use `onSurfaceVariant` only for truly secondary information.

Serial numbers and units should remain sufficiently bright.

### Measurement tables

Never put status only in a tiny colored dot.

Each measurement carries:

```text
7.8
↑ HIGH
Target 7.2–7.6
```

### Touch targets

Interactive controls should retain Android's expected minimum touch sizing; Android's app-quality
guidance calls for at least 48dp touch targets.

### TalkBack semantics

Status components should expose meaningful combined descriptions such as:

```text
"Free chlorine, 0.8 parts per million, low,
target range 2 to 4."
```

rather than exposing individual decorative icons.

Modern Android/Compose accessibility APIs also support semantic descriptions of UI state and
important changes rather than relying on disruptive generic announcements.

---

## 15. Compose architecture

The key architectural decision is to maintain **two layers**.

Material answers:

> How should generic UI components look?

noteNFC semantics answer:

> What does this operational state mean?

Conceptually:

```kotlin
@Composable
fun NoteNfcTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val materialColors =
        resolveNoteNfcColorScheme(
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
        )

    val semanticColors =
        if (darkTheme) {
            NoteNfcDarkSemanticColors
        } else {
            NoteNfcLightSemanticColors
        }

    CompositionLocalProvider(
        LocalNoteNfcSemanticColors provides semanticColors,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = NoteNfcTypography,
            shapes = NoteNfcShapes,
            content = content,
        )
    }
}
```

The semantic holder can remain small:

```kotlin
@Immutable
data class NoteNfcSemanticColors(
    val maintenanceOkay: StatusColor,
    val dueSoon: StatusColor,
    val due: StatusColor,
    val overdue: StatusColor,

    val seasonInactive: StatusColor,
    val paused: StatusColor,

    val measurementLow: StatusColor,
    val measurementInRange: StatusColor,
    val measurementHigh: StatusColor,

    val reminderHealthy: StatusColor,
    val reminderAttention: StatusColor,
    val reminderFailure: StatusColor,

    val syncProblem: StatusColor,
    val destructiveAction: StatusColor,
)

@Immutable
data class StatusColor(
    val foreground: Color,
    val container: Color,
)
```

Then the application should never contain code like:

```kotlin
if (overdue) Color.Red
```

Instead:

```kotlin
val colors = NoteNfcTheme.semanticColors

StatusBadge(
    label = "OVERDUE",
    icon = Icons.Outlined.Warning,
    colors = colors.overdue,
)
```

Material 3 explicitly encourages using semantic theme roles instead of naming application colors
by raw hex values.

Placement in the module layout (D3 §3): `app/src/main/kotlin/com/loosecannon/notenfc/ui/theme/`
— `Color.kt` (palette + both schemes), `SemanticColors.kt` (`NoteNfcSemanticColors`,
`StatusColor`, `LocalNoteNfcSemanticColors`), `Type.kt`, `Shape.kt`, `Theme.kt`
(`NoteNfcTheme`, `resolveNoteNfcColorScheme`). Created in Phase 1C; consumed, never redefined,
by every later feature lane.

---

## 16. Navigation and window structure (added at G1)

- **Bottom navigation** (`NavigationBar`, indicator pill squared to 6dp, `secondaryContainer`).
  1C shipped Dashboard · Assets · Scan. **Correction (2026-09-15): the Scan tab is not part of
  the long-term navigation.** Normal tag reading is ambient — tap the tag anywhere, Android's
  NDEF dispatch opens noteNFC, the resolver routes to the asset, link or contextual offer — so
  the user never opens the app to "scan". The tab existed because 1B/1C needed a place to
  exercise the resolver. Remove it at the first phase that touches the bottom bar (2B, or Phase
  3 when a maintenance destination may take its place); until then it is a utility, not the
  primary path. **Done in 2B-2**: `TopLevelRoutes` is now Dashboard · Assets, and the scan
  screen — unchanged — is reached as a pushed destination from Settings' "Read / inspect tag"
  row or the dashboard's empty-state "Scan a tag" action. Dedicated foreground NFC screens
  remain only for intentional tag operations —
  Write tag, Replace/rebind, Rewrite legacy, optional Inspect — because those may modify tag
  contents and the screen establishes intent; an ambient read never enters write mode. A manual
  "identify a tag" utility may live under tag tools or Settings. Asset detail, ledger and sheets
  are pushed on top; `notenfc://` deep links land inside this structure (D3 §13).

  > **Amended at implementation (2026-09-22, ServiceTag 1.2 / B13).** Primary navigation is now
  > **Dashboard · Assets · Maintenance** — three destinations, not two (`TopLevelRoutes` in
  > `app/.../ui/nav/Route.kt`). The 1.2 navigation ruling gives the Maintenance destination the
  > place this section reserved for it when it removed the Scan tab ("Phase 3 when a maintenance
  > destination may take its place"), so the removal above and this addition are the same
  > decision arriving in two parts. Maintenance hosts due work, schedules, maintenance groups and
  > reminder health. **The Scan tab does not come back**: tag reading stays ambient, and the scan
  > screen is still only a pushed destination. The scan *completion sheet* 1.2 adds is a sheet
  > over the ambient read, not a destination.
- **Edge-to-edge** (target 36+): the canvas colour runs under the status and navigation bars in
  both themes; app bars and bottom sheets pad by the system insets; no separate status-bar colour.
- **No FAB** on dashboard or asset detail (§7).

## The recognizable noteNFC signature

If only three things survive from this specification, make them these.

### 1. Asset Identity Plate

Every scanned object starts with the same disciplined identity structure:

```text
ASSET TYPE

Manufacturer Model
Friendly asset name

SERIAL        NFC ID
XYZ12345      41c11b73 · v1
```

That should become visually synonymous with noteNFC.

### 2. Service Ledger

History is a durable record, not an activity feed.

Dates, measurements, parts, notes and documents line up with intentional structure.

### 3. Instrument Measurement

Important physical values look unmistakably like readings:

```text
12.6
V

✓ IN RANGE
11.8–13.0 V
```

Large value. Small unit. Explicit status. Reference interval.

Together, those three devices say:

**physical object + instrumentation + durable record**

without a single fake dial, gear, rivet or spacecraft graphic.

---

## Final design recommendation

The production version of **Apollo Service Binder** should be approximately:

```text
50% modern Material 3
25% aerospace technical documentation
15% professional field instrumentation
10% physical service-binder / equipment-label character
```

That ratio matters.

Increase the NASA component much beyond that and noteNFC becomes themed software.

Reduce it too far and it becomes another competent Material application.

The target is a UI where someone might not immediately say, "This is NASA-inspired," but would
immediately think:

> **This looks like serious software for taking care of real equipment.**

The next useful step is the G1 visual-design gate (3–4 representative Android screen mockups —
asset detail, dashboard, water test, NFC scan) immediately before the Phase 1C Compose theme is
written, so that a too-weak or too-theatrical Apollo influence is caught while changes are cheap.

## Sources cited by the specification

- NASA Graphics Standards Manual (1976) — nasa.gov/image-article/nasa-graphics-standards-manual
- NASA Brand Guidelines — nasa.gov/nasa-brand-center/brand-guidelines
- Material Design 3 in Compose — developer.android.com/develop/ui/compose/designsystems/material3
- W3C WAI Easy Checks; WCAG 2.0 guidelines
- Compose `Shapes` reference — developer.android.com/reference/kotlin/androidx/compose/material3/Shapes
- Resources in Compose — developer.android.com/develop/ui/compose/resources
- Core app quality guidelines — developer.android.com/docs/quality-guidelines/core-app-quality
- Android 16 features and APIs — developer.android.com/about/versions/16/features
- Migrate XML themes to Material 3 in Compose — developer.android.com/develop/ui/compose/designsystems/migrate-xml-theme-to-compose
