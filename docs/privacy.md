# Privacy and permissions

TECHO5 Panel is a thin app around TECHO5's daemon, `echod`. The daemon does the voice work and talks
to Home Assistant, as it does on an Echo running TECHO5. The app hands it the microphone, the
speaker and the screen, keeps it running, and updates itself. This page lists every permission the
app asks for and why.

## Where things go

- **Microphone audio** goes from Android to `echod`, inside the tablet, over a local socket. `echod`
  listens for the wake word on the tablet itself. Only after a wake word does it send audio, to your
  Home Assistant's Assist pipeline, as any TECHO5 device does.
- **The app's local sockets** (`techo5.mic`, `techo5.spk`, `techo5.screen`, `techo5.ctl`) take only
  connections from the app's own user, which is `echod`. Any other app that connects is logged and
  closed before anything is sent.
- **The app itself** connects to one place: this repo's GitHub releases, over https, to check for
  and download updates. It sends nothing about you or the tablet.
- **The app is not debuggable** in release builds, and allows no plain-http traffic.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | The wake word and voice. Asked for on first start. Without it there is no wake word. |
| `INTERNET` | `echod` talks to Home Assistant and the other TECHO5 devices; the app checks for updates. |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | The app tells `echod` the tablet's network addresses (Android 11 and later hide them from it) and updates them when the network changes. |
| `CHANGE_WIFI_MULTICAST_STATE` | Lets mDNS in, so Home Assistant can find the panel. |
| `WAKE_LOCK` | Keeps the CPU awake with the screen off, so the wake word keeps listening. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The service that runs `echod` stays up, and may use the microphone and play sound while another screen is up. Android shows its notification. |
| `RECEIVE_BOOT_COMPLETED` | Starts the panel after a reboot or a power cut, and after an update. |
| `REQUEST_INSTALL_PACKAGES` | Installs the app's own updates. It installs nothing else. |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | On Android 12 and later, the app's own updates install without a prompt. |

Updates are checked against the sha256 in the release's `version.json`, and Android itself refuses
any APK not signed with the app's own key.

## HomeGuard, the accessibility service

HomeGuard is off until you turn it on over adb (see [install.md](install.md)). It is for tablets
that will not let the panel be the home screen, keep its microphone, or update without a prompt.

**What it may see.** Only windows of these packages, set in
[`app/res/xml/home_guard.xml`](../app/res/xml/home_guard.xml):

- `com.amazon.firelauncher`, the Fire's launcher
- `com.android.packageinstaller`, `com.google.android.packageinstaller`, Android's installer
- `com.android.permissioncontroller`, `com.google.android.permissioncontroller`, Android's
  permission prompts

Android does not send it events from any other app. It keeps nothing it sees and sends nothing
anywhere.

**What it does, and nothing else:**

1. **When the Fire launcher's window comes up**, it brings TECHO5 Panel back to the front. It also
   does this once when it starts. It presses nothing.
2. **On a permission prompt**, it taps **Allow** only when the prompt's message names this app
   (TECHO5 Panel) and asks to record audio. It taps the prompt's "while using the app" button, or
   its plain Allow button when there is no such button. Any other prompt is left alone. The check
   reads the English text "record audio", so on a tablet set to another language it presses nothing.
3. **On an install prompt**, it taps **Install** only when the prompt is an update to this app: the
   installer's app name is TECHO5 Panel (the Fire's install screen), or the dialog's title is
   TECHO5 Panel and it says it is an update (newer Android). The button is disabled for a moment
   after a prompt appears, so it tries again every half second, up to ten times. Any other install
   prompt is left alone.

On Android 12 and later the app's updates need no prompt, so step 3 does not come up there.
