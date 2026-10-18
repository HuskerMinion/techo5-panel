# TECHO5 Panel plan

## Goal

An Android app that turns a tablet or a Meta Portal into a TECHO5 device. Home Assistant sees it the
way it sees an Echo running TECHO5 (ESPHome native API), and it joins the TECHO5 intercom,
announcements and settings like any other unit. No root needed.

## How it fits together

- **The core stays in Go, in the techo5 repo.** `echod` gets one more device type (the `panel` build),
  next to the Show, Dot and Spot builds. Voice, wake word, intercom, alarms, screens and the Home
  Assistant connection are the same code, so a fix lands everywhere. No forked copy.
- **This repo is a thin app in plain Java** (no Kotlin, no Gradle) for what only Android can do:
  - microphone and speaker through Android's audio APIs, passed to `echod`
  - a foreground service and the home-launcher role, so it starts at boot and stays up
  - permissions and updates
  - a surface for `echod`'s own screens
- `echod` runs as a native binary shipped inside the APK (`lib/<abi>/libechod.so`), talking to the
  app over local sockets that only the app's own user may use.

## Milestones

- **M0, survey the test devices.** Model and OS version of each, over adb, read-only. Dumps stay
  outside the repo. Done.
- **M1, the spike (no UI).** A bare app runs `echod`: microphone audio, the wake word, and Home
  Assistant finds and adds it over the ESPHome API. Done.
- **M2, sound out.** Speaker, announcements, timers and alarms, media playback, the echo reference.
  Done.
- **M3, screens.** `echod`'s own screens on the tablet, touch, screen on/off and brightness from Home
  Assistant. Done; see [screen-protocol.md](screen-protocol.md).
- **M4, panel mode.** Home launcher, start at boot, stay alive, updates. Done.
- **M5, Meta Portal.** The same build on a Portal: ADB, Meta's package verifier, the Portal's own mic
  rules. Done for the Portal 2nd gen.
- **Later.** Bluetooth proxy, camera.

## Supported devices

| Device | Android | Notes |
|---|---|---|
| Amazon Fire HD 8 (10th gen, KFONWI) | Fire OS 7.3.2.9 | The stock launcher stays; HomeGuard brings the panel back in front of it |
| Samsung Galaxy Tab S6 Lite (SM-P613) | Android 14 | TECHO5 Panel as the home app; silent updates |
| Meta Portal (2nd gen, 10") | Android 10 | Official ADB; the microphone works while the panel is on the screen |

Other ARM tablets with Android 7.1 or later may work, untested. See [install.md](install.md).
