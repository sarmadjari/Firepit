# RAK WisMesh Tag — Button Guide

**Author:** Sarmad Jari

The Tag has **two buttons** and one **magnetic port**. No display, so buttons and LED/buzzer feedback are the only way to interact with it directly.

## Front button (center, face of device)

| Action | What it does |
|---|---|
| Hold 5 seconds (device off) | **Power on** — beeps once it's ready |
| Long press (device on) | **Power off** — tones step upward, then a power-down tone plays on release |
| Single press | Disable LED and buzzer notifications |
| Double press | Send a one-off **position ping** to the network |
| Triple press | Toggle the **GPS module** on/off |
| Quadruple press | **Mute/unmute** the device |

## Rear button (top-left, small/recessed)

| Action | What it does |
|---|---|
| Single press | Reset the device |
| Double press | Enter **DFU mode** (for flashing firmware) |

This button never powers the device off — it's for reset and firmware recovery only.

## Magnetic port (top, 4-pin)

Same port handles charging, USB, DFU, and serial logging.

- Blinking LED (0.5s on/off) = charging
- Use a 5V / 0.5A charger — avoid fast chargers
- Doubles as the DFU/firmware-flash connection when needed

## LED indicators

| LED | Meaning |
|---|---|
| 🔴 Red | Charging — lit whenever USB is connected, even if the device is off |
| 🟢 Green (solid) | Powered on |
| 🟢 Green (breathing) | BLE DFU mode |
| 🟢 Green (blinking) | MCU activity |
| 🔵 Blue | New message received |

With no screen, these lights and the tone patterns above are the only feedback you get — worth learning them before you're relying on the Tag out in the field.

## How to share your current location with trusted people

The double-press "position ping" only reaches people who are on a channel where **location sharing is turned on**. It doesn't ask you who to send it to — it broadcasts to whichever room already has position sharing enabled. So the setup happens in the app, once, before you ever need the button:

1. **In the Meshtastic app**, open the private room/channel your trusted people are in → channel settings → turn on **position sharing** (choose your precision level here too — full precision for close friends, coarser for a larger group)
2. **Check the primary/public channel (Channel 0)** — if it also has location sharing on, your position broadcasts there too, publicly, to anyone on the default mesh. Turn it off there if you only want trusted people to see you
3. Meshtastic only auto-broadcasts your *live* position to one channel — the lowest-indexed one with location sharing enabled. If you're in more than one private room, pick the one that should get your regular updates
4. Make sure **GPS is on** (triple-press toggles it) and has a fix — it needs open sky, so give it a minute outdoors before your first ping

Once that's set up, you never need the app again for a quick share: **double-press the front button** any time you want to send your current position immediately, instead of waiting for the next scheduled update.



RAK's own quick-start guide and Meshtastic's hardware docs don't fully agree with each other, and community reports across firmware versions add a third variation (some users see the mute toggle on **triple** press, not quadruple). The table above reflects Meshtastic's official hardware page — but before relying on a specific press count for something important, **test it once on your own unit** and note what actually happens, since it can shift with firmware version.

## Useful tips

- **Set your region first.** The device won't transmit anything until a region is set in the app — do this before expecting any button function to work
- No screen means every action is confirmed by tone or LED — mute the buzzer (single press) and you lose that feedback, so know how to unmute (quadruple press) before you turn it off
- The GPS toggle (triple press) is a fast way to save battery in low-power situations without touching the app
- The double-press position ping is also a handy way to confirm the Tag is alive and has a fix, without opening the app at all
- Default pairing PIN is `123456` when connecting the app to the Tag over Bluetooth for the first time
- If the Tag won't connect or seems unresponsive, a single press of the **rear** button resets it — this doesn't erase your settings, it's just a soft reset
