# Firepit — store listing

Text for Google Play and the App Store, ready for release once the main field tests pass (build plan,
Stage 9). Plain words, the same voice as the website. Character counts are each store's limits.

## Names

| Field | Text | Limit |
|---|---|---|
| App name | Firepit | 30 |
| iOS subtitle | Group chat and map by radio | 30 |
| Play short description | Private group chat and live map over Meshtastic radios. No signal needed. | 80 |
| iOS keywords | meshtastic,lora,mesh,offline,radio,group,chat,map,hiking,festival,camping,off grid | 100 |
| Category | Communication (Play) · Navigation as second category on iOS, Social Networking as the first | |

## Full description (Play) and description (App Store)

Firepit is a private group chat and live map that works over small Meshtastic radios. Talk with your
group and see where everyone is, even where phones have no signal. No internet, no accounts, no
servers.

Each person carries a small LoRa radio, connected to their phone by Bluetooth. The radios pass
messages to each other over kilometres of forest, mountain or festival field. Firepit makes this
easy: join a room by scanning a code, chat with your group, and see everyone on the map.

PRIVATE BY DESIGN
Messages, locations and pins are encrypted on your phone before they leave it. The radios that carry
them cannot read them, not even your own. Room keys move on every hour, so a phone taken later cannot
open what was said before.

ROOMS YOU JOIN IN PERSON
Show a room's code to a friend next to you. They scan it, you both check a short key on screen, and
you let them in. A photo of the code is not enough to get in.

A LIVE MAP
Share where you are with one room, for as long as you choose. Drop pins for the car, the water or
the meeting point. Download map areas before you go, so the map works with no signal at all.

MADE FOR THE OUTDOORS
Quick replies send "On my way" with one tap. Alerts reach everyone in a room. Messages show honestly
whether they reached the mesh, the person, or were read.

WORKS WITH YOUR RADIO
Firepit works with standard Meshtastic radios on firmware 2.7 or newer, so every radio nearby helps
carry your messages. Android and iPhone users chat in the same rooms.

WHAT YOU NEED
- A Meshtastic radio for each person, on firmware 2.7 or newer.
- Bluetooth on your phone.

Firepit is an independent app. It is not part of the Meshtastic project.

## What's new (first release)

First release. Private rooms, direct messages, a live map with pins and offline areas, quick replies
and alerts, on Android and iPhone.

## Privacy answers

Both stores ask what an app collects. Firepit collects nothing: there is no Firepit server and no
analytics, and the only network calls are map tiles from OpenFreeMap (tiles.openfreemap.org), which
can be switched off in Settings with offline areas.

| Question | Answer |
|---|---|
| Data collected (Play Data safety, App Store privacy labels) | None |
| Data shared with third parties | None. Map tile requests show the tile server which part of the map is viewed, as any map does; offline-only mode stops them |
| Location | Used on the phone to show your dot, and sent only to the room you choose, sealed, over the radio. Never to a server |
| Bluetooth | Connects to your own radio |
| Camera | Scans room invite codes; images never leave the phone |
| Notifications | New messages and alerts |
| Encrypted in transit | Yes: every message is sealed on the phone (AES-256-GCM) |
| Can users delete their data | Yes: Settings, Erase history; uninstalling removes everything |
| Account required | No |

**Export compliance (App Store):** uses standard encryption (AES, ECDH, HMAC) only for securing the
user's own messages; qualifies for the exemption for mass-market encryption. Answer "Yes" to using
encryption and "Yes" to the exemption.

## Content rating

Users message each other, and nothing passes through a Firepit server, so there is no moderation.
Play: "Users interact" and "Shares location". App Store: 12+, because users can message each other.

## Screenshots

From each app's demo world (iOS: launch with `-demo`), light and dark, in this order:

1. The chat list with rooms.
2. A room conversation with a reply and quick replies open.
3. The map with people and a pin.
4. Inviting someone: the room's code on screen.
5. A tablet or unfolded phone: the chat and the map side by side.
