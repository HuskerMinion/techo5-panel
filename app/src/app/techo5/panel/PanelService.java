package app.techo5.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LocalServerSocket;
import android.net.Network;
import android.net.LocalSocket;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Keeps echod running and hands it the microphone and the speaker.
 *
 * echod ships in the APK as lib/<abi>/libechod.so (arm64-v8a and armeabi-v7a) and runs as a child
 * process, as this app's own user; the sockets take only that user (LocalPeer). The microphone
 * goes to it over the abstract socket "techo5.mic" as 16 kHz mono S16LE, as Android delivers it. What
 * it plays comes back over "techo5.spk" as 48 kHz stereo S16LE, written to an AudioTrack; those writes
 * block at the pace the track plays, which is the clock echod's write loop runs on.
 */
public class PanelService extends Service {
    private static final String TAG = "techo5-panel";
    private static final String CHANNEL = "panel";
    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20 ms, what echod reads at a time
    private static final int PLAY_RATE = 48000;
    private static final int PLAY_PERIOD = PLAY_RATE / 50 * 4; // 20 ms of 48 kHz stereo S16LE, in bytes

    private volatile boolean stopping;
    private PowerManager.WakeLock wake;
    private WifiManager.MulticastLock multicast;
    private LocalServerSocket micServer;
    private LocalServerSocket speakerServer;
    private LocalServerSocket screenServer;
    private LocalServerSocket controlServer;
    private volatile Process daemon;
    private Notification notification;
    private ConnectivityManager.NetworkCallback networkWatch;
    private ControlLink control;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TECHO5 Panel", NotificationManager.IMPORTANCE_LOW));
        notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("TECHO5 Panel")
                .setContentText("Listening for the wake word")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        goForeground();

        // The CPU stays up so the wake word keeps listening with the screen off, and multicast is let in
        // so Home Assistant can find the device over mDNS.
        wake = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "techo5:daemon");
        wake.acquire();
        multicast = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .createMulticastLock("techo5:mdns");
        multicast.setReferenceCounted(false);
        multicast.acquire();

        // Android 11 and later refuse apps the netlink requests Go lists interfaces with, so echod
        // reads them from a file this keeps current: at start, and whenever the network changes.
        writeInterfaces();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        networkWatch = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                writeInterfaces();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                writeInterfaces();
            }

            @Override
            public void onLost(Network network) {
                writeInterfaces();
            }
        };
        cm.registerDefaultNetworkCallback(networkWatch);

        new Updater(this).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                serveMic();
            }
        }, "mic").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                serveSpeaker();
            }
        }, "speaker").start();
        try {
            screenServer = new LocalServerSocket("techo5.screen");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ScreenLink.serve(PanelService.this, screenServer);
                }
            }, "screen").start();
        } catch (IOException e) {
            Log.e(TAG, "screen socket", e);
        }
        try {
            controlServer = new LocalServerSocket("techo5.ctl");
            control = new ControlLink(this);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    control.serve(PanelService.this, controlServer);
                }
            }, "control").start();
        } catch (IOException e) {
            Log.e(TAG, "control socket", e);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                installModels();
                superviseDaemon();
            }
        }, "daemon").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    /**
     * Foreground as what it does: sound always, and the microphone while it is granted. Android 11 and
     * later only let a service use the microphone in the background when it says so, Android 14 refuses
     * to say so without the permission, and a service started from the background (at boot) may not use
     * it at all; so it is said again each time the panel's own screen starts the service, which is in
     * front. Older Androids take no types.
     */
    private void goForeground() {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(1, notification);
            return;
        }
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        if (Build.VERSION.SDK_INT >= 30
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(1, notification, types);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground without the microphone for now: " + e.getMessage());
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        }
    }

    /** Whether the service is going: the socket loops end rather than accept again. */
    boolean stopping() {
        return stopping;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Process p = daemon;
        if (p != null) p.destroy();
        if (networkWatch != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkWatch);
            } catch (RuntimeException ignored) {
            }
        }
        if (control != null) control.close();
        try {
            if (micServer != null) micServer.close();
            if (speakerServer != null) speakerServer.close();
            if (screenServer != null) screenServer.close();
            if (controlServer != null) controlServer.close();
        } catch (IOException ignored) {
        }
        if (multicast != null) multicast.release();
        if (wake != null) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Writes files/net, one line an interface: name, index, MTU, Go's net.Flags as a number, then each
     * address as addr/prefix. Written whole and renamed into place, so echod never reads half of it.
     */
    private synchronized void writeInterfaces() {
        StringBuilder out = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                int flags = 0;
                if (ni.isUp()) flags |= 1 | 32; // up, running
                if (ni.isLoopback()) flags |= 4;
                if (ni.isPointToPoint()) flags |= 8;
                if (ni.supportsMulticast()) flags |= 16;
                out.append(ni.getName()).append(' ').append(ni.getIndex()).append(' ')
                        .append(ni.getMTU()).append(' ').append(flags);
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    String host = a.getAddress().getHostAddress();
                    int scope = host.indexOf('%');
                    if (scope >= 0) host = host.substring(0, scope);
                    out.append(' ').append(host).append('/').append(a.getNetworkPrefixLength());
                }
                out.append("\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "listing interfaces", e);
            return;
        }
        File tmp = new File(getFilesDir(), "net.new");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "writing interfaces", e);
            return;
        }
        if (!tmp.renameTo(new File(getFilesDir(), "net"))) Log.w(TAG, "could not put the interfaces in place");
    }

    /** One connection at a time: echod connects when it takes the microphone, and again after a restart. */
    private void serveMic() {
        try {
            micServer = new LocalServerSocket("techo5.mic");
        } catch (IOException e) {
            Log.e(TAG, "mic socket", e);
            return;
        }
        while (!stopping) {
            try (LocalSocket conn = micServer.accept()) {
                if (!LocalPeer.isOwn(conn, "techo5.mic")) continue;
                Log.i(TAG, "echod connected to the microphone");
                stream(conn.getOutputStream());
            } catch (IOException e) {
                if (!stopping) Log.w(TAG, "microphone connection ended: " + e.getMessage());
            }
        }
    }

    private void stream(OutputStream out) throws IOException {
        int size = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), FRAME * 2 * 8);
        // Which capture path, for measuring: files/mic-source holds "vc" for voice communication, which
        // turns on the platform's own echo canceller where it has one, or "mic" for the raw microphone;
        // anything else is voice recognition, Android's path for speech to text.
        String which = readSetting("mic-source");
        int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        if ("vc".equals(which)) source = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        if ("mic".equals(which)) source = MediaRecorder.AudioSource.MIC;
        AudioRecord rec = new AudioRecord(source, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            askForMic();
            throw new IOException("AudioRecord did not initialize (is the microphone permission granted?)");
        }
        int session = rec.getAudioSessionId();
        Log.i(TAG, "capture source " + (which.isEmpty() ? "vr" : which)
                + ", platform AEC available=" + AcousticEchoCanceler.isAvailable()
                + " AGC=" + AutomaticGainControl.isAvailable() + " NS=" + NoiseSuppressor.isAvailable());
        if ("vc".equals(which) && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
            if (aec != null) {
                aec.setEnabled(true);
                Log.i(TAG, "platform AEC enabled=" + aec.getEnabled());
            }
        }
        byte[] buf = new byte[FRAME * 2];
        rec.startRecording();
        Log.i(TAG, "recording at " + RATE + " Hz, buffer " + size + " bytes");
        try {
            while (!stopping) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) throw new IOException("AudioRecord.read returned " + n);
                out.write(buf, 0, n);
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }

    /** One connection at a time, like the microphone: echod connects when it takes the speaker. */
    private void serveSpeaker() {
        try {
            speakerServer = new LocalServerSocket("techo5.spk");
        } catch (IOException e) {
            Log.e(TAG, "speaker socket", e);
            return;
        }
        while (!stopping) {
            try (LocalSocket conn = speakerServer.accept()) {
                if (!LocalPeer.isOwn(conn, "techo5.spk")) continue;
                Log.i(TAG, "echod connected to the speaker");
                play(conn.getInputStream());
            } catch (IOException e) {
                if (!stopping) Log.w(TAG, "speaker connection ended: " + e.getMessage());
            }
        }
    }

    private void play(InputStream in) throws IOException {
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(PLAY_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        // Which kind of sound Android is told this is, for measuring: files/play-usage holds
        // "assistant" or "game"; anything else is media. Vendors hang their own processing (loudness,
        // limiting) on media, which a linear echo canceller cannot follow.
        String usage = readSetting("play-usage");
        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        if ("assistant".equals(usage)) {
            ab.setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        } else if ("game".equals(usage)) {
            ab.setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        }
        AudioAttributes attrs = ab.build();
        Log.i(TAG, "playback usage " + (usage.isEmpty() ? "media" : usage));
        // 200 ms: a slow tablet stalls now and then, and a track that runs dry is a gap in the sound and a
        // jump in the echo reference. Deeper delays replies and stops. Never below what the track needs.
        int size = Math.max(AudioTrack.getMinBufferSize(PLAY_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT), PLAY_PERIOD * 10);
        AudioTrack track = new AudioTrack(attrs, format, size, AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        byte[] buf = new byte[PLAY_PERIOD];
        track.play();
        Log.i(TAG, "playing at " + PLAY_RATE + " Hz, buffer " + size + " bytes");
        try {
            while (!stopping) {
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n < 0) return;
                    got += n;
                }
                for (int off = 0; off < got; ) {
                    int n = track.write(buf, off, got - off);
                    if (n < 0) throw new IOException("AudioTrack.write returned " + n);
                    off += n;
                }
            }
        } finally {
            track.stop();
            track.release();
        }
    }

    private long askedForMic;

    /**
     * The microphone was taken back (Fire OS does, at boot and after Wi-Fi drops): put the panel on the
     * screen, which asks for it again, at most once every half minute. Without this the panel stays
     * deaf until somebody opens it.
     */
    private void askForMic() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - askedForMic < 30000) return;
        askedForMic = now;
        Log.w(TAG, "the microphone permission is gone, asking again");
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** A one-line setting from the files directory, empty when there is none. */
    private String readSetting(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(new File(getFilesDir(), name)), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The wake word models the app carries, any this tablet does not have yet, into files/models where
     * echod looks: a new panel gets them all, and one set up before a model joined the app gets it at
     * its next update. One already there is left alone, as boot.sh does on the Echos.
     */
    private void installModels() {
        File dir = new File(getFilesDir(), "models");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "could not make " + dir);
            return;
        }
        int added = 0;
        try {
            String[] names = getAssets().list("models");
            if (names == null) return;
            for (String name : names) {
                File f = new File(dir, name);
                if (f.exists()) continue;
                File tmp = new File(dir, name + ".new");
                try (InputStream in = getAssets().open("models/" + name);
                     FileOutputStream o = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
                if (tmp.renameTo(f)) added++;
            }
        } catch (IOException e) {
            Log.w(TAG, "installing the wake word models", e);
        }
        if (added > 0) Log.i(TAG, "wake word models: " + added + " files added from the app");
    }

    /** Starts echod, logs what it prints, and starts it again if it exits, waiting longer each time. */
    private void superviseDaemon() {
        String exe = getApplicationInfo().nativeLibraryDir + "/libechod.so";
        File files = getFilesDir();
        long wait = 2000;
        while (!stopping) {
            long started = System.currentTimeMillis();
            try {
                ProcessBuilder pb = new ProcessBuilder(exe, "run").directory(files).redirectErrorStream(true);
                pb.environment().put("HOME", files.getAbsolutePath());
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                daemon = pb.start();
                Log.i(TAG, "echod started: " + exe);
                // echod logs to logcat itself (tag techo5), so its console is only kept, not copied: the
                // last lines are logged if it exits, which is where a panic lands. Copying every line
                // doubled the log and pushed everything else out of a small logcat buffer.
                java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(daemon.getInputStream()))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        if (tail.size() == 60) tail.removeFirst();
                        tail.addLast(line);
                    }
                }
                int code = daemon.waitFor();
                Log.w(TAG, "echod exited with " + code + "; its last output:");
                for (String line : tail) Log.w("techo5-out", line);
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "running echod", e);
            }
            if (stopping) return;
            // A run that lasted a while starts the backoff over; one that died at once waits longer.
            if (System.currentTimeMillis() - started > 60000) wait = 2000;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
            }
            wait = Math.min(wait * 2, 60000);
        }
    }
}
