# Screen protocol (M3)

echod draws its own screens, the same ones an Echo Show draws (clock, weather, cards, the drawer, call
and timer pages), on a canvas in memory. The app owns the real display: it puts what echod sends on
the screen, and sends back where fingers go. This is the app's side; the daemon's is
`echod/internal/lib/panelscreen` in techo5's panel build.

Only the app's own user may connect: the app checks each connection's peer credentials and closes
any other caller (`LocalPeer`).

## The socket

One abstract local socket, **`techo5.screen`** (`LocalServerSocket("techo5.screen")`), served by
`PanelService` from the moment it starts, one connection at a time, like `techo5.mic` and
`techo5.spk`. The screen and the touchscreen in echod share the one connection. When it ends, accept
the next: echod connects again by itself, and sends the whole canvas again on a new connection.

All numbers are little-endian.

## App to echod

### Hello (first, once per connection)

| Bytes | Field | |
|---|---|---|
| 4 | magic | ASCII `T5P1` |
| 2 | width | canvas width in pixels |
| 2 | height | canvas height in pixels |
| 2 | density | the panel's dpi (`DisplayMetrics.densityDpi`), for the log |
| 2 | flags | 0 |

Send it as soon as the connection is accepted, from the service: it must not wait for the activity.
echod gives up after 10 seconds without one.

**Choosing the canvas.** The layouts are the Echo Show's, drawn for landscape at 960 wide and scaled by
width; they are known good at 960x480 and 1280x800 (Show 8). So:

1. Take the display's real size (`WindowManager.getDefaultDisplay().getRealSize`) **in landscape**:
   the larger side is the width.
2. If the width is over 1280, scale both sides down to make it 1280. Round to whole pixels.
3. Width and height must each be 240 to 1920, or echod drops the connection.

| Tablet | Panel (landscape) | Canvas |
|---|---|---|
| Fire HD 8 (10th gen) | 1280x800 | 1280x800 (a Show 8) |
| Galaxy Tab S6 Lite | 2000x1200 | 1280x768 |

The activity is held in landscape (`android:screenOrientation="sensorLandscape"`) so the canvas
fills the screen. A portrait canvas would be accepted, but the layouts would sit in the top part of
it.

### Touch (`'T'`, 7 bytes)

| Bytes | Field | |
|---|---|---|
| 1 | `'T'` (0x54) | |
| 1 | action | 0 down, 1 move, 2 up |
| 1 | pointer | `MotionEvent` pointer id, 0 to 9 |
| 2 | x | canvas pixels, 0 to width-1 |
| 2 | y | canvas pixels, 0 to height-1 |

From the activity's `onTouchEvent`: `ACTION_DOWN`/`ACTION_POINTER_DOWN` are down,
`ACTION_MOVE` is a move for every pointer, `ACTION_UP`/`ACTION_POINTER_UP` are up, and
`ACTION_CANCEL` is an up for every pointer still down. Map from view pixels to canvas pixels through
the same scaling the frames are drawn with. echod turns these into the Show's gestures (tap, swipes,
the volume drag) with the Show's own code, so send the raw finger, not gestures. Batch nothing:
one record per pointer per event is fine.

Anything else sent to echod ends the connection.

## echod to app

### Frame (`'F'`)

| Bytes | Field | |
|---|---|---|
| 1 | `'F'` (0x46) | |
| 2 | x | left of the rectangle that changed |
| 2 | y | top |
| 2 | w | |
| 2 | h | |
| w*h*4 | pixels | RGBA, 8 bits each, rows top to bottom, no padding |

The first frame on a connection is the whole canvas; after that only the smallest rectangle holding
every pixel that changed. Keep one `Bitmap` the canvas's size (`ARGB_8888`, whose memory order is
R, G, B, A), copy each rectangle into it (`Bitmap.setPixels` from converted ints, or
`copyPixelsFromBuffer` into a scratch bitmap of the rectangle's size and `Canvas.drawBitmap` it in),
then invalidate. Draw the bitmap scaled to fill the view (`drawBitmap(bmp, null, viewRect, paint)`
with filtering on). Alpha is always 255.

Keep the bitmap in the service, not the activity: frames arrive while the activity is gone, and the
activity draws the latest bitmap when it comes back.

How often: about once a second when idle (the clock), faster during a voice turn, a swipe, or the
volume overlay. A clock tick is a few rows; a page change is most of the canvas, about 4 MB at
1280x800.

### Backlight (`'B'`, 3 bytes)

| Bytes | Field | |
|---|---|---|
| 1 | `'B'` (0x42) | |
| 2 | level | 0 to 255 |

- **0** is the screen off: dim the window to black (`WindowManager.LayoutParams.screenBrightness = 0`
  and draw black) and let `FLAG_KEEP_SCREEN_ON` go, so Android can sleep the panel. Without the
  system permission an app cannot switch the panel off itself; black and dimmed is the panel's off.
- **1 to 255** is a brightness: `screenBrightness = level / 255f` on the window, and
  `FLAG_KEEP_SCREEN_ON` held, so the panel stays on as an Echo's does. If the screen went to sleep
  while it was off, wake it (a `FULL_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP`, released at once, or
  `setTurnScreenOn(true)` on the activity) and bring the activity to the front.

echod's night light uses low levels (1 to about 20), so do not floor them.

## Not covered yet

- The activity's own content beyond the canvas (the setup text it shows today) goes once the canvas
  is up.
- A screen turned on or off by the user (power button) is not reported to echod.
- Echo-only pieces of the display (camera view, lens cover) stay off on a panel.
