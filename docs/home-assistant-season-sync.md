# Home Assistant season sync

ServiceTag can let one Home Assistant on/off helper decide when an Asset's operating season starts and ends. When
the phone checks, it reads that helper's state from your Home Assistant; if the answer differs from the Asset's
season today, it records the same Start season or End season you could tap yourself. Schedules, maintenance
policy, breaks and season history then behave exactly as they do for a season started and ended by hand.

Every name, address and network on this page is made up: an Asset called **Example Heater**, a helper
`input_boolean.example_heater_in_season`, a Home Assistant at `http://192.168.0.10:8123` (or
`https://ha.example:8123`) and a home Wi-Fi called `ExampleHomeWifi`. Use your own.

## What the phone does

- **It reads, and only reads.** A check is one `GET /api/states/<entity id>` to the address you entered, carrying
  your access token; Test connection is one `GET /api/`. A redirect is not followed, and https trusts Android's own
  certificate authorities only.
- **`on` starts the season and `off` ends it** — exactly those two answers. Anything else (`unavailable`,
  `unknown`, an entity Home Assistant does not have, a refused token, no answer at all) changes nothing, and the
  Asset's season card says why.
- **It compares first.** The season changes only when Home Assistant's answer differs from the Asset's season
  today, so reading `on` ten times records one start, and two checks at once cannot record it twice.
- **The date is the day the phone applies it**, never the time Home Assistant says the helper changed. That time is
  shown for information ("Changed in Home Assistant %s").
- **It sees the current state, not history.** If the helper goes off and on again while the phone cannot reach Home
  Assistant, the phone sees only the final state, and applies at most one change for it.

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
can. ServiceTag uses it only for the reads above, so a user without administrator rights keeps the rest small. If you
ever disconnect, delete the token in that same Security tab.

## On the phone: the connection

Open Settings › Utilities › **Home Assistant**.

- **Server address.** "Use https:// with the server's name or private IPv4 address, or http:// with its private IPv4
  address on your home network." The address is an origin only — a scheme, a host and an optional port, with no path,
  query or user name: `http://192.168.0.10:8123` or `https://ha.example:8123`. A private IPv4 address is one in the
  RFC 1918 ranges (those that begin 10, 172.16 to 172.31, or 192.168). Refused, with "That address is not allowed.":
  http to a name, any public or documentation address such as `http://192.0.2.10:8123`, an IPv6 address,
  `localhost`, and anything with a path.
- **Access token.** The field is masked, has no reveal button and is never filled in for you. After Save it empties
  and says "A token is saved on this phone."
- **Test connection** reads `GET /api/` with the form as it stands: "Home Assistant answered. The address and the
  token work.", or the reason it did not.
- **How often to check:** Every 12 hours, Once a day (the default), Once a week or Once a month. This is a requested
  interval, not a deadline: Android may run a background check late. A linked Asset whose last successful check is
  older than one interval says "Not checked successfully within the chosen interval."
- **Where to check:**
  - **Any network** — "Only for an https:// address you have made reachable from outside your home. The token is
    sent from whatever network this phone is on." Nothing is asked of Android. An http address is refused here: "An
    http:// address needs ‘Only on this home Wi-Fi’. Choose it, or use an https:// address."
  - **Only on this home Wi-Fi** — before every check, the phone confirms it is on the Wi-Fi you captured, and sends
    nothing otherwise ("This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant.").
    Android shows a Wi-Fi network's name only to an app with **precise Location**, and only while Location is on,
    so choosing this asks for it. ServiceTag reads the network's name, not where you are. Then **Use the network
    I'm on now** captures the name ("Home Wi-Fi: ExampleHomeWifi"). In this mode an https name must resolve only to
    private addresses.
- **Background checks on this home network** (under "Only on this home Wi-Fi"): **Off** by default. Off, the phone
  checks when you open or return to ServiceTag after the chosen interval and when you tap Sync now, and Android is
  never asked for background location. **On** asks for Location "Allow all the time" (on Android 11 and later you
  choose it on the app's settings page, and Android reminds you from time to time that ServiceTag has it); then a
  background check runs at the chosen interval and contacts Home Assistant only when the phone is on the captured
  Wi-Fi. Refused or taken back later, background checks pause and the phone works as with Off, saying so on the
  screen.
- **Disconnect** deletes the connection, every link and the token from this phone; linked Assets keep their season
  and history. Home Assistant cannot be told from the phone, so delete the token in Home Assistant as well.

## On the phone: linking an Asset

With a connection saved, an Asset's season card offers **Link to Home Assistant**. Enter the **Entity ID**
(`input_boolean.example_heater_in_season`): lowercase letters, digits and underscores, with one dot. Before
anything is written, the sheet says what linking does to the Asset's operating season:

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
checks On.

## The token, backups and other phones

- The token is kept in a file in ServiceTag's no-backup storage, encrypted with a key in the Android Keystore. It is
  never in a ServiceTag backup, export, merge or Transfer Pack, never on the Developer API or the MCP, and never
  logged.
- The connection and the links are this phone's own: no ServiceTag backup, export, merge or Transfer Pack carries
  them. A replace restore removes every link (the connection and the token stay), so link the Assets again.
- Android's own backup of the app may bring back the non-secret part — the address, the entity IDs, the home
  Wi-Fi's name, the modes and the last status — but never a usable token. Each link then says "Enter the access
  token again: this phone no longer has it." and sends nothing until you do.

## From a workstation

`GET /v1/assets/{id}/season-sync` on the [Developer API](api/v1.md) and the MCP tool `get_season_sync` show a link's
non-secret state: the connection's settings, the mode, the last check and problem, and the last change with where
it came from. Neither shows the address, the home Wi-Fi's name or the token, and neither changes anything.

## Limits

- **Background timing is best effort.** The interval may run late; Sync now is always there.
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
- **Some cases are silent.** If the phone's key store or database fails during Sync now or a card action, nothing
  is shown and the last status stays; try again. A capture that reads a blank Wi-Fi name, or a name Android hides
  without a reason, captures nothing and shows no line.
