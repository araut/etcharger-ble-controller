# Contributing

Contributions that improve device compatibility, protocol documentation,
reliability, testing, or user safety are welcome.

## Ways to contribute

- Report compatibility results for ETCharger hardware or firmware variants
- Improve BLE connection and recovery behavior
- Document protocol commands and response fields
- Add tests for frame encoding, decoding, and validation
- Improve Android accessibility, diagnostics, or documentation
- Propose support for additional EVSE protocols

## Hardware validation

Clearly distinguish observed behavior from assumptions derived through static
analysis.

For hardware-tested changes, include:

- charger model and firmware version, when observable
- Android device and OS version
- operation tested
- expected and actual behavior
- relevant sanitized logs
- number of successful test cycles

Never publish device identifiers, account data, credentials, Bluetooth captures
containing private information, or other users' data.

## Safety

EV charging equipment controls high-voltage electrical systems. Contributions
must not bypass charger safety mechanisms or recommend operating beyond the
rated limits of the charger, vehicle, circuit, wiring, or electrical panel.

Protocol commands with uncertain behavior should remain clearly marked as
experimental until independently validated against owned hardware.

## Pull requests

Keep pull requests focused on one logical change. Before submitting:

- describe the problem and proposed solution
- document hardware validation or state that the change is unvalidated
- include tests where practical
- confirm that no secrets or private device information are included
- verify that the project builds successfully

By contributing, you agree that your contribution will be licensed under the
repository's existing license.
