# NFC tags for ServiceTag

You do not need to be an NFC expert to use ServiceTag.

If you have ever tapped your phone at a payment terminal to use Google Wallet or another contactless-payment service, you have already used NFC. Most modern Android phones have an NFC reader built in. ServiceTag uses that same general tap-and-read idea, but instead of paying for something, the phone reads a small tag attached to your equipment and opens that equipment's ServiceTag record.

The NFC tag does **not** need a battery, charger, Wi-Fi connection, internet connection, or Bluetooth pairing. It is a passive chip and antenna: when you bring the phone close, the phone's NFC field powers the tag just long enough to read or write its small memory. When the phone moves away, the tag has no power again. There is no battery to run down or replace.

The physical tag cannot phone home, track its location, or connect to a ServiceTag server. ServiceTag stores only a small opaque tag identity on it; the Asset's useful information remains in the local database on your phone. See [NFC tag privacy and how passive tags work](nfc-privacy.md) for what another person could—and could not—learn by scanning one.

You simply attach the tag to the equipment and tap it with your phone when you want to open that Asset in ServiceTag.

## What an NFC tag looks like

NFC tags come in many shapes, but for ServiceTag there are three practical styles worth knowing about.

### Indoor adhesive tags

For equipment kept indoors and away from moisture, a simple adhesive NFC sticker or label is usually the easiest choice.

These work well on things such as:

- a furnace or air handler in a dry utility room;
- a UPS or battery backup;
- appliances;
- tools and shop equipment;
- water-treatment controls or other equipment where the tag can be placed on a dry non-metal surface.

They are thin, inexpensive, and easy to hide or place next to an existing equipment label.

For ordinary household and workshop use, this is usually the simplest place to start.

### Outdoor and weather-resistant disc tags

For equipment exposed to humidity, splashes, rain, dirt, or changing temperatures, use a **weather-resistant or waterproof NFC tag made for outdoor use**.

These are commonly sold as small rigid discs, coins, or pucks with the NFC electronics sealed inside plastic or another protective material. They are a better fit for things such as:

- lawn mowers;
- snowblowers;
- generators;
- pumps;
- hot-tub equipment;
- outdoor HVAC equipment;
- garden and landscaping equipment.

Look for a tag whose seller specifically rates it for the environment where you plan to use it. A rigid disc shape by itself does not guarantee that every tag is waterproof, heat resistant, or suitable for permanent outdoor exposure.

### Tags made for metal surfaces

Ordinary NFC tags do not work well when placed directly on metal.

If the place where you want to mount the tag is metal — which is very common with tools, appliances, HVAC equipment, generators, and machinery — buy a tag specifically described as **on-metal**, **anti-metal**, or **metal-mount**.

These tags include a backing that lets the NFC tag operate while attached to metal. From the user's point of view there is nothing special to configure in ServiceTag: you simply use the metal-compatible tag instead of a normal sticker.

If you are unsure, assume that a painted steel cabinet is still a metal surface and use an on-metal tag.

## Choosing a tag

A simple rule of thumb is:

| Where the tag will live | What to buy |
| --- | --- |
| Indoors, dry, non-metal surface | Regular adhesive NFC tag |
| Outdoors, damp, dirty, or exposed to weather | Weather-resistant/waterproof NFC disc or similar rugged tag |
| Directly on a metal surface | On-metal / anti-metal NFC tag |
| Outdoors **and** on metal | Weather-rated on-metal tag |

You do not need an advanced, high-security, or specialty NFC tag for ordinary ServiceTag use. The important things are that the tag is compatible with NFC, physically suited to where you are mounting it, and easy for your phone to reach.

## Where to put the tag

Choose a location that is:

- easy to reach with your phone;
- unlikely to be scraped, crushed, or removed during normal service;
- not hidden behind a thick panel you would have to remove just to scan it;
- reasonably close to the equipment it identifies;
- appropriate for the tag's indoor, outdoor, or on-metal rating.

A tag near the manufacturer's model/serial label is often convenient because that is already a natural place to look for equipment information.

For equipment that gets dirty or is serviced frequently, place the tag where it can still be wiped clean and scanned without reaching near moving, hot, or electrically hazardous parts.

## How to scan it

The NFC reader is built into the phone, but the exact antenna location varies by phone model.

On many Android phones it is somewhere near the upper or middle portion of the back of the phone. If a tag does not read immediately:

1. unlock or wake the phone;
2. make sure NFC is enabled;
3. place the back of the phone against or very close to the tag;
4. move the phone slowly over the tag until it is detected.

After you learn where the NFC antenna is on your particular phone, scanning becomes a quick tap.

## Using a new tag with ServiceTag

The basic ServiceTag workflow is intentionally simple:

1. Create or open the Asset in ServiceTag.
2. Use ServiceTag's NFC workflow to write/bind the tag to that Asset.
3. Attach the tag to the equipment.
4. Scan it once from its final location to make sure your phone can read it comfortably.
5. From then on, tap the tag whenever you want to open that equipment's maintenance record.

ServiceTag stores the useful equipment and maintenance information in its local database. The NFC tag carries the stable identity needed to get you back to the correct Asset.

That means you can keep adding years of service history, documents, measurements, supplies, components, and maintenance schedules without needing to rewrite all of that information onto the tag.

## A practical starter approach

If you are tagging equipment around a home or workshop, you do not need to solve every NFC purchasing decision up front.

A practical starter set is:

- ordinary adhesive tags for dry indoor equipment;
- a few weather-rated disc tags for outdoor equipment;
- a few on-metal tags for steel cabinets, machinery, tools, and appliances.

Create one Asset, write one tag, attach it, and try scanning it for a few days. Once you know what physical tag style works best for your equipment and your phone, it is easy to expand from there.

The important idea is simple: **the NFC tag is just the passive physical shortcut. ServiceTag is where the maintenance record lives.**

For a plain-English explanation of the tag's power, internet, tracking, and privacy boundaries, see [NFC tag privacy and how passive tags work](nfc-privacy.md).
