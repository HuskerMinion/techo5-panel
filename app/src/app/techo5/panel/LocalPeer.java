package app.techo5.panel;

import android.net.LocalSocket;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

/**
 * Who may use the app's sockets. The four abstract sockets (techo5.mic, techo5.spk, techo5.screen and
 * techo5.ctl) are for echod, which the app starts as its own child process, so it runs as the app's own
 * user id. Any other app can find an abstract socket by name, so each connection is checked: a caller
 * with another user id is logged and closed before anything is read from it or sent to it.
 */
final class LocalPeer {
    private static final String TAG = "techo5-panel";

    private LocalPeer() {
    }

    /** True when the caller on conn runs as this app; otherwise logs it, and the caller closes conn. */
    static boolean isOwn(LocalSocket conn, String socket) {
        int uid;
        try {
            uid = conn.getPeerCredentials().getUid();
        } catch (IOException e) {
            Log.w(TAG, socket + ": could not read who connected, closing: " + e.getMessage());
            return false;
        }
        if (uid == Process.myUid()) return true;
        Log.w(TAG, socket + ": refused a connection from uid " + uid + " (not this app)");
        return false;
    }
}
