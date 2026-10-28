package app.techo5.panel;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One GATT client slot for the ESPHome proxy, over a same-UID local socket. */
final class GattLink {
    private static final String TAG = "techo5-panel";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final Map<String, BluetoothDevice> seen = new HashMap<>();

    // Android 8.1 stores the real ATT handle as each object's instance ID. The descriptor
    // accessor is hidden from the SDK jar but present on the Link View's framework.
    private static int descriptorHandle(BluetoothGattDescriptor descriptor) {
        try {
            Method method = BluetoothGattDescriptor.class.getDeclaredMethod("getInstanceId");
            method.setAccessible(true);
            return (Integer) method.invoke(descriptor);
        } catch (Exception e) {
            Log.w(TAG, "descriptor ATT handle unavailable", e);
            return 0;
        }
    }

    static synchronized void remember(BluetoothDevice device) {
        if (seen.size() > 256) seen.clear();
        seen.put(device.getAddress().replace(":", "").toUpperCase(), device);
    }

    private final Context context;
    private final BleLink scanner;
    private final LocalServerSocket server;
    private volatile LocalSocket active;
    private volatile boolean closed;

    GattLink(Context context, BleLink scanner) throws IOException {
        this.context = context;
        this.scanner = scanner;
        this.server = new LocalServerSocket("techo5.gatt");
    }

    void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        LocalSocket c = active;
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }

    void serve() {
        while (!closed) {
            LocalSocket c;
            try { c = server.accept(); }
            catch (IOException e) {
                if (!closed) Log.w(TAG, "gatt accept", e);
                return;
            }
            try (LocalSocket conn = c) {
                if (!LocalPeer.isOwn(conn, "techo5.gatt")) continue;
                active = conn;
                new Session(conn).run();
            } catch (IOException e) {
                if (!closed) Log.w(TAG, "gatt connection: " + e.getMessage());
            } finally {
                active = null;
            }
        }
    }

    private final class Session {
        final LocalSocket conn;
        final OutputStream out;
        final Object outputLock = new Object();
        final Map<Integer, Object> handles = new ConcurrentHashMap<>();
        volatile BluetoothGatt gatt;
        volatile String address = "";
        volatile int mtu = 23;
        volatile int pendingHandle;
        volatile String pending = "";
        volatile int notifyHandle;
        volatile boolean connected;
        volatile boolean stopping;
        volatile boolean scanPaused;
        volatile boolean disconnectRequested;
        volatile boolean responded;
        volatile int connectReplies;
        volatile BluetoothDevice target;

        Session(LocalSocket conn) throws IOException {
            this.conn = conn;
            this.out = conn.getOutputStream();
        }

        void run() throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                for (String line; !stopping && (line = in.readLine()) != null; ) {
                    try { command(new JSONObject(line)); }
                    catch (JSONException e) { Log.w(TAG, "gatt malformed command", e); }
                    catch (RuntimeException e) {
                        Log.w(TAG, "gatt operation unavailable", e);
                        if (!connected) finishFailure(0x85);
                        else { pending = ""; error(pendingHandle, 0x85); }
                    }
                }
            } finally {
                stopping = true;
                resumeScan();
                BluetoothGatt g = gatt;
                if (g != null) { g.disconnect(); g.close(); }
            }
        }

        void send(JSONObject event) {
            if (stopping) return;
            try {
                byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                synchronized (outputLock) { out.write(line); }
            } catch (IOException e) {
                stopping = true;
                try { conn.close(); } catch (IOException ignored) { }
            }
        }

        JSONObject event(String type, int handle) {
            JSONObject e = new JSONObject();
            try {
                e.put("type", type);
                e.put("addr", address);
                e.put("handle", handle);
            } catch (JSONException ignored) { }
            return e;
        }

        void error(int handle, int status) {
            JSONObject e = event("error", handle);
            try { e.put("error", status); } catch (JSONException ignored) { }
            send(e);
        }

        void command(JSONObject cmd) throws JSONException {
            String op = cmd.optString("op");
            if ("known".equals(op)) {
                String raw = cmd.optString("addr").toUpperCase();
                JSONObject reply = new JSONObject();
                reply.put("type", "known");
                reply.put("addr", raw);
                synchronized (GattLink.class) { reply.put("known", seen.containsKey(raw)); }
                send(reply);
                return;
            }
            if ("connect".equals(op)) { connect(cmd.optString("addr")); return; }
            if ("disconnect".equals(op)) { disconnect(); return; }
            if ("services".equals(op)) { services(); return; }
            int handle = cmd.optInt("handle", 0);
            Object target = handles.get(handle);
            if (gatt == null || target == null) { error(handle, 1); return; }
            if (!pending.isEmpty()) { error(handle, 0x84); return; }
            pending = op;
            pendingHandle = handle;
            boolean begun = false;
            try {
                switch (op) {
                    case "read":
                        if (target instanceof BluetoothGattCharacteristic)
                            begun = gatt.readCharacteristic((BluetoothGattCharacteristic) target);
                        break;
                    case "read_descriptor":
                        if (target instanceof BluetoothGattDescriptor)
                            begun = gatt.readDescriptor((BluetoothGattDescriptor) target);
                        break;
                    case "write":
                        byte[] data = Base64.decode(cmd.optString("data"), Base64.DEFAULT);
                        if (target instanceof BluetoothGattCharacteristic) {
                            BluetoothGattCharacteristic ch = (BluetoothGattCharacteristic) target;
                            boolean response = cmd.optBoolean("response", true);
                            ch.setWriteType(response
                                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                            ch.setValue(data);
                            begun = gatt.writeCharacteristic(ch);
                        } else if (target instanceof BluetoothGattDescriptor) {
                            BluetoothGattDescriptor desc = (BluetoothGattDescriptor) target;
                            desc.setValue(data);
                            begun = gatt.writeDescriptor(desc);
                        }
                        break;
                    case "notify":
                        if (target instanceof BluetoothGattCharacteristic) {
                            BluetoothGattCharacteristic ch = (BluetoothGattCharacteristic) target;
                            boolean enable = cmd.optBoolean("enable");
                            begun = gatt.setCharacteristicNotification(ch, enable);
                            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
                            if (begun && cccd != null) {
                                notifyHandle = handle;
                                byte[] enabled = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                                cccd.setValue(enable ? enabled : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                                begun = gatt.writeDescriptor(cccd);
                            } else if (begun) {
                                pending = "";
                                send(event("notify", handle));
                            }
                        }
                        break;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt " + op + " rejected", e);
            }
            if (!begun) { pending = ""; error(handle, 2); }
        }

        synchronized void connect(String raw) {
            if (!raw.matches("[A-Fa-f0-9]{12}")) { error(0, 0x85); return; }
            if (address.equalsIgnoreCase(raw) && target != null && !disconnectRequested) {
                if (connected) connected(true, 0);
                else connectReplies++;
                return;
            }
            if (!address.isEmpty() || raw.length() != 12) {
                error(0, 0x84);
                return;
            }
            address = raw.toUpperCase();
            connectReplies = 1;
            responded = false;
            disconnectRequested = false;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) { finishFailure(2); return; }
            BluetoothDevice device;
            synchronized (GattLink.class) { device = seen.get(raw.toUpperCase()); }
            try {
                if (device == null) {
                    String formatted = raw.substring(0, 2) + ":" + raw.substring(2, 4) + ":" +
                            raw.substring(4, 6) + ":" + raw.substring(6, 8) + ":" +
                            raw.substring(8, 10) + ":" + raw.substring(10, 12);
                    device = adapter.getRemoteDevice(formatted);
                }
                target = device;
                if (scanner != null) {
                    scanner.pauseForConnection();
                    scanPaused = true;
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                startAttempt();
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt connect failed", e);
                finishFailure(1);
            }
        }

        // The daemon owns retries. Each socket makes only one Android attempt.
        synchronized void startAttempt() {
            if (stopping || disconnectRequested || target == null) return;
            try {
                gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) throw new IllegalStateException("connectGatt returned null");
                // The shared daemon manager owns the connection deadline. Closing its socket
                // cancels BluetoothGatt in Session.finally, including a controller that never replies.
                Log.i(TAG, "gatt connection started");
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt connection failed to start", e);
                finishFailure(1);
            }
        }

        synchronized void failAttempt(BluetoothGatt failed, int status) {
            if (gatt != failed) return;
            gatt = null;
            failed.disconnect();
            failed.close();
            handles.clear();
            finishFailure(status);
        }

        synchronized void finishFailure(int status) {
            if (!responded) {
                for (int i = 0; i < Math.max(1, connectReplies); i++) connected(false, status);
                responded = true;
            }
            connectReplies = 0;
            address = "";
            target = null;
            resumeScan();
        }

        synchronized void resumeScan() {
            if (scanPaused) {
                scanPaused = false;
                if (scanner != null) scanner.resumeAfterConnection();
            }
        }

        void disconnect() {
            disconnectRequested = true;
            BluetoothGatt g = gatt;
            if (g == null) { finishFailure(0); return; }
            g.disconnect();
        }

        void connected(boolean on, int status) {
            JSONObject e = event("connection", 0);
            try {
                e.put("connected", on);
                e.put("mtu", mtu);
                e.put("error", status);
            } catch (JSONException ignored) { }
            send(e);
        }

        void services() {
            BluetoothGatt g = gatt;
            if (g == null) { error(0, 1); return; }
            JSONArray list = new JSONArray();
            for (BluetoothGattService svc : g.getServices()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("uuid", svc.getUuid().toString());
                    item.put("handle", find(svc));
                    JSONArray chars = new JSONArray();
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        JSONObject c = new JSONObject();
                        c.put("uuid", ch.getUuid().toString());
                        c.put("handle", find(ch));
                        c.put("properties", ch.getProperties());
                        JSONArray descs = new JSONArray();
                        for (BluetoothGattDescriptor desc : ch.getDescriptors()) {
                            JSONObject d = new JSONObject();
                            d.put("uuid", desc.getUuid().toString());
                            d.put("handle", find(desc));
                            descs.put(d);
                        }
                        c.put("descriptors", descs);
                        chars.put(c);
                    }
                    item.put("characteristics", chars);
                    list.put(item);
                } catch (JSONException ignored) { }
            }
            JSONObject e = event("services", 0);
            try { e.put("services", list); } catch (JSONException ignored) { }
            send(e);
        }

        int find(Object object) {
            for (Map.Entry<Integer, Object> e : handles.entrySet())
                if (e.getValue() == object) return e.getKey();
            return 0;
        }

        boolean mapServices() {
            handles.clear();
            for (BluetoothGattService svc : gatt.getServices()) {
                if (svc.getInstanceId() <= 0 || handles.put(svc.getInstanceId(), svc) != null)
                    return false;
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    if (ch.getInstanceId() <= 0 || handles.put(ch.getInstanceId(), ch) != null)
                        return false;
                    for (BluetoothGattDescriptor desc : ch.getDescriptors()) {
                        int handle = descriptorHandle(desc);
                        if (handle <= 0 || handles.put(handle, desc) != null) return false;
                    }
                }
            }
            return true;
        }

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                if (gatt != g) return;
                if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.discoverServices()) failAttempt(g, 2);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!connected && !disconnectRequested && !stopping) {
                        failAttempt(g, status);
                        return;
                    }
                    connected = false;
                    handles.clear();
                    g.close();
                    gatt = null;
                    finishFailure(status);
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (gatt != g) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (connected) return;
                    if (!mapServices()) {
                        Log.w(TAG, "gatt real ATT handles unavailable; refusing proxy connection");
                        disconnectRequested = true;
                        connected(false, 2);
                        responded = true;
                        g.disconnect();
                        return;
                    }
                    connected = true;
                    synchronized (Session.this) {
                        for (int i = 0; i < Math.max(1, connectReplies); i++) connected(true, 0);
                        connectReplies = 0;
                    }
                    resumeScan();
                } else { failAttempt(g, status); }
            }
            @Override public void onMtuChanged(BluetoothGatt g, int size, int status) {
                if (gatt == g && status == BluetoothGatt.GATT_SUCCESS) mtu = size;
            }
            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                if (gatt != g) return;
                readResult("read", ch.getValue(), status);
            }
            @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                if (gatt != g) return;
                readResult("read_descriptor", d.getValue(), status);
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                if (gatt != g) return;
                writeResult(status);
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                if (gatt != g) return;
                if ("notify".equals(pending)) {
                    pending = "";
                    if (status == BluetoothGatt.GATT_SUCCESS) send(event("notify", notifyHandle));
                    else error(notifyHandle, status);
                } else writeResult(status);
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                if (gatt != g) return;
                int handle = find(ch);
                if (handle == 0) return;
                JSONObject e = event("notify_data", handle);
                try { e.put("data", Base64.encodeToString(ch.getValue() == null ? new byte[0] : ch.getValue(), Base64.NO_WRAP)); }
                catch (JSONException ignored) { }
                send(e);
            }
        };

        void readResult(String op, byte[] data, int status) {
            if (!op.equals(pending)) return;
            pending = "";
            if (status != BluetoothGatt.GATT_SUCCESS) error(pendingHandle, status);
            else {
                JSONObject e = event("read", pendingHandle);
                try { e.put("data", Base64.encodeToString(data == null ? new byte[0] : data, Base64.NO_WRAP)); }
                catch (JSONException ignored) { }
                send(e);
            }
        }

        void writeResult(int status) {
            if (!"write".equals(pending)) return;
            pending = "";
            if (status != BluetoothGatt.GATT_SUCCESS) error(pendingHandle, status);
            else send(event("write", pendingHandle));
        }
    }
}
