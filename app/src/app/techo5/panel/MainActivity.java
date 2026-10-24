package app.techo5.panel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Asks for the microphone once and starts the service, then shows echod's screen: the canvas the
 * service keeps, scaled to fill the display, with touches sent back.
 */
public class MainActivity extends Activity implements ScreenLink.Viewer {
    private static final int ASK_MIC = 1;
    private static final int ASK_CAMERA = 2;
    // HomeGuard answers a microphone prompt only this soon after this screen asked for one.
    private static final long ASK_WINDOW_MS = 30_000;
    private static volatile long askedForMicAt;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Handler touches;
    private PanelView view;
    private TextView text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        HandlerThread t = new HandlerThread("touch");
        t.start();
        touches = new Handler(t.getLooper());

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            text = new TextView(this);
            text.setPadding(48, 48, 48, 48);
            text.setTextSize(20);
            text.setText("TECHO5 Panel needs the microphone.");
            setContentView(text);
            askForMic();
            return;
        }
        startWithCameraPermission();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == ASK_CAMERA) {
            // Declining camera access leaves voice available.
            start();
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (view != null) return; // asked again while running: the service picks the microphone back up
            startWithCameraPermission();
        } else if (text != null) {
            text.setText("Without the microphone there is no wake word. Allow it in Settings > Apps.");
        }
    }

    private void askForMic() {
        askedForMicAt = android.os.SystemClock.elapsedRealtime();
        requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
    }

    private void startWithCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            text = new TextView(this);
            text.setPadding(48, 48, 48, 48);
            text.setTextSize(20);
            text.setText("Allow camera access to share this tablet's camera with Home Assistant and RTSP.");
            setContentView(text);
            requestPermissions(new String[] {Manifest.permission.CAMERA}, ASK_CAMERA);
            return;
        }
        start();
    }
    /** Whether this screen asked for the microphone in the last half minute, so a prompt now is its own. */
    static boolean justAskedForMic() {
        long at = askedForMicAt;
        return at != 0 && android.os.SystemClock.elapsedRealtime() - at < ASK_WINDOW_MS;
    }

    private void start() {
        startForegroundService(new Intent(this, PanelService.class));
        view = new PanelView(this);
        setContentView(view);
        fullScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The microphone can be taken back while the app runs (Fire OS does, see HomeGuard): ask again.
        if (view != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askForMic();
        }
        fullScreen();
        if (view != null) {
            ScreenLink.setViewer(this);
            // Started again from the front, so the service may keep the microphone (see PanelService).
            startForegroundService(new Intent(this, PanelService.class));
        }
    }

    @Override
    protected void onPause() {
        ScreenLink.setViewer(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        touches.getLooper().quitSafely();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void fullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void frameChanged() {
        PanelView v = view;
        if (v != null) v.postInvalidate();
    }

    @Override
    public void backlight(final int level) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (level == 0) {
                    // An app cannot switch the panel off; black and dimmed is its off, and letting go of
                    // keep-screen-on lets Android sleep it.
                    lp.screenBrightness = 0f;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    lp.screenBrightness = Math.max(level, 1) / 255f;
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                getWindow().setAttributes(lp);
                if (view != null) {
                    view.dark = level == 0;
                    view.invalidate();
                }
            }
        });
    }

    /** Draws the canvas scaled to fill the view and maps touches back to canvas pixels. */
    private final class PanelView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();
        boolean dark;

        PanelView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (dark) {
                c.drawColor(Color.BLACK);
                return;
            }
            dst.set(0, 0, getWidth(), getHeight());
            synchronized (ScreenLink.lock()) {
                Bitmap b = ScreenLink.canvas();
                if (b != null) c.drawBitmap(b, null, dst, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            Bitmap b = ScreenLink.canvas();
            if (b == null || getWidth() == 0 || getHeight() == 0) return true;
            final float sx = (float) b.getWidth() / getWidth(), sy = (float) b.getHeight() / getHeight();
            int action = e.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    send(0, e, e.getActionIndex(), sx, sy);
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) send(1, e, i, sx, sy);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    send(2, e, e.getActionIndex(), sx, sy);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    for (int i = 0; i < e.getPointerCount(); i++) send(2, e, i, sx, sy);
                    break;
                default:
                    break;
            }
            return true;
        }

        private void send(final int action, MotionEvent e, int index, float sx, float sy) {
            final int id = Math.min(e.getPointerId(index), 9);
            final int x = Math.round(e.getX(index) * sx), y = Math.round(e.getY(index) * sy);
            touches.post(new Runnable() {
                @Override
                public void run() {
                    ScreenLink.touch(action, id, x, y);
                }
            });
        }
    }
}
