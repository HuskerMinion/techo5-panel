# TECHO5 Panel

**TECHO5 for Android screens.** An app that turns a tablet or a Meta Portal into a TECHO5 device: a
voice satellite and wall panel for Home Assistant, with no root.

It runs the same daemon as an Echo running TECHO5 (`echod`, from the
[techo5](https://github.com/HuskerMinion/techo5) repo), built for Android and carried inside the
APK. So it does what those devices do: the wake word, voice through Home Assistant's Assist,
timers, alarms and announcements, the TECHO5 intercom, and the Echo Show's own screens on the
tablet's display. Home Assistant adds it as an ESPHome device.

## Supported devices

| Device | System |
|---|---|
| Amazon Fire HD 8, 10th gen (KFONWI) | Fire OS 7.3.2.9 |
| Samsung Galaxy Tab S6 Lite (SM-P613) | Android 14 |
| Meta Portal, 2nd gen (10") | Android 10 |

**May work, untested:** other ARM tablets (64-bit or 32-bit) with Android 8.0 or later. If you try
one, an issue saying how it went is welcome.

## What it needs

- One of the devices above, on the same network as Home Assistant.
- Home Assistant with the ESPHome integration (built in).
- A computer with [adb](https://developer.android.com/tools/releases/platform-tools) to install it.
- Internet access for the app's updates, from this repo's GitHub releases.

Nothing else: the TECHO5 core is inside the APK. To build the APK yourself you need a checkout of
the techo5 repo with the panel build; see [docs/building.md](docs/building.md).

## Install

Download `techo5-panel-<version>.apk` from the [latest release](https://github.com/HuskerMinion/techo5-panel/releases/latest),
then follow [docs/install.md](docs/install.md) for your device. In short: turn on USB debugging,
`adb install` the APK, allow the microphone, and add it in Home Assistant within 15 minutes of its
first start.

After that it keeps itself up to date. It checks the latest release a minute after it starts and
every six hours, and its update card in Home Assistant shows what is new. It installs an update when
you press **Install** on that card, or by itself at night with **Install updates automatically** on.

## Docs

- [docs/install.md](docs/install.md): installing, per device, and adding it to Home Assistant
- [docs/privacy.md](docs/privacy.md): every permission the app asks for, and why
- [docs/building.md](docs/building.md): building the APK yourself
- [docs/plan.md](docs/plan.md): how it fits together
- [docs/screen-protocol.md](docs/screen-protocol.md): the screen link between the app and `echod`

## License

MIT, as techo5. See [LICENSE](LICENSE). What the APK carries from other projects (the daemon's
libraries, fonts, sounds and wake word models) is listed in [NOTICE](NOTICE).

TECHO5 is not affiliated with or endorsed by Amazon, Samsung, Meta or Google.

### Bluetooth proxy

With a matching daemon build, Android BLE advertisements follow **Bluetooth proxy**, on by
default. The app asks for location and nearby-device permissions while in front.
**Bluetooth proxy connections** is a separate switch, initially off; an API encryption key is
required. It lends one GATT connection for service discovery, characteristic/descriptor reads and
writes, and notifications. Turning the proxy off releases connections too. Pairing and service-cache
clearing are not offered. Android handles ordinary Bluetooth audio independently.

The daemon uses the shared connection manager from techo5; this app supplies same-UID local scan
and GATT sockets. Real ATT handles are required. On Android versions that hide descriptor handles,
the bridge refuses the connection rather than inventing handles that conflict with HA's cache.

GATT retries coalesce scan resumes, and all scan registrations share Android's rolling
registration budget. Long-running scans renew before Oreo downgrades them to opportunistic
mode. The shared daemon owns connection deadlines; closing its socket cancels Android GATT.
Run `sh tools/test-bluetooth.sh` with a JDK to check the scan policy without an Android device.
