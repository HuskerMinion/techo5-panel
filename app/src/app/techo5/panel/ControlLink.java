package app.techo5.panel;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * echod's control line, the abstract socket "techo5.ctl": text lines both ways, the first word saying
 * what each is.
 *
 * Volume: echod sends "volume <step> <steps>" whenever its level changes, and Android's media volume is
 * set to match, so the tablet's own buttons and slider show the level Home Assistant does. A ring
 * sends its own step while it sounds and the level before it after, and muted is never sent as zero. When
 * something else moves the media volume (the buttons, the slider), the new level goes back as
 * "volume <step>".
 *
 * Updates: "update check" from echod is a check asked for on the screen or from Home Assistant, which
 * only reports; "update install" is Home Assistant's Install, or the night's automatic install, which
 * installs a newer build if there is one. The app's Updater does both, since the app is what gets
 * updated. "update <running> <offered>" goes back after every check and whenever echod connects, for
 * Home Assistant's update card.
 *
 * Android's settings: "android settings can" goes to echod on every connection, so its settings screen
 * shows an Android settings row (echod from before the line ignores it); "android settings open" from
 * that row opens the tablet's own Settings (openAndroidSettings), and "android settings none" goes back
 * when the tablet has nothing to open.
 *
 * The tablet's other apps: "apps begin", an "apps item <package> <label>" for each app the launcher
 * would show, and "apps end" go to echod on every connection and whenever "apps list" asks, for its
 * Apps row; "apps open <package>" from that row opens one (openApp).
 */
final class ControlLink {
    private static final String TAG = "techo5-panel";

    private final AudioManager audio;
    private final Updater updater;
    private final Context ctx;
    // The link echod is on, for the Updater's reports; one service, so one link.
    private static volatile ControlLink live;
    private final Object lock = new Object();
    private OutputStream out;
    private int steps = 30;
    // The levels this side set, so the changes they cause, however late Android reports them, are not
    // sent back as the user's (OwnLevels). Under lock.
    private final OwnLevels own = new OwnLevels();
    private final BroadcastReceiver moved;

    ControlLink(Context ctx) {
        audio = ctx.getSystemService(AudioManager.class);
        updater = new Updater(ctx);
        this.ctx = ctx.getApplicationContext();
        live = this;
        // The application context: the service that makes this can be killed and made again, and a
        // receiver registered on it would leak. Only Android sends this, so no other app may.
        moved = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                    volumeMoved(i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1));
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getApplicationContext().registerReceiver(moved, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.getApplicationContext().registerReceiver(moved, filter);
        }
    }

    private int maxIndex() {
        return Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    /** The service is going: no more volume reports from this link. */
    void close() {
        try {
            ctx.unregisterReceiver(moved);
        } catch (RuntimeException ignored) {
        }
        if (live == this) live = null;
    }

    /** One connection at a time, until the service stops and closes the socket (see ScreenLink.serve). */
    void serve(PanelService svc, LocalServerSocket server) {
        while (!svc.stopping()) {
            LocalSocket accepted;
            try {
                accepted = server.accept();
            } catch (IOException e) {
                if (svc.stopping()) return;
                Log.w(TAG, "control socket: " + e.getMessage());
                if (!ScreenLink.pause()) return;
                continue;
            }
            try (LocalSocket conn = accepted) {
                if (!LocalPeer.isOwn(conn, "techo5.ctl")) continue;
                Log.i(TAG, "echod connected to the control line");
                synchronized (lock) {
                    out = conn.getOutputStream();
                }
                send(Updater.report(ctx));
                send("android settings can");
                sendApps();
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                for (String line; (line = in.readLine()) != null; ) handle(line.trim().split("\\s+"));
            } catch (IOException e) {
                Log.w(TAG, "control line ended: " + e.getMessage());
            } finally {
                synchronized (lock) {
                    out = null;
                }
            }
        }
    }

    private void handle(String[] w) {
        if (w.length == 2 && w[0].equals("ble") && (w[1].equals("0") || w[1].equals("1"))) {
            MainActivity.bleSwitch(w[1].equals("1"));
            return;
        }
        if (w.length == 2 && w[0].equals("camera") && (w[1].equals("0") || w[1].equals("1"))) {
            MainActivity.cameraSwitch(w[1].equals("1"));
            return;
        }
        if (w.length >= 3 && w[0].equals("android") && w[1].equals("settings") && w[2].equals("open")) {
            if (!openAndroidSettings()) send("android settings none");
            return;
        }
        if (w.length >= 2 && w[0].equals("apps") && w[1].equals("list")) {
            sendApps();
            return;
        }
        if (w.length >= 3 && w[0].equals("apps") && w[1].equals("open")) {
            openApp(w[2]);
            return;
        }
        if (w.length >= 2 && w[0].equals("update") && (w[1].equals("check") || w[1].equals("install"))) {
            boolean install = w[1].equals("install");
            Log.i(TAG, "updates: " + (install ? "an install" : "a check") + " was asked for");
            updater.checkNow(install);
            return;
        }
        if (w.length >= 3 && w[0].equals("volume")) {
            try {
                int step = Integer.parseInt(w[1]);
                steps = Math.max(1, Integer.parseInt(w[2]));
                int index = Math.round((float) step / steps * maxIndex());
                // echod keeps mute and the lowest steps itself, over a speaker that stays on (so a ring on a
                // muted tablet is heard): any step above zero is at least Android's first.
                if (step > 0) index = Math.max(index, 1);
                synchronized (lock) {
                    own.set(index, android.os.SystemClock.elapsedRealtime());
                }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                Log.i(TAG, "volume " + step + "/" + steps + " -> media " + index + "/"
                        + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * Opens the tablet's own Settings, for a panel that is its home screen and so leaves no other way to
     * them (the Portal's Settings shortcut opens nothing over it): Android's main Settings screen, or its
     * wireless settings on a tablet with none for that. A task of its own, over the panel, until Home or
     * Back brings the panel back. A tap on the panel's screen asked for it, so the app is in front and
     * Android lets it start an activity. False when neither opens.
     */
    private boolean openAndroidSettings() {
        for (String action : new String[] {Settings.ACTION_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS}) {
            try {
                ctx.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Log.i(TAG, "android settings: opened " + action);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "android settings: nothing opens " + action);
            } catch (RuntimeException e) {
                Log.w(TAG, "android settings: " + action + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Sends echod the apps the tablet's launcher would show, by name, for its Apps row: not this app, and
     * not Fire OS's own launcher, which the home guard would only send back. A name is one line, its
     * spaces kept single.
     */
    private void sendApps() {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "apps: " + e.getMessage());
            return;
        }
        List<String[]> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || pkg.equals(HomeGuard.LAUNCHER) || seen.contains(pkg)) continue;
            String label = String.valueOf(r.loadLabel(pm)).trim().replaceAll("\\s+", " ");
            if (label.isEmpty()) label = pkg;
            seen.add(pkg);
            apps.add(new String[] {label, pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        send("apps begin");
        for (String[] a : apps) send("apps item " + a[1] + " " + a[0]);
        send("apps end");
        Log.i(TAG, "apps: told echod of " + apps.size());
    }

    /**
     * Opens an app echod's Apps row picked, in a task of its own over the panel until Home or Back. A tap
     * on the panel's screen asked for it, so the app is in front and Android lets it start an activity.
     */
    private void openApp(String pkg) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.w(TAG, "apps: nothing opens " + pkg);
            sendApps();
            return;
        }
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i(TAG, "apps: opened " + pkg);
        } catch (RuntimeException e) {
            Log.w(TAG, "apps: " + pkg + ": " + e.getMessage());
        }
    }

    /** Sends a line to echod if it is connected; a later connection is brought up to date on its own. */
    static void tell(String line) {
        ControlLink l = live;
        if (l != null) l.send(line);
    }

    private void send(String line) {
        OutputStream o;
        synchronized (lock) {
            o = out;
        }
        if (o == null) return;
        try {
            synchronized (o) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "control: " + e.getMessage());
        }
    }

    /** Android's media volume moved. Ours is ignored, even reported late; anyone else's goes to echod as a step. */
    private void volumeMoved(int index) {
        if (index < 0) return;
        OutputStream o;
        synchronized (lock) {
            if (!own.fromPerson(index, android.os.SystemClock.elapsedRealtime())) return;
            o = out;
        }
        if (o == null) return;
        int step = Math.round((float) index / maxIndex() * steps);
        try {
            synchronized (o) {
                o.write(("volume " + step + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "control: " + e.getMessage());
        }
    }
}
