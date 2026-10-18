package app.techo5.panel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Two things a tablet that is not built for this will not let the panel do itself, switched on over
 * adb (see res/xml/home_guard.xml for what it may see).
 *
 * The home screen. Fire OS refuses every way of replacing its launcher (the home role, disabling it,
 * removing it for the user), so this watches for the launcher's window and puts the panel back in
 * front of it: the home button, and the end of a boot, flash the stock launcher and land on TECHO5.
 *
 * The microphone. Fire OS takes RECORD_AUDIO back from any app not installed from Amazon's Appstore,
 * at every boot and now and then after Wi-Fi comes back, and nothing stops it. The panel asks again
 * and this answers Allow, for this app's own microphone prompt only: the prompt has to name this app
 * and ask about recording audio, or nothing is pressed.
 */
public class HomeGuard extends AccessibilityService {
    private static final String TAG = "techo5-panel";
    private static final String LAUNCHER = "com.amazon.firelauncher";

    private final Handler later = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(LAUNCHER)) {
            if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            Log.i(TAG, "stock launcher came up, bringing the panel back");
            bringPanelForward();
        } else if (p.equals("com.android.packageinstaller") || p.equals("com.android.permissioncontroller")
                || p.equals("com.google.android.permissioncontroller")) {
            answerMicPrompt(p);
            if (p.equals("com.android.packageinstaller")) answerUpdatePrompt(e, 0);
        } else if (p.equals("com.google.android.packageinstaller")) {
            answerUpdatePrompt(e, 0);
        }
    }

    /**
     * At boot the launcher is often up before this service is, so its window never comes past it; the
     * panel is brought forward once, as soon as the guard is running.
     */
    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "home guard running, bringing the panel forward");
        bringPanelForward();
    }

    private void bringPanelForward() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void answerMicPrompt(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (!asksForOurMic(root, pkg)) return;
            AccessibilityNodeInfo allow = find(root, pkg + ":id/permission_allow_foreground_only_button");
            if (allow == null) allow = find(root, pkg + ":id/permission_allow_button");
            if (allow == null || !allow.isEnabled()) return;
            Log.i(TAG, "answering the microphone prompt for this app: allow");
            allow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            root.recycle();
        }
    }

    /**
     * The confirmation Android shows for an update installed by something other than a store: pressed
     * only when it is an update to this app, the one Updater asked for. Two layouts: AOSP's screen
     * (Fire OS: app_name, ok_button) and the dialog newer Android shows (alertTitle, button1). Android
     * keeps the button disabled for a moment after the prompt appears, so tapping too soon is not
     * possible; it is tried again for a few seconds.
     *
     * Android 12 and later install the update without a prompt (see Updater), which matters: Android 14
     * hides the installer's buttons from accessibility services that are not assistive tools.
     */
    private void answerUpdatePrompt(AccessibilityEvent e, final int tries) {
        // The window the event came from, climbed to its top: on some tablets (the Galaxy Tab S6 Lite)
        // the "active window" is not the installer's dialog while it is up.
        AccessibilityNodeInfo root = e != null ? top(e.getSource()) : null;
        if (root == null) root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String label = getApplicationInfo().loadLabel(getPackageManager()).toString();
            AccessibilityNodeInfo ok = null;
            AccessibilityNodeInfo name = find(root, "com.android.packageinstaller:id/app_name");
            if (name != null && name.getText() != null && name.getText().toString().equals(label)) {
                ok = find(root, "com.android.packageinstaller:id/ok_button");
            } else {
                AccessibilityNodeInfo title = find(root, "android:id/alertTitle");
                boolean update = find(root, "com.android.packageinstaller:id/install_confirm_question_update") != null;
                if (update && title != null && title.getText() != null && title.getText().toString().equals(label)) {
                    ok = find(root, "android:id/button1");
                }
            }
            if (ok == null) {
                if (tries == 0 && find(root, "android:id/button1") != null) {
                    Log.i(TAG, "an install prompt that is not this app's update, left alone");
                }
                return;
            }
            if (!ok.isEnabled()) {
                if (tries < 10) {
                    later.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            answerUpdatePrompt(null, tries + 1);
                        }
                    }, 500);
                }
                return;
            }
            Log.i(TAG, "answering the update prompt for this app: install");
            ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            root.recycle();
        }
    }

    /** Whether the prompt on the screen is this app's, about the microphone. */
    private boolean asksForOurMic(AccessibilityNodeInfo root, String pkg) {
        AccessibilityNodeInfo msg = find(root, pkg + ":id/permission_message");
        if (msg == null || msg.getText() == null) return false;
        String text = msg.getText().toString();
        String label = getApplicationInfo().loadLabel(getPackageManager()).toString();
        return text.contains(label) && text.toLowerCase().contains("record audio");
    }

    private static AccessibilityNodeInfo top(AccessibilityNodeInfo n) {
        if (n == null) return null;
        for (AccessibilityNodeInfo up = n.getParent(); up != null; up = up.getParent()) n = up;
        return n;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    @Override
    public void onInterrupt() {
    }
}
