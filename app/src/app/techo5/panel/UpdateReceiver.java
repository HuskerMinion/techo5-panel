package app.techo5.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * The installer's answer to an update Updater committed: on Androids that want it confirmed, the
 * prompt is brought up (HomeGuard presses it); anything else is logged. A successful update replaces
 * this process, and BootReceiver starts the new one.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        // Installed (this process is about to be replaced), failed or turned down: settled either way.
        Updater.settled();
        Log.i("techo5-panel", "updates: install status " + status + " "
                + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
