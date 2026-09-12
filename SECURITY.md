# Security Policy

## Reporting a vulnerability

Please do not open a public issue for vulnerabilities that could affect charger
control, authentication, Bluetooth communication, device privacy, or user
safety.

Use GitHub's private vulnerability reporting feature for this repository. Include:

- a description of the vulnerability
- affected versions or components
- reproduction steps
- potential impact
- sanitized logs or evidence
- a suggested mitigation, if available

Do not include credentials, account information, private device identifiers, or
data belonging to another person.

## Safety-sensitive findings

This project interacts with EV charging equipment. Findings involving current
limits, charger state transitions, safety controls, or undocumented commands
should be treated as safety-sensitive.

Testing must only be performed on hardware you own or are explicitly authorized
to test. Do not bypass electrical safety mechanisms or operate equipment beyond
the rated limits of the charger, vehicle, circuit, wiring, or electrical panel.

## Supported versions

Security fixes are applied to the latest version on the default branch. Earlier
commits and experimental branches may not receive updates.

## Response process

A report will be reviewed to determine reproducibility, severity, affected
components, and an appropriate remediation. Valid findings may be acknowledged
in the resulting security advisory with the reporter's permission.
