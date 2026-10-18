package app.techo5.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the panel when the tablet boots, or when the app is updated, so it comes back on its own
 * after a power cut or an update, as an Echo does.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        // The receiver is exported so Android can deliver these two; anything else another app sends is ignored.
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Log.i("techo5-panel", "starting after " + action);
        ctx.startForegroundService(new Intent(ctx, PanelService.class));
        // The screen too: an app still running from before a reboot is not on the screen, and with
        // TECHO5 as the home app Android brings it up anyway, which this only makes sooner.
        Intent show = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(show);
        } catch (RuntimeException e) {
            // Android 10 and later refuse an activity started from the background unless the app is
            // the home app; the service still runs, and the home app's screen comes up with the launcher.
            Log.i("techo5-panel", "not bringing the screen up from the background: " + e.getMessage());
        }
    }
}
