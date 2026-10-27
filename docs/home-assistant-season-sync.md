# Home Assistant: season sync and publishing

ServiceTag works with one Home Assistant in two directions, and keeps them apart:

- **Reading, for the season** (#16). An Asset's operating season can follow one Home Assistant on/off helper. When
  the phone checks, it reads that helper's state from your Home Assistant; if the answer differs from the Asset's
  season today, it records the same Start season or End season you could tap yourself. Schedules, maintenance
  policy, breaks and season history then behave exactly as they do for a season started and ended by hand. Since
  #125, one schedule can instead follow an on/off helper of its own (**A schedule's own season**, below).
- **Writing, only to the helpers you enable** (#113, #114). An Asset can publish two facts to helpers your Home
  Assistant administrator created: its recorded equipment status, as exactly `UP`, `DEGRADED` or `DOWN`, to a
  dropdown, and the date of its Season Start service — or `1970-01-01` whenever it has none — to a date-only helper
  (**Publishing to Home Assistant**, below). Nothing is written until you turn publishing on for that Asset.

ServiceTag reports facts; Home Assistant decides what to do with them. ServiceTag answers "was this serviced since
its season ended?"; whether and when the equipment runs is decided by your Home Assistant automations, never by
ServiceTag. Neither `UP` nor being in season stands in for the service, and the service date stands in for neither.

Every name, address and network on this page is made up: Assets called **Example Heater** and **Example Boiler**,
helpers such as `input_boolean.example_heater_in_season` and `input_datetime.example_heater_service_done`, a Home
Assistant at `http://192.168.0.10:8123` (or `https://ha.example:8123`) and a home Wi-Fi called `ExampleHomeWifi`. Use
your own.

## What the phone does

### Reading the season

- **It reads for the season, and writes only to the helpers you enable.** A season check is one
  `GET /api/states/<entity id>` to the address you entered, carrying your access token; Test connection is one
  `GET /api/`, and the list of helpers to choose from is one `GET /api/states`. With no publishing turned on, those
  reads are all the phone ever sends. A redirect is not followed, and https trusts Android's own certificate
  authorities only.
- **`on` starts the season and `off` ends it** — exactly those two answers. Anything else (`unavailable`,
  `unknown`, an entity Home Assistant does not have, a refused token, no answer at all) changes nothing, and the
  Asset's season card says why.
- **It compares first.** The season changes only when Home Assistant's answer differs from the Asset's season
  today, so reading `on` ten times records one start, and two checks at once cannot record it twice.
- **The date is the day the phone applies it**, never the time Home Assistant says the helper changed. That time is
  shown for information ("Changed in Home Assistant %s").
- **It sees the current state, not history.** If the helper goes off and on again while the phone cannot reach Home
  Assistant, the phone sees only the final state, and applies at most one change for it.

### Writing to the helpers you enable

- **Two service actions, and nothing else.** A publication's only write is
  `POST /api/services/input_select/select_option` with `{"entity_id", "option"}` (equipment status) or
  `POST /api/services/input_datetime/set_datetime` with `{"entity_id", "date"}` (the Season Start date), to the one
  helper you chose for that Asset, at the address you entered. ServiceTag never writes `/api/states`, never calls a
  switch, climate, light, lock, script or any other service, and never creates, renames or re-options a helper.
- **It reads back.** After a write the phone reads the helper (`GET /api/states/<entity id>`) and says "Published"
  only when Home Assistant holds what it sent. A date publication on a season Home Assistant drives also reads the
  season-ended helper you named (**The season-ended helper**, below); that one is read, never written.
- **It never sets a helper it reads**, and never reads a helper it sets: a season link refuses an entity a
  publication on this phone writes, and a publication refuses an entity a season link reads.

## In Home Assistant

**The helper.** In Settings › Devices & services › Helpers, create a **Toggle** named "Example Heater in season".
Home Assistant gives it the entity ID `input_boolean.example_heater_in_season`. In YAML:

```yaml
input_boolean:
  example_heater_in_season:
    name: Example Heater in season
```

**Link the season helper, never the appliance's own switch.** The heater's power switch goes on and off many times
a day; the season helper changes a few times a year. If ServiceTag followed the power switch, every cycle would end
and restart the season, and maintenance that counts from the start of the season would start over each time. The
setup sheet's helper text says the same: "An on/off helper that is on while this asset is in season, for example
input_boolean.example_heater_in_season. Not the appliance's own power switch."

**An automation decides the season.** ServiceTag does not care how the helper is set: a date, the weather, a
dashboard button. A fictional example that starts the season on October 1 and ends it on May 1:

```yaml
automation:
  - alias: Example Heater season starts
    triggers:
      - trigger: time
        at: "06:00:00"
    conditions:
      - condition: template
        value_template: "{{ now().month == 10 and now().day == 1 }}"
    actions:
      - action: input_boolean.turn_on
        target:
          entity_id: input_boolean.example_heater_in_season
  - alias: Example Heater season ends
    triggers:
      - trigger: time
        at: "06:00:00"
    conditions:
      - condition: template
        value_template: "{{ now().month == 5 and now().day == 1 }}"
    actions:
      - action: input_boolean.turn_off
        target:
          entity_id: input_boolean.example_heater_in_season
```

**A user and a token made for ServiceTag.** In Settings › People, add a person for ServiceTag, allow them to log
in, and leave **Administrator** off. Log in as that user, open the profile's **Security** tab and create a
**long-lived access token**. Home Assistant has no token that can only read: the token can do whatever that user
can. ServiceTag uses it for the reads above and, only for an Asset whose publishing you turn on, for the two helper
changes; a user without administrator rights keeps the rest small. That user must be able to set the helpers you
publish to; if Home Assistant refuses, the publishing block says "Home Assistant refused this change for the access
token's user." If you ever disconnect, delete the token in that same Security tab.

## On the phone: the connection

Open Settings › Utilities › **Home Assistant**.

- **Server address.** "Use https:// with the server's name or private IPv4 address, or http:// with its private IPv4
  address on your home network." The address is an origin only — a scheme, a host and an optional port, with no path,
  query or user name: `http://192.168.0.10:8123` or `https://ha.example:8123`. A private IPv4 address is one in the
  RFC 1918 ranges (those that begin 10, 172.16 to 172.31, or 192.168). Refused, with "That address is not allowed.":
  http to a name, any public or documentation address such as `http://192.0.2.10:8123`, an IPv6 address,
  `localhost`, and anything with a path. While a Season Start date is published, or one is still owed `1970-01-01`,
  a new address is refused: "Stop publishing Season Start dates before changing the address: the old Home Assistant
  has to be told first."
- **Access token.** The field is masked, has no reveal button and is never filled in for you. After Save it empties
  and says "A token is saved on this phone." While any Asset publishes, the screen adds: "When an asset publishes to
  Home Assistant, this phone also sets that helper with this token. Give the token's user only the rights it needs."
- **Test connection** reads `GET /api/` with the form as it stands: "Home Assistant answered. The address and the
  token work.", or the reason it did not. It proves the address and the token, not that the token's user may set a
  helper.
- **How often to check:** Every 12 hours, Once a day (the default), Once a week or Once a month. This is a requested
  interval, not a deadline: Android may run a background check late. A linked Asset whose last successful check is
  older than one interval says "Not checked successfully within the chosen interval." Publishing reads its helpers
  back at the same interval where background work is allowed.
- **Where to check:**
  - **Any network** — "Only for an https:// address you have made reachable from outside your home. The token is
    sent from whatever network this phone is on." Nothing is asked of Android. An http address is refused here: "An
    http:// address needs ‘Only on this home Wi-Fi’. Choose it, or use an https:// address."
  - **Only on this home Wi-Fi** — before every check, the phone confirms it is on the Wi-Fi you captured, and sends
    nothing otherwise ("This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant.").
    Android shows a Wi-Fi network's name only to an app with **precise Location**, and only while Location is on,
    so choosing this asks for it. ServiceTag reads the network's name, not where you are. Then **Use the network
    I'm on now** captures the name ("Home Wi-Fi: ExampleHomeWifi"). In this mode an https name must resolve only to
    private addresses. Publishing follows the same rule: nothing is sent off the captured Wi-Fi.
- **Background checks on this home network** (under "Only on this home Wi-Fi"): **Off** by default. Off, the phone
  checks when you open or return to ServiceTag after the chosen interval and when you tap Sync now, and Android is
  never asked for background location. **On** asks for Location "Allow all the time" (on Android 11 and later you
  choose it on the app's settings page, and Android reminds you from time to time that ServiceTag has it); then a
  background check runs at the chosen interval and contacts Home Assistant only when the phone is on the captured
  Wi-Fi. Refused or taken back later, background checks pause and the phone works as with Off, saying so on the
  screen.
- **Disconnect** deletes the connection, every link, every publication and the token from this phone; linked Assets
  keep their season and history. When a Season Start date is published, the screen first says how many date
  helpers still hold one, and Disconnect sets each to `1970-01-01` where it can; any it cannot reach keep their date
  until you clear them in Home Assistant, and stay listed on this screen until you do. Home Assistant cannot be told
  about the token from the phone, so delete the token in Home Assistant as well.

## On the phone: linking an Asset

With a connection saved, an Asset's season card offers **Link to Home Assistant**. Tap **Choose entity**: the sheet
reads Home Assistant's on/off helpers (its `input_boolean` entities) once and lists them by name, each over its exact
entity ID; search by the name or by the ID, tap the one that is on while this Asset is in season, and it is shown
on the sheet with **Change** beside it. The list is read only when you tap Choose entity or **Refresh**, over the
same address, token and network rule as every other check; if it cannot be read, the sheet says why and keeps
whatever you had chosen. **Enter entity ID manually** is always there instead: type the **Entity ID**
(`input_boolean.example_heater_in_season`): lowercase letters, digits and underscores, with one dot. Either way the
link stores the exact entity ID and never a name: a helper renamed in Home Assistant keeps working, and the next
list shows its new name. A helper this phone publishes to is refused: "%s is a helper this phone sets. Choose the
helper Home Assistant itself controls." Before anything is written, the sheet says what linking does to the Asset's
operating season:

- **Started and ended by hand already:** "From now on Home Assistant starts and ends this asset's season. Its season
  history stays as it is."
- **A calendar season:** its calendar dates are replaced. It keeps the season it has today, and from then on Home
  Assistant starts and ends it.
- **Year-round:** it is in season from today.

In both of the last two cases, maintenance that counts from the start of the season counts from today. If some
maintenance on the Asset is set to be ready before its season, the link is refused with the editor's own sentence
and nothing is written: change that maintenance first.

While linked, the card shows the Asset's season as before, a three-way control in place of Start season and End
season — **Follow Home Assistant**, **Force in season**, **Force out of season** — and **Sync now**, the last
successful check, the latest problem on its own line, and the last change the link made ("Started from Home
Assistant on %s", or "Forced in season on %s" when a forced season applied it). The editor's Operating season block
is read-only until syncing stops, and the Developer API's season start, end and mode changes answer
`409 SEASON_SYNC_ENABLED`.

- **Force in season / Force out of season** sets the season now and keeps it: checks go on and the card still shows
  Home Assistant's answer, but it does not change the season until you choose Follow Home Assistant, which waits for
  a fresh check rather than reusing an old one.
- **Stop syncing** leaves the season as it is, records nothing, and keeps the link for **Resume syncing**.
- **An Asset no longer maintained here** (archived, retired or transferred out) is left alone; its link wakes up
  by itself when the Asset is maintained here again. A replaced Asset's successor has no link. An Asset that comes
  back from a transfer through its Transfer Pack returns without its link: link it again.

## When the phone checks

On Test connection; on the fresh check that linking, changing a link's entity ID, choosing Follow, resuming, or
saving a changed address, token, network option or captured Wi-Fi asks for; on Sync now; when you open or return
to ServiceTag and a link has had no successful check within the chosen interval; and by Android's periodic
background work at the chosen interval, with "Any network", or with "Only on this home Wi-Fi" and Background
checks On. Publishing has its own moments (**When the phone sends**, below).

## A schedule's own season (#125)

Sometimes one piece of maintenance follows a season of its own while the Asset keeps its calendar: the heater's
"Example annual service" should come due when a Home Assistant switch such as
`input_boolean.example_heater_service_window` turns on, whatever the heater's own season says. Such a schedule
**follows its own Home Assistant season**: an on/off helper starts and ends a season that belongs to that schedule
alone, made only of the rows its helper writes, with no calendar window, and with the Asset's maintenance break.

- **Only that schedule's due date moves.** The Asset's season, its season card, its other schedules, its Season
  Start readiness and everything it publishes keep reading the Asset's own season.
- **Follow only.** The same rules as an Asset's link apply — the same connection, token, network rule, interval and
  `input_boolean` helpers, and the same "on starts, off ends, compared first" — but there is no Force: the schedule
  follows Home Assistant or it uses the Asset's season.
- **On the phone**, an Asset's schedule shows its season on its page, in one of three states, each with its own
  action (a group's schedule shows none, and no action is offered while the Asset is transferred out from here):
  - **Using the Asset's season:** "Season: Example Heater's season", with **Follow Home Assistant** while the
    schedule is live and a connection is saved; it opens the same entity sheet as an Asset's link.
  - **Following its own:** "Season: follows input_boolean.example_heater_service_window in Home Assistant", with the
    link's lines as an Asset's card draws them — the last successful check (or that there is none yet), "Not checked
    successfully within the chosen interval.", the time Home Assistant changed it, the last change the link made and
    the latest problem — and **Use the asset's season**, which asks first: "Stop following Home Assistant? This
    schedule will use Example Heater's season again, and its own season history is kept."
  - **Stranded**, on its own season with no link (after Disconnect, or a schedule imported with its own season): "No
    longer linked to Home Assistant: this schedule keeps its own season as it was. Follow Home Assistant again, or use
    the asset's season.", with both **Follow Home Assistant** (while the schedule is live) and **Use the asset's
    season**.
- **Refused**, with nothing written: a group's schedule ("A group's schedule follows no season of its own."); an
  archived schedule; an Asset no longer maintained here; a helper this phone publishes to; and a schedule done before
  the season starts on an Asset with no maintenance break ("This schedule is done before the season starts, and a
  Home Assistant season has no start date to be ready before. Change when it should be done first."). A year-round
  Asset's schedule may follow a season of its own.
- **No start is predicted.** A Home Assistant season has no calendar: out of its own season the schedule shows "Out
  of season until you start it" and no next date until its helper turns `on`, and from linking until the first check
  reads the helper it is out of its season. The Assets list's Home Assistant mark is the Asset's own and does not
  show a schedule's link.
- **Nothing falls back by itself.** Only **Use the asset's season** and archiving the schedule return it to the
  Asset's season. After Disconnect it keeps its own season as it was, stranded, until you follow Home Assistant again
  or use the Asset's season.
- **The season travels; the link does not.** Its own season rows are in every ServiceTag backup, merge and Transfer
  Pack; the link is this phone's alone, so an imported schedule that followed its own season arrives unlinked.

## The Season Start service and readiness (#114)

One schedule can be an Asset's **Season Start service**: the service that makes it ready for its season. The Asset
is **ready for the season** exactly when that schedule has a qualifying completion of this Asset dated on or after
the later of two days — the role's **Services count from** date (its commissioning boundary) and the day the
Asset's season last ended. ServiceTag reads nothing else for it: no condition, health, reminder or due date, and no
name, category or template. Whether an Asset has a season, and when it ended, are that Asset's own settings.

- **Setting the role.** On a schedule's page, **Use as Season Start service** asks: "Use "Example annual service" as
  Example Heater's Season Start service? Completing it marks Example Heater ready for the season. Services dated on or
  after %s count." The **Services count from** date is today unless you choose an earlier one; only completions dated
  on or after it count. **Stop using as Season Start service** clears it. The role changes no rule and no history.
- **Which Assets.** An Asset with seasons (a calendar or a manual season, never year-round) that has no parent, or
  has child assets of its own, and is maintained here. One Season Start service covers an Asset at most. A group's
  schedule may be one, for every Asset in the group: each member is ready from its own completion of it, and one
  member's service never counts for another.
- **One-tap schedules.** A schedule completed with one tap counts only when you say so: the confirm shows "This
  schedule is completed with one tap and no service details. A one-tap completion will still mark the asset ready."
  and **Count one-tap completions**. It is asked again whenever the role's date moves, and when a Season Start
  service is changed from a form to one tap.
- **What qualifies.** A completion of that schedule by this Asset — never another schedule's, a parent's or a
  child's — whose details are filled in ("Not ready: the service's details are still to be filled in" otherwise),
  not brought by a Transfer Pack, dated from the cutoff to today, and written on or after the day it names. The
  latest such date is the service date.
- **The date guard.** A schedule's completion cannot be dated after today, on the phone, the Developer API or the
  MCP: the completion prompt and the entry form will not save it and say "The date cannot be later than today." An
  older record dated ahead keeps its date and does not count until it is edited on or after that day —
  before its day it reads "Not ready yet: the service is dated %s, after today", and from then on "Not ready: the
  service dated %s was entered before that day, so it does not count. Edit it on or after that day to confirm it." A
  record written in advance never becomes a service because midnight came.
- **Service brought by a Transfer Pack never counts** for the Asset that received it: "Not ready: the service on
  record was recorded on another phone and came with this asset. Record this season's service on this phone." A
  ServiceTag backup of your own, restored or merged, counts as recorded here.

The season card shows the answer when a Season Start service covers the Asset or a date is published: "Ready for
the season · serviced %s", or the reason it is not — for example "Not ready: not serviced since the season ended on
%s" or "Not ready: no qualifying service dated on or after %s." — with "Services dated on or after %s count." and
where that day came from:

| the Asset's season | the season's end is | the card says |
|---|---|---|
| a calendar season | the first day after its window, never moved by Home Assistant | "Season end from the calendar season: %s" |
| a manual season Home Assistant drives, whose date publication names a season-ended helper | that helper's date, while it was read recently enough | "Season end from Home Assistant: %1$s · checked %2$s" |
| the same, when that read is too old | the later of the last date read and the phone's own END | "Season end not checked with Home Assistant since %1$s · using %2$s" |
| any other manual season | the phone's latest END | "Season end as recorded on this phone: %s" |
| any season, when the role's date is later | the role's date | "Counted from the day the Season Start service was set: %s" |

Completing a Season Start service says "Completing this marks Example Heater ready for the season." on the
completion itself.

### Counting from the Season Start service (#124)

A schedule's **Start counting from** can be **The Season Start service**, with **Days after the Season Start
service**. In season and not yet serviced, the schedule is **Waiting** ("Waiting for this season's Season Start
service"), with no date, no reminder and no effect on health. Once the Asset is ready it counts from the later of the
service day and the season's start, plus the days after; when a late service moved it, it says "Moved from %1$s to
count from the Season Start service". Out of season it behaves as "When the season starts". It is offered on an
Asset's own schedules with a time rule, on a calendar or manual season, never on a group's schedule, and never on the
Season Start service itself; an Asset with no Season Start service has nothing to count from, and the editor says so.
While a schedule counts from it, the Season Start service cannot be cleared or archived, its group cannot be
archived and the Asset cannot leave that group: the phone names the schedule that counts from it and says "Change
when it starts counting first." (An Asset with a Season Start service cannot become year-round in any case: "…so it
needs seasons.")

## Publishing to Home Assistant (#113, #114)

Each Asset may publish two independent facts, each to its own helper, each turned on by you on the Asset's page:

| publication | helper | value | service action |
|---|---|---|---|
| **Publish equipment status to Home Assistant** | a dropdown (`input_select`) whose options are exactly `UP`, `DOWN` and `DEGRADED`, in any order, with nothing else | the recorded condition: Operational → `UP`, Degraded → `DEGRADED`, Down → `DOWN` | `input_select.select_option` |
| **Publish Season Start date to Home Assistant** | a date helper (`input_datetime`) with a date and no time | the service date (`YYYY-MM-DD`) while the Asset is ready for the season, else **`1970-01-01`** | `input_datetime.set_datetime` |

The status is the **current** condition alone: no health score, due date, completion, season or readiness reaches
it, and readiness never reads a condition. With no condition recorded, nothing is sent ("Waiting: no equipment
status is recorded, so nothing is sent."); ServiceTag never invents an `UP`. A condition recorded with a new reason
but the same value, or one backdated behind the current one, sends nothing.

**`1970-01-01` is the invalid date.** It means "not ready": no Season Start service, no qualifying completion since
the season ended, the completion deleted, the role removed or its schedule archived, the Asset archived, retired,
transferred out or deleted, or publishing stopped. Your Home Assistant rule must treat it as not serviced.

### The helpers: create them first

Create the helpers in Home Assistant (Settings › Devices & services › Helpers, or YAML) before turning publishing on.
In YAML, with **no `initial:`**:

```yaml
input_select:
  example_heater_equipment_status:
    name: Example Heater equipment status
    options:
      - UP
      - DEGRADED
      - DOWN

input_datetime:
  example_heater_service_done:
    name: Example Heater service done
    has_date: true
    has_time: false
  example_heater_season_ended:
    name: Example Heater season ended
    has_date: true
    has_time: false
```

- **The TODAY trap.** A new `input_datetime` that has never been set reads **today's date**, not "unknown" and not
  `1970-01-01` — so a brand-new service-done helper looks like a service done today. Seed every date helper
  explicitly right after creating it, before any automation reads it:

  ```yaml
  action: input_datetime.set_datetime
  target:
    entity_id: input_datetime.example_heater_service_done
  data:
    date: "1970-01-01"
  ```

  A new dropdown likewise starts at its first option, which is not ServiceTag's until the block says "Published".
- **Keep Home Assistant's restore behaviour.** Without `initial:`, a helper keeps its last value across a Home
  Assistant restart. With `initial:` in YAML, every restart puts that value back over whatever ServiceTag wrote, until
  the phone next reads it back and corrects it.
- **No automation sets these two helpers.** Home Assistant automations should read them and never set them; the
  confirm on the phone says so. The season-ended helper is the exception the other way round: Home Assistant stamps
  it and ServiceTag only reads it.

### The season-ended helper

For an Asset whose **manual** season Home Assistant drives through a season link, the date publication also names
the helper Home Assistant stamps when the season ends — "Season-ended helper (the date Home Assistant records when
the season ends)" — required then, and offered only then. The phone reads it whenever it reads every helper back —
on opening and returning, on Publish now and at the background check — and before it sends a date whenever its last
read is no longer fresh. Readiness then counts from Home Assistant's date rather than the day the phone happened to
see the season end:

- **Fresh** while the last good read was made for the publication as it stands now, no longer ago than a week or the
  check interval if that is longer, and not before the Asset's newest season entry.
- **Unverified** otherwise: readiness uses the later of the last date read and the phone's own END, the card says
  "Season end not checked with Home Assistant since %1$s · using %2$s", and the date is still published. Unverified
  never loosens the rule: it takes the later of the two dates. A date published then can still be older than a season
  end the phone has not read, and Home Assistant's own comparison with the season-ended helper refuses it.
- A calendar season's end is its window's, and Home Assistant's date never overrides it. Any other manual season
  uses the phone's END. A date publication turned on before its Asset was linked names no season-ended helper and
  uses the phone's END until you stop publishing and turn it on again naming one.

Stamp the season-ended helper from the in-season helper at a real `on` → `off` change only (the example below stamps
every such change; see the next paragraph for a cold spell) — never from `unavailable` or `unknown`, and never at
Home Assistant start — and set it explicitly once when you commission it:

```yaml
automation:
  - alias: Example Heater season ended stamp
    triggers:
      - trigger: state
        entity_id: input_boolean.example_heater_in_season
        from: "on"
        to: "off"
    actions:
      - action: input_datetime.set_datetime
        target:
          entity_id: input_datetime.example_heater_season_ended
        data:
          date: "{{ now().date().isoformat() }}"
```

If your season can end, start again and end again within one cycle — a cold spell after the season ended, say —
decide whether the later end should move the stamp. Moving it means a service made in between no longer counts;
stamping only the first end of each cycle keeps it. Either way the phone records each END and START for the
schedules; only the stamp decides the boundary.

### Turning publishing on

On the Asset's page, turn on **Publish equipment status to Home Assistant** or **Publish Season Start date to Home
Assistant**. The sheet asks for the **Home Assistant helper**: **Choose helper** lists the dropdowns ("Dropdown
helpers (input_select)") or the date helpers ("Date helpers (input_datetime)"), or enter the entity ID by hand. The
phone reads the helper once and checks its shape — a dropdown with exactly the three options, a date helper with a
date and no time — and refuses one that does not fit, saying why ("%s must be a dropdown helper whose options are
exactly UP, DOWN and DEGRADED.", "%s must be a date helper with a date and no time."). Then it names the exact
destination and what will be sent:

- "Publish Example Heater's equipment status to input_select.example_heater_equipment_status? ServiceTag sets this
  helper whenever the recorded condition changes: Operational → UP, Degraded → DEGRADED, Down → DOWN. Home
  Assistant automations should read it and never set it."
- "Publish Example Heater's Season Start date to input_datetime.example_heater_service_done? While Example Heater is
  ready for the season, ServiceTag sets the service date; otherwise it sets 1970-01-01, which means not ready. Home
  Assistant automations should read it and never set it."

Nothing is written before **Publish**. An Asset archived, retired or transferred out cannot be set up.

### What the block shows

Each block shows its helper, the mapping or the invalid date's meaning, and its state: "Not published yet.",
"Sending %s…", "Published %1$s · %2$s" (this phone's own write, read back), "Home Assistant shows %1$s, which matches
ServiceTag · checked %2$s" (Home Assistant already held it), and the latest problem on its own line. The last
publish and the last matching read are kept apart. Problems name what to do, for example:

- "Home Assistant did not confirm the change. ServiceTag will check the helper before sending again."
- "Home Assistant shows %1$s after the change; something else may be setting %2$s."
- "Check the helper again before ServiceTag publishes to it." — after the connection's address changed, for
  example; a new token or network setting keeps the check.
- "This setup came from another phone's backup, so this phone does not publish. Check the helper and turn
  publishing on again here."

**Publish now** reads the helper back and writes where Home Assistant differs. **Check helper** reads the stored
helper again and records whether it still fits. **Stop publishing** asks first. To publish to another helper, stop
publishing and turn it on again with the new one; the Developer API also has one step for it (**From a
workstation**, below).

### When the phone sends

After every change that moves a published value — a condition recorded, a completion added, edited or deleted, a
role set or cleared, a season ended, an Asset archived — the phone works out each publication's value again and
sends what changed. It sends when it can:

- **at once**, while the app runs and the connection's network rule allows it ("Any network", or on the captured
  home Wi-Fi);
- **when you open or return to ServiceTag**, reading every enabled helper back (on return at most once an hour);
- **at local midnight** while the app is open, so a season that ends by the calendar takes effect that night;
- on **Publish now**;
- in the background, only with "Any network", or with "Only on this home Wi-Fi" and Background checks On: a retry
  after a failure, and a read-back of every enabled helper at the check interval.

The block says so: "Sent when the phone can: on opening, on Publish now, or in the background where allowed. It is
not instant."

- **Current state, not a history.** If the condition goes Down → Degraded → Operational while the phone cannot
  reach Home Assistant, Home Assistant receives `UP` once. Its history shows what was delivered, not every change.
- **Repair.** A helper that Home Assistant reset, or something else set, is put back at the next read-back.
- **Revocation is not instantaneous.** A completion deleted on a phone that cannot reach Home Assistant reaches it
  when the phone next can; until then Home Assistant still holds the old date. A write Home Assistant accepted cannot
  be called back. Build the start rule so that Home Assistant's own comparison with the season-ended helper refuses
  last season's date anyway.

### Stopping, moving and Disconnect

- **Stop publishing equipment status:** "Stop publishing to %s? Home Assistant keeps the value it has now."
- **Stop publishing a Season Start date** first sets the helper to `1970-01-01`: "Stop publishing to %1$s? ServiceTag
  first sets it to %2$s, so Home Assistant stops treating the old date as a service." The same holds for every way
  ServiceTag lets go of a date helper — another helper chosen, the Asset archived, retired, transferred out or
  deleted, Disconnect.
- Until Home Assistant reads `1970-01-01` back, the block says "Not yet cleared in Home Assistant: %1$s may still
  allow starts. ServiceTag keeps trying; if this phone cannot reach Home Assistant, set it to %2$s there." When it
  cannot be delivered at all: "ServiceTag can no longer reach %1$s. It may still allow starts: set it to %2$s in Home
  Assistant." Set it there, then tap **I cleared it in Home Assistant**. A helper whose Asset is gone is listed on the
  Home Assistant screen instead.
- **An Asset no longer maintained here:** its status publication stops and Home Assistant keeps its last value ("Not
  sending: the asset is archived, retired or transferred out. Home Assistant keeps its last value."), and resumes on
  its own when the Asset is maintained here again; its date publication sends `1970-01-01` once and then nothing
  ("Cleared in Home Assistant: %1$s is %2$s. Nothing more is sent while the asset is archived, retired or
  transferred out.").

### One writer per helper

On this phone, a helper is published by one Asset at most ("%s is already set by another asset on this phone."), and
never one a season link reads ("%s is the helper this asset's season follows. Choose another: ServiceTag never sets a
helper it reads."). ServiceTag cannot stop a second phone or a Home Assistant automation from writing the same
helper: give each helper one writer. The read-back shows a stranger's write ("…something else may be setting %2$s"),
and the next read-back puts ServiceTag's value back. Loops through Home Assistant templates — a template reading a
published helper and driving the in-season helper — cannot be seen from the phone: do not build one.

## Home Assistant's side: the start rule

ServiceTag ships none of this; it is an example of the Home Assistant side for one fictional heater, with made-up
names. Adapt it to your own equipment.

**Serviced for the season, derived.** A template sensor that is on exactly when the published date is real and on
or after the season's end, with no writer of its own:

```yaml
template:
  - binary_sensor:
      - name: Example Heater serviced
        unique_id: example_heater_serviced
        state: >
          {% set done = states('input_datetime.example_heater_service_done') %}
          {% set ended = states('input_datetime.example_heater_season_ended') %}
          {{ done not in ['unknown', 'unavailable', '1970-01-01']
             and ended not in ['unknown', 'unavailable']
             and done >= ended }}
```

**The start rule.** Each automation that starts the heater checks, in its conditions: in season, serviced, and no
fault — `input_select.example_heater_equipment_status` not `DOWN` once you publish it — beside whatever rules it
already has:

```yaml
conditions:
  - condition: state
    entity_id: input_boolean.example_heater_in_season
    state: "on"
  - condition: state
    entity_id: binary_sensor.example_heater_serviced
    state: "on"
  - condition: not
    conditions:
      - condition: state
        entity_id: input_select.example_heater_equipment_status
        state: "DOWN"
```

A completion is the owner's record that the service was done — a one-tap completion especially — not a check of the
equipment itself. Keep the appliance's own fault inputs and protective interlocks in the rule whatever the date says.

**Voice and Assist.** Make an ordinary voice or Assist **on** pass the same check as an automatic start, and answer
with the reason when it is refused; **off** is always allowed. The freeze override below is then the only way to
start an appliance that is not serviced, and the appliance's own panel stays outside Home Assistant.

**A freeze override.** When the house needs heat and the heater is not serviced, raise one urgent, actionable
notification with **Start anyway (unserviced)** and **Keep off**. **Start anyway** starts a one-time, time-limited
override for that event only — a `timer` such as `timer.example_heater_freeze_override`, logged and shown while it
runs — which the start rule accepts in place of the serviced check alone; faults, the appliance's interlocks and
every other rule still apply, and when the timer ends the question is asked again. ServiceTag is never told, and
nothing in it changes.

```yaml
conditions:
  - condition: or
    conditions:
      - condition: state
        entity_id: binary_sensor.example_heater_serviced
        state: "on"
      - condition: state
        entity_id: timer.example_heater_freeze_override
        state: active
```

**Two appliances, one season.** Example Heater and Example Boiler may follow one in-season helper and read one
season-ended helper — a read is not a claim — but each has its own Season Start completion and its own service-done
helper (`input_datetime.example_heater_service_done`, `input_datetime.example_boiler_service_done`). One appliance's
service never counts for the other. An appliance whose own start path does not read its serviced sensor is not gated
by it: publishing its date changes nothing there, so do not treat it as covered until its own start controls check
the rule.

**Until publishing is on (the interim).** Set the service-done helper by hand after each service, as before.
ServiceTag treats a value it did not write as Home Assistant's: the block says "Home Assistant shows %1$s, which
matches ServiceTag" or that it differs, never "Published". Once publishing is on, the phone's value replaces any
other at the next read-back.

**Commissioning order.**

1. Create the helpers and seed them explicitly: the service-done helper `1970-01-01`, the season-ended helper the
   date you trust the last season ended (or the commissioning date), never a default or restored value.
2. Add the season-ended stamp.
3. Link the Asset's season to the in-season helper on the phone (Follow).
4. Write the derived sensor and the start rule, but keep the start automations on their old check for now.
5. On the phone: set the Season Start service, record the service, and turn date publishing on, naming the
   season-ended helper. Read the service-done helper in Home Assistant and check it holds the date.
6. Switch the start automations to the new rule, and remove any hand-set writer of the serviced state.
7. Only then rely on the rule. A restored or default date, and Android's delayed delivery, are never evidence of a
   recent service.

## The token, backups and other phones

- The token is kept in a file in ServiceTag's no-backup storage, encrypted with a key in the Android Keystore. It is
  never in a ServiceTag backup, export, merge or Transfer Pack, never on the Developer API or the MCP, and never
  logged.
- The connection, the links and the publications are this phone's own: no ServiceTag backup, export, merge or
  Transfer Pack carries them, nor what was last published. A replace restore removes every season link (the
  connection and the token stay), so link the Assets again. A publication whose Asset a replace restore does not
  bring back is let go of as above, its date helper first owed `1970-01-01`; one whose Asset comes back publishes from
  the restored history.
- What travels is data: the Season Start role and its date on the schedule, each event's mark of having come with a
  Transfer Pack, a schedule's own season rows. A merge or a restore that changes a published Asset's history changes
  what is published.
- Android's own backup of the app may bring back the non-secret part — the address, the entity IDs, the home
  Wi-Fi's name, the modes, the publications and the last status — but never a usable token. Each link then says
  "Enter the access token again: this phone no longer has it." and sends nothing until you do, and a publication
  copied that way never publishes from the new phone until you check its helper and turn it on again there.

## From a workstation

The [Developer API](api/v1.md) and the MCP show and drive the same things, never the secrets:

- **Season sync:** `GET /v1/assets/{id}/season-sync` and the MCP tool `get_season_sync` show a link's non-secret
  state: the connection's settings, the mode, the last check and problem, and the last change with where it came
  from.
- **A schedule's own season:** `GET /v1/schedules/{id}/season-sync` (`get_schedule_season`) reads it, and the same
  path's `POST` links (Follow only, with the caller's own confirmation of the entity) and unlinks it
  (`link_schedule_season`, `unlink_schedule_season`).
- **Readiness and the role:** `GET /v1/assets/{id}/season-readiness` (`get_season_readiness`), and
  `POST /v1/schedules/{id}/role` (`set_schedule_role`), with the one-tap election only when the owner makes it.
- **Publishing:** `GET /v1/assets/{id}/ha-publishing` and `GET /v1/ha-publishing` (`get_ha_publishing`,
  `list_ha_publishing`) read it; `GET /v1/ha-connection/entities/{scope}` (`list_ha_entities`) lists helpers; turning
  publishing on or moving it to another helper is a plan that reads the helper, then the action with the caller's
  own confirmation of the destination (`enable_ha_publishing`, `change_ha_publishing_target`); stop, Check helper and
  Publish now are `disable_ha_publishing`, `check_ha_publishing_helper` and `publish_ha_now`; and "I cleared it in
  Home Assistant" is `acknowledge_ha_decommission`, only with the owner's own word.

No route and no tool takes a value, a date to publish or a ready flag: what is published is always worked out from
the phone's own records. Phone-only, with no route or tool: the token, the address, Test connection, saving the
connection, the home Wi-Fi's name and its capture, the location asks, Disconnect and what it sends first, an Asset's
season link with Follow, Force, Sync now, Stop and Resume syncing, and which phone a publication was turned on on. No
answer carries the address, the home Wi-Fi's name or the token, and every route needs the Developer API screen open
on the phone.

## Limits

- **Background timing is best effort.** The interval may run late; Sync now and Publish now are always there.
  Android may defer background work, a phone that is off sends nothing, and with "Only on this home Wi-Fi" and
  Background checks Off the phone publishes only while the app is open on that Wi-Fi.
- **"Only on this home Wi-Fi" checks the Wi-Fi's name.** Another network using the same name still passes: the check
  narrows where the token goes, it cannot prove the network is yours. Ethernet never passes ("On Ethernet,
  ServiceTag cannot tell which network this is."), and with a VPN up Android hides the Wi-Fi, so this mode does not
  work alongside an always-on VPN.
- **Away from home** with Background checks Off, opening the app after the interval records "not on your home
  Wi-Fi" on each link. That is accurate and expected.
- **`.local` names are not verified.** http takes an IPv4 address only; an https name has to resolve through your
  network's DNS.
- **A certificate from your own certificate authority** is not trusted: "Home Assistant's certificate could not be
  verified."
- **A phone date earlier than the Asset's latest season entry** leaves the season as it is until the date catches
  up.
- **A merge or a Transfer Pack can bring in another season change** for a linked Asset; the next check corrects it
  with at most one change.
- **The last change is kept only on the link.** Season history lists the starts and ends like any other. Disconnect
  deletes the link's record of its last change; the season history stays.
- **Proven on an emulator, not yet on a phone.** A phone's own Wi-Fi, its private DNS and Android's battery saving
  (Doze) have not been observed, and a background check's read of the Wi-Fi name is proven only on an emulator.
- **The entity list shows one kind of helper at a time**, read through the REST states list: `input_boolean` for a
  season, `input_select` or `input_datetime` for a publication; a switch or a sensor is entered by hand for an
  Asset's season (a schedule's own season takes an `input_boolean` only).
  Devices are not shown: Home Assistant's device and display registries are not read. Lists past the first 2,000
  helpers are cut, and the sheet asks you to search; a list over 8 MiB is refused as unreadable, and manual entry
  remains.
- **Some cases are silent.** If the phone's key store or database fails during Sync now or a card action, nothing
  is shown and the last status stays; try again. A capture that reads a blank Wi-Fi name, or a name Android hides
  without a reason, captures nothing and shows no line.
- **The local date decides.** Readiness compares days, not times: a service dated the day the season ended counts
  for the next season, a calendar season ends on its first day out of season, a phone that sees Home Assistant's
  `off` late records a later END, and moving to another time zone can shift a boundary by a day.
- **Today is the phone's.** A completion dated after the phone's today is refused, so a workstation already on
  tomorrow is refused until the phone's midnight.
- **One connection per phone.** Every link and every publication uses it.
- **A second phone, or an automation, can still write a published helper.** One writer is kept within this phone
  only; a phone restored from this one's Android backup stays silent until turned on there, but a phone set up
  separately is not fenced.
- **A season-ended helper moving without a change on the phone** reaches a schedule counting from the Season Start
  service at its next recompute (any change to the Asset, or the daily rebuild), not at once; the card and the
  published date see it at once.
