package app.techo5.panel;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/** Controls Android's ADB daemon on the tested Link View, including USB and TCP. */
final class AdbControl {
    private static final String TAG = "techo5-panel";
    private static final String PREFS = "adb-control";
    private static final String DISABLED = "disabled-by-techo5";
    private final Context context;
    private final SharedPreferences prefs;

    AdbControl(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean available(Context context) {
        if (Build.VERSION.SDK_INT != 27 || !"Google".equals(Build.MANUFACTURER)
                || !"iot_msm8x53_som".equals(Build.MODEL)
                || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class);
            return "5555".equals(get.invoke(null, "persist.adb.tcp.port"));
        } catch (Exception e) {
            Log.w(TAG, "cannot verify ADB TCP mode", e);
            return false;
        }
    }

    boolean enabled() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0;
    }

    synchronized boolean set(boolean enabled) {
        if (!available(context)) return false;
        if (!enabled && !prefs.edit().putBoolean(DISABLED, true).commit()) return false;
        boolean written;
        try {
            written = Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, enabled ? 1 : 0);
        } catch (SecurityException e) {
            Log.w(TAG, "ADB setting write refused", e);
            written = false;
        }
        boolean actual = enabled();
        if ((enabled && written && actual) || (!enabled && (!written || actual))) {
            prefs.edit().putBoolean(DISABLED, false).commit();
        }
        return written && actual == enabled;
    }

    // An app/service restart restores access after TECHO5 disabled ADB. A deliberate Android setting
    // changed outside TECHO5 is left alone, since it did not write our marker.
    synchronized void restoreAfterRestart() {
        if (!prefs.getBoolean(DISABLED, false) || !available(context)) return;
        if (set(true)) Log.i(TAG, "restored ADB after TECHO5 service restart");
    }
}
