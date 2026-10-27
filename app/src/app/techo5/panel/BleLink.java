package app.techo5.panel;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A passive advertisement bridge from Android's BLE scanner to the panel daemon. */
final class BleLink {
    private static final String TAG = "techo5-panel";
    private final Context context;
    private final LocalServerSocket server;
    private volatile LocalSocket active;
    private volatile boolean closed;
    private final Object scanLock = new Object();
    private BluetoothLeScanner currentScanner;
    private ScanCallback currentCallback;
    private ScanSettings currentSettings;
    private ScanSession currentSession;
    private boolean scanning;
    private int connectionPauses;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScanStartBudget budget = new ScanStartBudget();
    private Runnable restart;
    // Coalesce the shared manager's retries, rather than register a scan between each attempt.
    private static final long RESUME_DELAY_MS = 1500;
    // Oreo eventually moves a long scan to opportunistic mode. Renew before that happens.
    private static final long RENEW_MS = 20 * 60 * 1000;

    private void cancelRestart() {
        if (restart != null) main.removeCallbacks(restart);
        restart = null;
    }

    // Called only with scanLock held. All starts, including retries/renewals, share one budget.
    private void startScan(final ScanSession session) {
        if (closed || connectionPauses != 0 || scanning || currentSession != session || session.failed) return;
        long now = SystemClock.elapsedRealtime();
        long delay = budget.delay(now);
        if (delay != 0) { scheduleStart(session, delay); return; }
        budget.started(now);
        try {
            currentScanner.startScan(null, currentSettings, currentCallback);
            scanning = true;
            Log.i(TAG, "ble scan registered");
            restart = new Runnable() {
                @Override public void run() {
                    synchronized (scanLock) {
                        if (restart != this) return;
                        restart = null;
                        if (currentSession != session || !scanning || connectionPauses != 0 || closed) return;
                        try { currentScanner.stopScan(currentCallback); }
                        catch (RuntimeException e) { session.failed = true; return; }
                        scanning = false;
                        startScan(session);
                    }
                }
            };
            main.postDelayed(restart, RENEW_MS);
        } catch (RuntimeException e) {
            session.failed = true;
            Log.w(TAG, "ble scan start failed", e);
        }
    }

    private void scheduleStart(final ScanSession session, long delay) {
        cancelRestart();
        restart = new Runnable() {
            @Override public void run() {
                synchronized (scanLock) {
                    if (restart != this) return;
                    restart = null;
                    startScan(session);
                }
            }
        };
        main.postDelayed(restart, delay);
    }

    private void endScan(ScanSession session) {
        synchronized (scanLock) {
            if (currentSession != session) return;
            cancelRestart();
            if (scanning && currentScanner != null) {
                try { currentScanner.stopScan(currentCallback); }
                catch (RuntimeException e) { Log.w(TAG, "ble scan stop failed", e); }
            }
            currentScanner = null; currentCallback = null; currentSettings = null;
            currentSession = null; scanning = false;
        }
    }

    BleLink(Context context) throws IOException {
        this.context = context;
        server = new LocalServerSocket("techo5.ble");
    }

    void close() {
        synchronized (scanLock) { closed = true; cancelRestart(); }
        try { server.close(); } catch (IOException ignored) { }
        LocalSocket c = active;
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }

    void pauseForConnection() {
        synchronized (scanLock) {
            connectionPauses++;
            if (connectionPauses == 1 && scanning && currentScanner != null) {
                try {
                    currentScanner.stopScan(currentCallback);
                    scanning = false;
                    Log.i(TAG, "ble scan paused for GATT connection");
                } catch (RuntimeException e) {
                    connectionPauses--;
                    Log.w(TAG, "could not pause BLE scanning", e);
                    return; // Keep the running scan's renewal timer.
                }
            }
            cancelRestart();
        }
    }

    void resumeAfterConnection() {
        synchronized (scanLock) {
            if (connectionPauses == 0) return;
            connectionPauses--;
            if (connectionPauses != 0 || scanning || currentScanner == null || closed
                    || currentSession == null || currentSession.failed) return;
            scheduleStart(currentSession, RESUME_DELAY_MS);
        }
    }

    void serve() {
        while (!closed) {
            LocalSocket c;
            try { c = server.accept(); }
            catch (IOException e) {
                if (!closed) Log.w(TAG, "ble accept", e);
                return;
            }
            try (LocalSocket conn = c) {
                if (!LocalPeer.isOwn(conn, "techo5.ble")) continue;
                active = conn;
                scan(conn);
            } catch (IOException e) {
                if (!closed) Log.w(TAG, "ble connection: " + e.getMessage());
            } finally {
                active = null;
            }
        }
    }

    private boolean permitted() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 31 ||
                (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED &&
                 context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED);
    }

    private void scan(final LocalSocket conn) throws IOException {
        InputStream in = conn.getInputStream();
        final OutputStream out = conn.getOutputStream();
        if (in.read() < 0) return; // active mode: Android's public scan API chooses its own mode
        if (!permitted()) { out.write(1); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) { out.write(2); return; }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { out.write(3); return; }

        final ScanSession session = new ScanSession();
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { session.add(result); }
            @Override public void onBatchScanResults(List<ScanResult> batch) {
                for (ScanResult result : batch) session.add(result);
            }
            @Override public void onScanFailed(int code) {
                Log.w(TAG, "ble scan failed: " + code);
                session.failed = true;
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
        try {
            synchronized (scanLock) {
                currentScanner = scanner;
                currentCallback = callback;
                currentSettings = settings;
                currentSession = session;
                startScan(session);
                if (session.failed) throw new IllegalStateException("scan start rejected");
            }
            out.write(0);
            Log.i(TAG, "ble scan started");
        } catch (RuntimeException e) {
            endScan(session);
            out.write(4);
            Log.w(TAG, "ble scan start", e);
            return;
        } catch (IOException e) {
            endScan(session); // A daemon that left during the handshake must not leave a scan behind.
            throw e;
        }

        Thread eof = new Thread(new Runnable() {
            @Override public void run() {
                try { while (conn.getInputStream().read() >= 0) { } }
                catch (IOException ignored) { }
                session.failed = true;
            }
        }, "ble-eof");
        eof.setDaemon(true);
        eof.start();

        try {
            while (!closed && !session.failed) {
                byte[] frame;
                try { frame = session.queue.poll(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (frame == null) continue;
                out.write(frame.length & 255);
                out.write(frame.length >>> 8);
                out.write(frame);
            }
        } finally {
            endScan(session);
            conn.close();
            Log.i(TAG, "ble scan stopped, reports=" + session.reports +
                    " dropped=" + session.dropped);
        }
    }

    private static final class ScanSession {
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(512);
        volatile boolean failed;
        long reports;
        long dropped;

        void add(ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null || result.getDevice() == null) return;
            String address = result.getDevice().getAddress();
            if (address == null || address.length() != 17) return;
            GattLink.remember(result.getDevice());
            byte[] data = compact(record.getBytes());
            if (data.length == 0 || data.length > 512) return;
            byte[] frame = new byte[8 + data.length];
            try {
                for (int i = 0; i < 6; i++)
                    frame[i] = (byte) Integer.parseInt(address.substring(i * 3, i * 3 + 2), 16);
            } catch (NumberFormatException e) { return; }
            frame[6] = addressType(result.getDevice());
            frame[7] = (byte) result.getRssi();
            System.arraycopy(data, 0, frame, 8, data.length);
            reports++;
            if (!queue.offer(frame)) dropped++;
        }
    }

    // Android 8.1 has no public address-type accessor. Use one when the platform exposes it;
    // otherwise report public. GATT uses the BluetoothDevice remembered from the actual scan,
    // allowing Android's stack to retain its controller-side address information.
    private static byte addressType(BluetoothDevice device) {
        try {
            Method m = BluetoothDevice.class.getMethod("getAddressType");
            Object value = m.invoke(device);
            if (value instanceof Integer && ((Integer) value) == 1) return 1;
        } catch (ReflectiveOperationException | SecurityException ignored) { }
        return 0;
    }

    // Oreo exposes a 62-byte legacy buffer. Some controllers concatenate AD structures across
    // byte 31; others pad the advertisement to 31 and start scan response there. Parse the whole
    // record first, as Android's ScanRecord does, and only treat byte 31 as a second packet when
    // the first parse ended there or earlier. Splitting unconditionally loses a name spanning 31.
    private static byte[] compact(byte[] raw) {
        if (raw.length != 62) return raw;
        int firstEnd = validEnd(raw, 0, raw.length);
        if (firstEnd > 31) {
            byte[] trimmed = new byte[firstEnd];
            System.arraycopy(raw, 0, trimmed, 0, firstEnd);
            return trimmed;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(62);
        out.write(raw, 0, firstEnd);
        int secondEnd = validEnd(raw, 31, raw.length);
        if (secondEnd > 31) out.write(raw, 31, secondEnd - 31);
        return out.toByteArray();
    }

    private static int validEnd(byte[] raw, int start, int limit) {
        int pos = start;
        while (pos < limit) {
            int n = raw[pos] & 255;
            if (n == 0 || pos + n >= limit) break;
            pos += n + 1;
        }
        return pos;
    }
}
