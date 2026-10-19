package app.techo5.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.Display;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * echod's screen: the canvas it draws, kept here in the service so frames land while no activity is
 * up, and the way back for touches. One abstract socket, "techo5.screen"; see docs/screen-protocol.md.
 */
final class ScreenLink {
    private static final String TAG = "techo5-panel";

    /** What draws the canvas: the activity, while it is up. */
    interface Viewer {
        void frameChanged();

        void backlight(int level);
    }

    private static final Object lock = new Object();
    private static Bitmap canvas;
    private static Viewer viewer;
    private static OutputStream out;
    private static int backlight = 255;

    private ScreenLink() {
    }

    static void setViewer(Viewer v) {
        int level;
        synchronized (lock) {
            viewer = v;
            level = backlight;
        }
        if (v != null) {
            v.backlight(level);
            v.frameChanged();
        }
    }

    /** The canvas, or null before echod has drawn anything. Callers draw it while holding lock(). */
    static Bitmap canvas() {
        return canvas;
    }

    static Object lock() {
        return lock;
    }

    /** One touch record. Called off the UI thread. */
    static void touch(int action, int pointer, int x, int y) {
        OutputStream o;
        Bitmap c;
        synchronized (lock) {
            o = out;
            c = canvas;
        }
        if (o == null || c == null) return;
        x = Math.max(0, Math.min(x, c.getWidth() - 1));
        y = Math.max(0, Math.min(y, c.getHeight() - 1));
        byte[] b = {'T', (byte) action, (byte) pointer, (byte) x, (byte) (x >> 8), (byte) y, (byte) (y >> 8)};
        try {
            synchronized (o) {
                o.write(b);
            }
        } catch (IOException e) {
            Log.w(TAG, "touch: " + e.getMessage());
        }
    }

    /**
     * Serves echod one connection at a time, for as long as the service runs: once it stops, its socket
     * is closed and accept fails, and the loop ends rather than spinning on it.
     */
    static void serve(PanelService svc, LocalServerSocket server) {
        while (!svc.stopping()) {
            LocalSocket conn;
            try {
                conn = server.accept();
            } catch (IOException e) {
                if (svc.stopping()) return;
                Log.w(TAG, "screen socket: " + e.getMessage());
                pause();
                continue;
            }
            try (LocalSocket c = conn) {
                if (!LocalPeer.isOwn(c, "techo5.screen")) continue;
                Log.i(TAG, "echod connected to the screen");
                run(svc, c);
            } catch (IOException e) {
                Log.w(TAG, "screen connection ended: " + e.getMessage());
            } finally {
                synchronized (lock) {
                    out = null;
                }
            }
        }
    }

    /** A second between tries at a socket that failed for a reason of its own, so it is not a busy loop. */
    static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void run(Context ctx, LocalSocket conn) throws IOException {
        int[] size = canvasSize(ctx);
        int w = size[0], h = size[1];
        int dpi = ctx.getResources().getDisplayMetrics().densityDpi;

        OutputStream o = conn.getOutputStream();
        byte[] hello = {'T', '5', 'P', '1', (byte) w, (byte) (w >> 8), (byte) h, (byte) (h >> 8),
                (byte) dpi, (byte) (dpi >> 8), 0, 0};
        o.write(hello);
        Log.i(TAG, "screen canvas " + w + "x" + h + " at " + dpi + " dpi");

        synchronized (lock) {
            if (canvas == null || canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            out = o;
        }

        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(conn.getInputStream(), 1 << 16));
        byte[] head = new byte[8];
        byte[] pixels = new byte[0];
        Bitmap scratch = null;
        while (true) {
            int kind = in.read();
            if (kind < 0) return;
            if (kind == 'F') {
                in.readFully(head, 0, 8);
                int x = u16(head, 0), y = u16(head, 2), fw = u16(head, 4), fh = u16(head, 6);
                int n = fw * fh * 4;
                if (pixels.length < n) pixels = new byte[n];
                in.readFully(pixels, 0, n);
                if (fw == 0 || fh == 0) continue;
                if (scratch == null || scratch.getWidth() != fw || scratch.getHeight() != fh) {
                    if (scratch != null) scratch.recycle();
                    scratch = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                }
                // ARGB_8888 is R, G, B, A in memory, which is what echod sends.
                scratch.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, n));
                Viewer v;
                synchronized (lock) {
                    new Canvas(canvas).drawBitmap(scratch, x, y, null);
                    v = viewer;
                }
                if (v != null) v.frameChanged();
            } else if (kind == 'B') {
                in.readFully(head, 0, 2);
                int level = u16(head, 0);
                Viewer v;
                synchronized (lock) {
                    backlight = level;
                    v = viewer;
                }
                if (v != null) v.backlight(level);
            } else {
                throw new IOException("unknown record " + kind);
            }
        }
    }

    /** The display in landscape, scaled down to at most 1280 wide: the Show's layouts are drawn for that. */
    private static int[] canvasSize(Context ctx) {
        Display d = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);
        int w = Math.max(p.x, p.y), h = Math.min(p.x, p.y);
        if (w > 1280) {
            h = Math.round(h * 1280f / w);
            w = 1280;
        }
        return new int[] {w, h};
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }
}
