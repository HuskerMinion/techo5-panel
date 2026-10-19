package app.techo5.panel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Keeps the app current from the project's GitHub releases (Release.UPDATE_URL, the latest release's
 * downloads, set when the APK is built). A check reads version.json there and reports what it offers,
 * for Home Assistant's update card: a minute after start, every six hours, and when echod passes on one
 * asked for on the screen or from Home Assistant. Only an install echod asks for (Home Assistant's
 * Install, or the night's automatic install when that is switched on) downloads a newer build, streamed
 * to a file and checked against its sha256, and installs it. Everything goes over https. The sha256
 * only catches a download gone wrong: Android installs an update only when it is signed with this
 * app's own key and is not older, so nothing else can stand in for one.
 *
 * Android 12 and later install it without asking: the app is updating itself and asks for no user
 * action, which Android allows for an app that targets a recent enough version. Older ones (Fire OS,
 * the Portal) want it confirmed; the prompt comes up and HomeGuard answers it for this app.
 */
final class Updater {
    private static final String TAG = "techo5-panel";
    private static final long FIRST = 60_000, EVERY = 6 * 3600_000L;

    // version.json is a few lines and an APK well under its limit: anything bigger is not a release.
    private static final int MAX_JSON = 64 << 10;
    private static final long MAX_APK = 512L << 20;

    // An update committed and not settled yet (on Android 11 and older its prompt may be waiting), so it
    // is not downloaded and prompted for again: its version code, and when. UpdateReceiver lets go of
    // it when the installer says how it ended; a day on, it is tried again anyway.
    private static final long PENDING_MS = 24 * 3600_000L;
    private static volatile long pendingCode, pendingAt;

    // When this app last committed an update of its own (elapsedRealtime, 0 for never), for HomeGuard:
    // it presses Install on an update prompt only this soon after. The installer asking for that update
    // to be confirmed starts it again (promptComing).
    private static final long PROMPT_WINDOW_MS = 60_000;
    private static volatile long committedAt;

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

    /** Whether this app's own update was committed, or its prompt asked for, in the last minute. */
    static boolean justCommitted() {
        long at = committedAt;
        return at != 0 && SystemClock.elapsedRealtime() - at < PROMPT_WINDOW_MS;
    }

    /**
     * The installer asked for the committed update to be confirmed (UpdateReceiver), which can come well
     * after the commit: overnight, the prompt waits until the tablet allows it. The minute HomeGuard
     * presses Install in starts again from now, while that update is still pending.
     */
    static void promptComing() {
        if (pendingCode != 0) committedAt = SystemClock.elapsedRealtime();
    }

    /** The installer said how the committed update ended: it may be tried again. */
    static void settled() {
        pendingCode = 0;
    }

    /**
     * A check now, off the caller's thread, as echod asked: install false only reports what the server
     * offers; install true also installs a newer build.
     */
    void checkNow(final boolean install) {
        if (Release.UPDATE_URL.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                check(install);
            }
        }, install ? "update install" : "update check").start();
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
                        check(false);
                        Thread.sleep(EVERY);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "updates").start();
    }

    private void check(boolean install) {
        synchronized (checking) {
            try {
                checkLocked(install);
            } catch (Throwable t) {
                // Whatever goes wrong in one check (a truncated version.json, an installer that refuses),
                // the thread and the next check carry on.
                Log.w(TAG, "updates: " + t);
            }
        }
    }

    private void checkLocked(boolean install) throws IOException, PackageManager.NameNotFoundException {
        String json = new String(get(Release.UPDATE_URL + "/version.json", MAX_JSON), "UTF-8");
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
        if (!install) {
            Log.i(TAG, "updates: " + code + " offered, installed when asked");
            return;
        }
        if (pendingCode == code && SystemClock.elapsedRealtime() - pendingAt < PENDING_MS) {
            Log.i(TAG, "updates: " + code + " is already waiting to install");
            return;
        }
        String apk = field(json, "apk"), sha = field(json, "sha256");
        // A file name beside version.json, as tools/release.sh writes it, and nothing else.
        if (!apk.matches("techo5-panel-[0-9]+\\.apk") || !sha.matches("[0-9a-fA-F]{64}")) {
            Log.w(TAG, "updates: version.json names '" + apk + "', not a release APK; not installing");
            return;
        }
        // One downloaded already, for an install that did not go through, is taken as it is.
        File f = new File(ctx.getCacheDir(), "update.apk");
        if (f.isFile() && sha.equalsIgnoreCase(sha256(f))) {
            Log.i(TAG, "updates: " + mine + " -> " + code + ", " + apk + " downloaded already");
        } else {
            Log.i(TAG, "updates: " + mine + " -> " + code + ", downloading " + apk);
            if (!sha.equalsIgnoreCase(download(Release.UPDATE_URL + "/" + apk, f))) {
                Log.w(TAG, "updates: " + apk + " does not match its sha256, not installing");
                if (!f.delete()) Log.w(TAG, "updates: could not remove " + f);
                return;
            }
        }
        install(f, code);
    }

    private void install(File f, long code) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        // A session left from an earlier try that never reached the installer's answer.
        for (PackageInstaller.SessionInfo old : pi.getMySessions()) {
            try {
                pi.abandonSession(old.getSessionId());
            } catch (RuntimeException ignored) {
            }
        }
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        boolean committed = false;
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("panel.apk", 0, f.length())) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            // Mutable: the installer fills in the result. Android 12 and later want that said.
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent done = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, UpdateReceiver.class), flags);
            pendingCode = code;
            pendingAt = SystemClock.elapsedRealtime();
            committedAt = pendingAt;
            s.commit(done.getIntentSender());
            committed = true;
            Log.i(TAG, "updates: installing");
        } finally {
            if (!committed) {
                pendingCode = 0;
                committedAt = 0;
                try {
                    pi.abandonSession(id);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(url + ": " + code);
        }
        return c;
    }

    /** A small download, whole; refused past max bytes. */
    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 13];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (b.size() + n > max) throw new IOException(url + ": more than " + max + " bytes");
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Streams url into the file and returns its sha256, so the APK is never held in memory. */
    private static String download(String url, File to) throws IOException {
        HttpURLConnection c = open(url);
        try (DigestInputStream in = new DigestInputStream(c.getInputStream(), sha256Digest());
             OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_APK) throw new IOException(url + ": more than " + MAX_APK + " bytes");
                o.write(buf, 0, n);
            }
            return hex(in.getMessageDigest().digest());
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws IOException {
        try (DigestInputStream in = new DigestInputStream(new FileInputStream(f), sha256Digest())) {
            byte[] buf = new byte[1 << 16];
            while (in.read(buf) > 0) {
                // Read through: the digest is the point.
            }
            return hex(in.getMessageDigest().digest());
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(String.format("%02x", b));
        return s.toString();
    }

    /** The value of one top-level field in a flat JSON object, as a string. Enough for version.json. */
    private static String field(String json, String name) throws IOException {
        int k = json.indexOf("\"" + name + "\"");
        if (k < 0) throw new IOException("version.json has no " + name);
        int c = json.indexOf(':', k);
        if (c < 0) throw new IOException("version.json has no value for " + name);
        int i = c + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) throw new IOException("version.json ends before " + name + "'s value");
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) throw new IOException("version.json ends inside " + name);
            return json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return json.substring(i, end);
    }
}
