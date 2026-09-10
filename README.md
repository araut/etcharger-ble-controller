# ETCharger BLE Controller

An independent Android Bluetooth Low Energy (BLE) controller and interoperability
reference for ETCharger-compatible EVSE hardware.

This project documents a subset of the local BLE protocol used by an ETCharger
EV charger and provides an independently written Kotlin implementation for
communicating with authorized hardware without depending on the original
mobile application's cloud service.

> This project is independent interoperability work. It is not affiliated with,
> endorsed by, or sponsored by ETCharger, EVENTEK, or the original application
> developers.

---

## Why This Project Exists

The Android application used with my EV charger became unavailable through
normal app-store distribution.

The hardware itself was still functional.

Rather than replacing working charging equipment, I investigated whether the
charger could continue to be controlled locally.

Analysis of the original application showed that important EVSE operations were
performed directly over Bluetooth Low Energy.

I documented the relevant BLE behavior and built an independent Android client
that implements the portions of the protocol required for basic local control.

The resulting architecture is intentionally simple:

```text
Android Controller
        |
        | Bluetooth Low Energy
        v
       EVSE
        |
        v
      Vehicle

```

## Validation

The behaviors below have been exercised against physical hardware I own,
not just inferred from static analysis of the original application.

| Operation | Protocol commands | Status |
|---|---|---|
| BLE scan / connect / GATT discovery | — | ✅ Validated |
| Handshake | `0x01` request → `0x02` reply | ✅ Validated |
| Current Status read | `0x09` request → `0x0A` reply | ✅ Validated |
| EVSE Info read | `0x07` request → `0x08` reply | ✅ Validated |
| Start Charging | `0x03` (`action=0x01`) → `0x04` reply | ✅ Validated |
| Pause Charging | `0x03` (`action=0x02`) → `0x04` reply | ✅ Validated |

### Validation scope

Validation means the operation was independently reproduced using this
controller against physical hardware and produced the expected device behavior
or protocol response.

It does **not** imply that every field in the corresponding response structure
has been fully interpreted.

### Test environment

- Hardware: ETCharger-compatible EVSE
- Firmware: documented where observable
- Android: tested on a physical Android device
- Transport: Bluetooth Low Energy
- Vehicle charging behavior: Start and Pause verified end-to-end

Private device identifiers, account data, and raw Bluetooth captures are
intentionally excluded from the public repository.

### Current limitations

The following areas are not yet considered fully validated:

- response fields whose semantics or units are still uncertain
- cumulative energy and usage counters
- behavior across other firmware or hardware variants
- recovery from BLE disconnects during command execution
- malformed or truncated protocol frames
- permission revocation during an active session
- charging-current configuration
- additional protocol commands recovered from the original application

Validation applies only to the specific operations and fields listed above,
not to the complete ETCharger protocol surface.



## Article

For the engineering story behind this project—including the architecture,
BLE protocol analysis, HCI validation, and design decisions—see:

**[When My EV Charger App Disappeared, I Built a Local Android Controller](https://medium.com/@ajitraut04/when-my-ev-charger-app-disappeared-i-built-a-local-android-controller-58a1bb94de37)**
