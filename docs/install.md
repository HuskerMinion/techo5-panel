# Installing TECHO5 Panel

All three devices take the same APK. You install it once over adb. After that it updates itself
from its releases (see [Updates](#updates)).

The commands are the same on Windows, Linux and macOS. On Windows, run them in PowerShell from the
folder with `adb.exe` in it, or put that folder on your PATH.

## Before you start

- Get [adb](https://developer.android.com/tools/releases/platform-tools) (Android's platform-tools).
- Download `techo5-panel-<version>.apk` from the
  [latest release](https://github.com/HuskerMinion/techo5-panel/releases/latest).
- Put the device on the same Wi-Fi network as Home Assistant.
- Plug the device into the computer with a USB cable that carries data.

`adb devices` should list the device as `device`. If it says `unauthorized`, look at the device's
screen and allow the computer.

## Every device: the app itself

These steps are the same everywhere. The sections below say where each device differs.

1. Install it:

   ```
   adb install techo5-panel-<version>.apk
   ```

2. Let it install its own updates:

   ```
   adb shell appops set app.techo5.panel REQUEST_INSTALL_PACKAGES allow
   ```

3. Open **TECHO5 Panel** and tap **Allow** for the microphone. Without it there is no wake word.

4. Add it to Home Assistant (see [below](#adding-it-to-home-assistant)) within 15 minutes.

## Amazon Fire HD 8 (10th gen, KFONWI, Fire OS 7.3.2.9)

**USB debugging.** Settings > Device Options > About Fire Tablet. Tap **Serial Number** seven times.
Back in Device Options, open **Developer options** and turn on **USB debugging**.

Then do the steps under [Every device](#every-device-the-app-itself).

**The home screen.** Fire OS will not let another app replace its launcher. Instead, the app has an
accessibility service, HomeGuard, that puts the panel back in front whenever the Fire launcher comes
up. Turn it on over adb:

```
adb shell settings put secure enabled_accessibility_services app.techo5.panel/app.techo5.panel.HomeGuard
adb shell settings put secure accessibility_enabled 1
```

The first command replaces the list of accessibility services that are on. If you use another one,
list both, separated by `:`.

HomeGuard also does two more things on the Fire, both for this app only:

- Fire OS takes the microphone back from apps not installed from Amazon's store, at every boot and
  sometimes after Wi-Fi drops. The app asks again, and HomeGuard taps **Allow** on that prompt.
- Fire OS asks before an app updates itself. HomeGuard taps **Install** on this app's update prompt.

See [privacy.md](privacy.md) for exactly what it looks at and presses.

After the home button or a reboot, the Fire launcher shows for a few seconds before the panel comes
back. That is Android's delay for switching apps, not a fault.

**Good to know:**

- `adb shell am force-stop app.techo5.panel` also turns HomeGuard off. Run the two commands above
  again after it.
- The lock screen can be turned off so the panel comes straight up: `adb shell locksettings set-disabled true`.

## Samsung Galaxy Tab S6 Lite (SM-P613, Android 14)

**USB debugging.** Settings > About tablet > Software information. Tap **Build number** seven times.
Then Settings > Developer options > **USB debugging** on.

Then do the steps under [Every device](#every-device-the-app-itself).

**The home screen.** Settings > Apps > Choose default apps > Home app > **TECHO5 Panel**. To undo,
pick One UI Home there again.

**Play Protect.** Turn off Play Protect's app scanning, or the app's updates fail. Open the Play
Store, tap your profile picture, then **Play Protect**, the gear, and turn off **Scan apps with Play
Protect** and **Improve harmful app detection**. With it on, Android offers to send the APK to
Google for a scan and the update fails with `INSTALL_FAILED_VERIFICATION_FAILURE`.

**Screen lock.** For a wall panel, set Settings > Lock screen > Screen lock type to **None** or
**Swipe**, so the panel comes up by itself after a reboot.

Android 12 and later let the app update itself without a prompt, so HomeGuard is not needed here.
You can still turn it on with the two Fire commands above; it only acts on this app's own prompts.

## Meta Portal (2nd gen, 10", Android 10)

The Portal has official ADB, behind a system update.

**ADB.**

1. Set the Portal up with your Meta or WhatsApp account, and set a device PIN.
2. Settings > About > check for updates. Install them until **Settings > Debug** shows up.
3. Settings > Debug > **ADB Enabled**, and enter the PIN. The switch can look like it did not change.
   It still works.
4. Plug in the USB-C port behind the speaker. Allow the computer on the Portal's screen.

ADB may be off again after the Portal restarts. Do all the steps below in one go.

**Freeze system updates and Meta's app verifier.** Do this after the update that brought ADB.

```
adb shell pm disable-user --user 0 com.facebook.aloha.otaui
adb shell pm disable-user --user 0 com.facebook.aloha.alohaotasetup
adb shell pm disable-user --user 0 com.facebook.appverifier
```

The first two stop system updates, so a later one cannot change what works now. The third is Meta's
app verifier: with it on, the app's own updates fail with `INSTALL_FAILED_VERIFICATION_FAILURE`.
Undo any of them with `adb shell pm enable <package>`.

Then do the steps under [Every device](#every-device-the-app-itself).

**The home screen.**

```
adb shell cmd package set-home-activity app.techo5.panel/.MainActivity
```

**HomeGuard.** Android 10 asks before an app updates itself. Turn HomeGuard on with the two Fire
commands above, and it taps **Install** on this app's update prompt.

**The microphone works only while the panel is on the screen.** Meta's system silences the
microphone for any app that is not in front. As the home app, TECHO5 Panel is in front nearly all
the time. When another app covers it, the wake word stops until the panel is back.

**Good to know:**

- Do not change **Display size** in the Portal's settings. It can leave the Portal in a boot loop.
- Do not factory reset the Portal once it is set up. Setting it up again needs Meta's servers.
- The privacy switch cuts the camera and the microphone in hardware. With it closed, there is no
  wake word.

**If Windows loses the Portal (Code 43).** Windows can stop seeing the Portal's ADB, with Code 43 in
Device Manager. It asks the Portal for a Microsoft descriptor the Portal answers badly, and remembers
the result. Tell Windows to skip that question. In an administrator PowerShell:

```
reg add "HKLM\SYSTEM\CurrentControlSet\Control\usbflags\2EC619030409" /v osvc /t REG_BINARY /d 0000 /f
```

`2EC6` and `1903` are the Portal's USB IDs and `0409` its USB revision. If yours differ, Device Manager
shows them under the device's Properties > Details > Hardware Ids (`USB\VID_2EC6&PID_1903&REV_0409`).

Then remove the Portal in Device Manager (Uninstall device), plug it into a USB port on the back of
the computer (not a front-panel hub), restart the Portal and turn **ADB Enabled** on again with the
PIN. Leave the registry value in place.

## Other tablets (may work, untested)

Any ARM tablet with Android 8.0 or later may work. Turn on USB debugging, do the steps under
[Every device](#every-device-the-app-itself), and make TECHO5 Panel the home app in Android's
settings. On Android 11 and older, turn HomeGuard on so update prompts are answered. If you try one,
please open an issue saying how it went.

## Adding it to Home Assistant

On its first start the panel opens a 15-minute window in which a Home Assistant can add it.

1. In Home Assistant, go to Settings > Devices & services. The panel shows up as a discovered
   ESPHome device, named **TECHO5 Panel** with a few letters after it.
2. Click **Add**. Home Assistant sets its own key on the panel, and they use it from then on.
3. On the device's ESPHome entry, under **Configure**, turn on **Allow the device to perform Home
   Assistant actions**.

Missed the window? Open it again from the panel's setup page, as on any TECHO5 device. Whatever Home
Assistant had the panel before loses it.

If Home Assistant does not find it, check that both are on the same network: discovery uses mDNS,
which does not cross subnets or VLANs on its own.

## Camera (where the tablet has a front camera)

**Camera streaming** is off by default. Turn it on in Home Assistant, then bring the TECHO5 Panel
screen to the front to allow Android's camera prompt. The camera entity can appear before permission
is granted, but it cannot show an image until you allow access. If you decline, switch Camera
streaming off and on to ask again. Turning it off stops capture and discards any cached still.

## Updates

The app checks this repo's latest release a minute after it starts, every six hours, and when Home
Assistant asks. A check only looks: the update card in Home Assistant shows the newer version.

The update installs when you press **Install** on that card. With **Install updates automatically**
on, it also installs by itself between 2 and 5 am, when nothing is playing or ringing, as on an Echo.
The newer build is downloaded, checked against its sha256 and installed. Android only takes an
update signed with the same key as the app already there, and never an older version.

## Looking at the logs

```
adb logcat -s techo5 techo5-panel
```

`techo5` is the daemon, `techo5-panel` the app around it.

## Removing it

```
adb uninstall app.techo5.panel
```

Then delete its entry in Home Assistant. The device's own home screen takes over again. On the
Portal, turn system updates and the app verifier back on with `adb shell pm enable <package>` if you
want them.
