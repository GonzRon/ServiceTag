# D14 — The asset model: Assets, Components and supply items (2026-10-01)

Status: **the current architecture**, recorded for #15 (owner ruling R15-14, 2026-10-01) from the owner's
binding clarification on issue #15 (2026-09-29), read beside #47. It describes what is built and what each
later issue owns; it is not a plan. D4 §9's older supply design is superseded where its note says so.

## 1. The chain

> **Asset → nested Component → optional SupplyItem identity → maintenance usage/history**

- **Asset** — an independently meaningful piece of equipment: its own NFC tags, journal, documents,
  schedules, condition, custody and retirement. An Asset may sit under a parent Asset; the phone's
  **Components** section on asset detail, and the Assets list's Components control, show those child
  Assets today. A child Asset is an Asset in every respect.
- **Component** (#47, not built) — physical, serviceable structure fitted inside an Asset or inside another
  Component. It does not have to be an Asset, needs no NFC tag, and may name a SupplyItem. It is what holds
  fitted state: what is in a position now, since when, and what was there before. How the phone distinguishes a #47
  Component from today's child-Asset "Components" is #47's to decide.
- **SupplyItem** (#15, Room schema 18) — the one canonical product identity: name, category, manufacturer,
  model, Part number, preferred unit, notes, and an ordered list of generic specifications (label, value,
  unit). It is archived, never deleted. **Applicability** says which SupplyItems an Asset takes, child Assets
  included, and in what role ("Oil filter", "Sanitizer", "Replacement battery"). Applicability is not fitted
  state: "this UPS takes battery model X" is #15's; "position 3 holds battery X, fitted on date Y" is #47's.
- **Maintenance usage/history** — the ordinary maintenance path, unchanged by #15: a schedule, a quick
  action whose material lines may name a SupplyItem, and events whose material lines may name one. A line's
  own name and unit stay its readable snapshot; the link adds the identity. Nothing links a line because its
  name matches a SupplyItem: a link exists only because a person chose it, or because a row carrying it was
  copied verbatim (a backup, a merge, a Transfer Pack, a Replace's cloned quick actions).

Every step after the Asset is optional. A SupplyItem needs no Component, and a Component needs no
SupplyItem: some fitted structure has nothing to buy, and most supplies are never tracked as fitted
instances.

**The distinction that decides it:** a SupplyItem needs a #47 Component record **only when fitted-instance
or current-position history actually matters**. A routine filter, cartridge, membrane, lamp, chemical or oil
needs none when the owner only wants schedule, event and usage history; it uses

> SupplyItem + applicability + an ordinary maintenance schedule + the replacement or usage event.

## 2. Four concepts, not mutually exclusive

| concept | answers | where it lives | state |
|---|---|---|---|
| **Asset** | what is this equipment? | `asset`, its tags, journal, documents and schedules | shipped |
| **Component** | what is fitted here now, in which position, since when, and what was fitted before? | #47 | not built |
| **SupplyItem** | what product is this, what are its specifications, which Assets take it, in what role? | `supply_item`, `supply_specification`, `asset_supply` | #15, schema 18 |
| **Consumable/replacement lifecycle** | how is it normally used up or replaced? | no table of its own: the schedule, the quick action and its material lines, the events (`profile_consumable`, `consumable_usage`, each with a nullable `supply_id`) | shipped path; #15 adds the link |

One thing can be several of these at once. A UPS battery pack is fitted structure **and** a SupplyItem
replaced on a cadence; a sanitizer is a SupplyItem with a consumption lifecycle and no fitted structure at
all. A SupplyItem is not "consumable only": it is the identity for chemicals, fluids, filters, batteries,
belts, blades, lamps, complete modules and packs, and durable service items alike.

## 3. The owner's examples

| example | Asset | Component (#47) | SupplyItem (#15) | lifecycle |
|---|---|---|---|---|
| hot-tub chemical (a sanitizer) | the hot tub | no | yes, applicable to the hot tub in a role | consumed; dosed on a schedule, the amount on the event's material line |
| engine oil | the engine | no | yes | consumed and changed on a cadence |
| oil filter | the engine | usually no: no fitted-instance history needed | yes | routine replacement on a schedule |
| RO membrane / UV lamp | the treatment system | optional, only when its fitted-instance history matters | yes | routine replacement on a schedule |
| UPS battery pack | the UPS | yes: the pack or tray is fitted structure | yes, when it is bought as a complete pack | replaced as a whole |
| an individual battery in that pack | the UPS | yes: a position inside the pack, naming the battery's SupplyItem | yes, a SupplyItem of its own | replaced one position at a time |
| generator alternator | the generator | yes, naming its SupplyItem | yes | not consumed in the ordinary sense; inspected on a schedule, replaced when needed |

The pack and the battery inside it are **two unrelated SupplyItems**: #15 has no SupplyItem-to-SupplyItem
relation, and containment belongs to #47's Components. #47's own example of the shape:

```text
UPS (Asset)
└─ Battery tray (Component)
   ├─ Position 1 (Component) → SupplyItem "battery model X"
   ├─ Position 2 (Component) → SupplyItem "battery model X"
   ├─ Position 3 (Component) → SupplyItem "battery model X"
   └─ Position 4 (Component) → SupplyItem "battery model X"
```

Several Components may name the same SupplyItem, and the SupplyItem keeps the manufacturer, model, Part
number and specifications once; a Component never copies them.

## 4. What #15 built, and what it did not

**Built (Room schema 18, backup format 18):** the SupplyItem with its specifications; applicability rows,
unique per `(asset, supply item, role)` and removable (configuration, not history); the nullable link on a
quick action's material line and on an event's material line. They travel through backup, replace restore,
merge and Transfer Packs, and are reachable over `/v1` and the MCP. On the phone: **Maintenance › Supplies**
(the list, a detail with Specifications and "Used by", the editor); a **Supplies** section on asset detail,
before Components; "Link supply" on the quick-action editor's Materials rows; the event form shows and
removes a link but never gives one. Event detail and the journal row draw a linked line like any other.

**What usage linkage means, exactly.** The SupplyItem identity is kept on the quick action's line and on any
event line that carries it: an event entered on the form from the quick action, or a `/v1` or MCP completion
that sends lines. A schedule completed through "When was this done?" writes no material line, so it records
no SupplyItem usage. The scheduler, the completion flow and the templates are unchanged.

**Not built, by the owner's scope** ("#15 MVP = canonical SupplyItem identity + generic specs + Asset
applicability + canonical maintenance usage linkage. Nothing more."):

- fitted state of any kind — positions, fitted dates, fitted serials, containment — is #47's, which will name
  the same SupplyItem id rather than keep a second catalog;
- photos, manuals, files and vendor links on a SupplyItem are #69's, which will treat the SupplyItem as their
  owner;
- quantities kept and purchasing are #95's;
- a schedule that names a SupplyItem is #96's; ranged or conditional guidance in a specification is #88's.

## 5. Choosing the shape for a new thing

1. Is it equipment with its own identity, journal, documents or movement? It is an **Asset** (a child Asset
   when it sits inside another).
2. Is it something you buy or fit by product identity? It is a **SupplyItem**; say which Assets take it, in
   what role, and link the quick action's material line to it.
3. Do you need to know which unit is fitted where, since when, and what it replaced? Only then add a #47
   **Component** for it, naming the SupplyItem.
4. Is it used up or replaced on a cadence? That is an ordinary **maintenance schedule** with a quick action,
   whatever the answers above were.
