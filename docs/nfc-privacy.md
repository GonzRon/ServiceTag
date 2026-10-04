# NFC tag privacy and how passive tags work

A ServiceTag NFC tag is much simpler than many people expect.

The **physical tag itself does not connect to the internet**. It has no cellular radio, Wi-Fi connection, Bluetooth connection, GPS, account, cloud service, or battery. It cannot phone home, report its location, or transmit information by itself.

A typical NFC tag is essentially a tiny memory chip connected to a small antenna. ServiceTag writes a small identifier into that memory. When you bring an NFC-capable phone close to the tag, the phone creates the short-range NFC field that powers the tag for that moment and reads its contents.

When the phone moves away, the tag has no power again.

Because there is no battery to discharge, an NFC tag does not "run out of power." It can remain attached to a piece of equipment for years without charging or battery replacement, subject only to ordinary physical wear, damage, or the environmental limits of the particular tag you bought.

## The phone powers the interaction

The easiest way to think about it is:

> The tag sleeps until an NFC reader is brought close to it.

The phone supplies enough energy through the NFC field for the tag to wake up briefly, read or write its small memory, and respond. The tag does not stay powered afterward.

This is also why NFC works only at very close range. The tag is not broadcasting continuously like a Bluetooth tracker.

A phone is the most common reader for ServiceTag, but technically another compatible NFC reader can energize and read the tag too.

## What ServiceTag actually writes to the tag

ServiceTag deliberately keeps the tag contents minimal.

A current ServiceTag tag contains:

- a marker that identifies the NFC record as belonging to ServiceTag; and
- an opaque, randomly assigned tag identifier used by ServiceTag to find the corresponding record in its local database.

The tag does **not** contain the Asset record itself.

It does not store things such as:

- your name;
- your email address or account information;
- your home address;
- the Asset's friendly name;
- make, model, or serial number;
- purchase information;
- maintenance history;
- schedules or reminders;
- measurements;
- documents or photos;
- Home Assistant credentials;
- notes about where the equipment is located.

Those details live in the ServiceTag database on the owner's phone and in backups the owner explicitly creates.

## What happens if someone else scans the tag?

Suppose someone finds an NFC tag attached to your generator and scans it with their own phone.

If their phone does not have your ServiceTag database, the tag does not give them your generator record. There is no ServiceTag cloud server for the phone to contact and ask, "Whose generator is this?"

At most, an NFC-capable reader can see that the tag contains a ServiceTag-formatted record and can read the tag's opaque identifier. Some NFC-reading tools may also expose the NFC chip's own factory hardware identifier.

Those identifiers identify **the tag**, not you or the equipment record stored on your phone.

A different ServiceTag installation that has never received your database or an intentional Transfer Pack/restore has no local mapping from that tag identifier to your Asset information.

## Can someone tell that it is a ServiceTag tag?

Yes.

ServiceTag does not try to hide the fact that a tag is a ServiceTag NFC tag. The NFC record includes an application/type marker so Android and ServiceTag know how to handle it.

That is different from revealing personal information.

A technically curious person using a generic NFC inspection app may be able to see:

- that the tag is associated with ServiceTag;
- an opaque ServiceTag tag identifier;
- ordinary NFC-chip metadata exposed by the tag.

They cannot derive your maintenance database, Asset details, documents, or personal information from those values alone.

## Can the tag track me or my equipment?

No.

A passive NFC tag:

- has no GPS;
- has no internet connection;
- has no cellular or Wi-Fi radio;
- does not continuously broadcast;
- cannot report when or where it was moved;
- cannot contact ServiceTag on its own.

It responds only when a compatible NFC reader is brought very close and supplies the energy needed for the interaction.

The physical tag therefore behaves very differently from a battery-powered Bluetooth or cellular tracker.

## Does scanning a tag contact the internet?

Scanning the physical tag does not require the tag to contact anything.

ServiceTag resolves the tag identifier against the app's **local database on the phone**.

The ServiceTag Android application does have a few optional network features—for example Home Assistant season synchronization and **Save as document**—but those are separate application features. They do not turn the NFC tag into an internet-connected device, and ordinary NFC identification does not depend on a ServiceTag cloud service.

For the broader application/network boundary, see [Local-first design, privacy, and Android permissions](local-first-and-permissions.md).

## The tag is an identifier, not a secret key

ServiceTag's ordinary NFC tags are designed as convenient physical identifiers, not high-security authentication tokens.

If another person can physically get close enough to the tag with an NFC reader, you should assume they can read the tag's non-secret identifier. With ordinary writable NFC tags, a person with physical access and suitable NFC-writing software may also be able to alter or overwrite the tag.

That does **not** give them the ServiceTag database on your phone.

It does mean you should think of the NFC tag the same way ServiceTag does:

> **The tag tells your phone which local record to open. It is not where the private record lives.**

If a tag is damaged, removed, or overwritten, the Asset's maintenance history remains in ServiceTag. The tag can be replaced or rebound through ServiceTag's NFC workflows.

## What protects the private information?

The main privacy boundary is architectural:

```text
NFC tag
  └─ opaque tag identity
          ↓
your phone
  └─ local ServiceTag database
       ├─ Asset details
       ├─ maintenance history
       ├─ schedules
       ├─ measurements
       ├─ supplies/components
       └─ documents and references
```

The meaningful information remains on the phone rather than being copied onto every physical tag.

That is why a ServiceTag can stay useful for years while the tag itself remains tiny and simple—and why someone scanning the tag does not receive the equipment's private maintenance record.

## Related reading

- [NFC tags for ServiceTag](nfc-tags.md) — choosing, mounting, and scanning ordinary NFC tags.
- [Local-first design, privacy, and Android permissions](local-first-and-permissions.md) — application data, networking, credentials, and Android permissions.
- [Current ServiceTag capabilities](capabilities.md)
