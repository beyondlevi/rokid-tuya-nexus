# Tuya Smart Home — Rokid Nexus plugin

Control the homes, rooms and devices of a Tuya / Smart Life account from the
Rokid glasses HUD.

It is a **headless phone APK** (a Nexus plugin): no launcher icon, one exported
`NexusPluginService`, and a settings Activity the Nexus hub opens by explicit
component. The glasses hub renders every surface; this plugin only pushes typed
cards and reacts to the four R08 ring verbs.

| | |
|---|---|
| Plugin id | `tuya` |
| Package | `com.beyondlevi.nexus.plugin.tuya` |
| API version | 3 |
| Capabilities | `surfaces` |
| SDK | `com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.13.0` |
| min / target SDK | 30 / 36 |

## Navigation (one axis, always)

```
Homes ──select──► Rooms ──select──► Devices ──select──► Device controls
  ▲                 │                  │                    │
  └────back─────────┴──────back────────┴────────back────────┘         back at Homes = exit
```

Every view is an ordered list walked with NEXT / PREV (wrapping), acted on with
SELECT and left with BACK. Nothing needs a second axis, a long press or a
pointer.

Device controls are typed from the device's own Tuya datapoints:

| Tuya function type | HUD control | SELECT does |
|---|---|---|
| Boolean | switch row | flips it immediately |
| Enum | value row | opens the option list |
| Integer | value row | opens a stepper (NEXT/PREV = ±step, clamped to the declared range) |
| reported-only | dimmed row | nothing (sensors, battery, readouts) |

Raw/JSON/bitmap datapoints and countdown/schedule noise are hidden. IR air
conditioners, which report under different codes than they accept (`power` vs
`switch`, `temp_set` vs `temp`), are resolved through an alias table.

## Configuration

Nexus phone app → Plugins → Tuya Smart Home → settings:

- **Access ID / Access Secret** — from the cloud project on
  [iot.tuya.com](https://iot.tuya.com) (the Smart Life app account must be
  linked to that project).
- **Data center** — the region the project was created in.
- **Account UID** — optional; resolved from a linked device when empty.

"Test connection" signs in and reports how many homes and devices the project
can actually see.

## Build

```bash
sh ./gradlew :app:testDebugUnitTest    # state machine, signing, datapoint mapping
sh ./gradlew :app:assembleDebug
```

`unset NEXUS_RELEASE_KEYSTORE_PASSWORD` before a debug build if the release
signing variables are only partially present in the shell.
