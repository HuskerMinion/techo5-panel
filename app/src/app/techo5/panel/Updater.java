package app.techo5.panel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Keeps the app current from the project's GitHub releases (Release.UPDATE_URL, the latest release's
 * downloads, set when the APK is built): a minute after start, every six hours, and when echod passes
 * on a check asked for on the screen or from Home Assistant, it reads version.json there, and a newer
 * build is downloaded, checked against its sha256 and installed. Everything goes over https. Android
 * only installs an update signed with this app's own key, so nothing else can stand in for one.
 *
 * Android 12 and later install it without asking: the app is updating itself and asks for no user
 * action, which Android allows for an app that targets a recent enough version. Older ones (Fire OS,
 * the Portal) want it confirmed; the prompt comes up and HomeGuard answers it for this app.
 */
final class Updater {
    private static final String TAG = "techo5-panel";
    private static final long FIRST = 60_000, EVERY = 6 * 3600_000L;

    private final Context ctx;

    Updater(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private static boolean started;
    // One check at a time: one asked for can land while the timer's is downloading.
    private static final Object checking = new Object();
    // What the last check found, as the control line says it: "update <running> <offered>".
    private static volatile String found = "";

    /** What to tell echod for Home Assistant's update card: the last check's answer, or what is running. */
    static String report(Context ctx) {
        String f = found;
        if (!f.isEmpty()) return f;
        try {
            return "update " + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "update";
        }
    }

    /** A check now, off the caller's thread: echod passed on one asked for on the screen or from Home Assistant. */
    void checkNow() {
        if (Release.UPDATE_URL.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                check();
            }
        }, "update check").start();
    }

    /** Once per process: the service can be created again in a process that already has the thread. */
    void start() {
        synchronized (Updater.class) {
            if (started) return;
            started = true;
        }
        if (Release.UPDATE_URL.isEmpty()) {
            Log.i(TAG, "updates: no release server built in");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(FIRST);
                    while (true) {
                        check();
                        Thread.sleep(EVERY);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "updates").start();
    }

    private void check() {
        synchronized (checking) {
            checkLocked();
        }
    }

    private void checkLocked() {
        try {
            String json = new String(get(Release.UPDATE_URL + "/version.json"), "UTF-8");
            long code = Long.parseLong(field(json, "versionCode"));
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long mine = info.versionCode;
            // Home Assistant offers what differs from what runs, so a server behind this build offers nothing.
            found = "update " + info.versionName + " " + (code > mine ? field(json, "versionName") : info.versionName);
            ControlLink.tell(found);
            if (code <= mine) {
                Log.i(TAG, "updates: current (" + mine + ")");
                return;
            }
            String apk = field(json, "apk"), sha = field(json, "sha256");
            // A file name beside version.json, as tools/release.sh writes it, and nothing else.
            if (!apk.matches("techo5-panel-[0-9]+\\.apk") || !sha.matches("[0-9a-fA-F]{64}")) {
                Log.w(TAG, "updates: version.json names '" + apk + "', not a release APK; not installing");
                return;
            }
            Log.i(TAG, "updates: " + mine + " -> " + code + ", downloading " + apk);
            File f = new File(ctx.getCacheDir(), "update.apk");
            try (OutputStream o = new FileOutputStream(f)) {
                o.write(get(Release.UPDATE_URL + "/" + apk));
            }
            if (!sha.equalsIgnoreCase(sha256(f))) {
                Log.w(TAG, "updates: " + apk + " does not match its sha256, not installing");
                return;
            }
            install(f);
        } catch (IOException | NumberFormatException | PackageManager.NameNotFoundException e) {
            Log.w(TAG, "updates: " + e);
        }
    }

    private void install(File f) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("panel.apk", 0, f.length())) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            // Mutable: the installer fills in the result. Android 12 and later want that said.
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent done = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, UpdateReceiver.class), flags);
            s.commit(done.getIntentSender());
            Log.i(TAG, "updates: installing");
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException(url + ": " + c.getResponseCode());
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte b : d.digest()) s.append(String.format("%02x", b));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The value of one top-level field in a flat JSON object, as a string. Enough for version.json. */
    private static String field(String json, String name) throws IOException {
        int k = json.indexOf("\"" + name + "\"");
        if (k < 0) throw new IOException("version.json has no " + name);
        int c = json.indexOf(':', k);
        int i = c + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return json.substring(i, end);
    }
}
