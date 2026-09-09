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

## Article

For the engineering story behind this project—including the architecture,
BLE protocol analysis, HCI validation, and design decisions—see:

**[When My EV Charger App Disappeared, I Built a Local Android Controller](https://medium.com/@ajitraut04/when-my-ev-charger-app-disappeared-i-built-a-local-android-controller-58a1bb94de37)**
