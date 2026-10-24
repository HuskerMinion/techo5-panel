package app.techo5.panel;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Feeds Android's front camera to the same-UID TECHO5 daemon only while it has a viewer. */
final class CameraLink {
    private static final String TAG = "techo5-panel";
    private static final int MAX_JPEG = 2 * 1024 * 1024;
    private final Context context;
    private final LocalServerSocket server;
    private final LocalServerSocket stillServer;
    private final File ready;
    private volatile boolean stopping;
    private volatile LocalSocket active;
    private volatile int previewWidth = 640;
    private volatile int previewHeight = 480;
    private Capture capture;
    private int users;

    CameraLink(Context context) throws IOException {
        this.context = context;
        ready = new File(context.getFilesDir(), "camera-ready");
        refreshReady();
        server = new LocalServerSocket("techo5.cam");
        stillServer = new LocalServerSocket("techo5.still");
    }

    synchronized boolean refreshReady() throws IOException {
        boolean was = ready.exists();
        boolean now = available();
        if (now) {
            int[] size = advertisedPreview();
            previewWidth = size[0];
            previewHeight = size[1];
            byte[] marker = (size[0] + " " + size[1] + "\n").getBytes(StandardCharsets.US_ASCII);
            if (!was || !Arrays.equals(Files.readAllBytes(ready.toPath()), marker)) {
                File temporary = new File(context.getFilesDir(), "camera-ready.new");
                try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(marker); }
                if (!temporary.renameTo(ready)) throw new IOException("could not update camera mode marker");
                return true;
            }
        } else if (!now && was && !ready.delete()) {
            Log.w(TAG, "could not clear the camera availability marker");
        }
        return was != now;
    }

    private int[] advertisedPreview() {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics caps = manager.getCameraCharacteristics(id);
                Integer facing = caps.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                StreamConfigurationMap map = caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
                if (sizes == null) break;
                for (Size size : sizes)
                    if (size.getWidth() == 1280 && size.getHeight() == 720) return new int[] {1280, 720};
                for (Size size : sizes)
                    if (size.getWidth() == 640 && size.getHeight() == 480) return new int[] {640, 480};
            }
        } catch (Exception e) {
            Log.w(TAG, "camera preview capability query failed", e);
        }
        return new int[] {640, 480};
    }

    private boolean available() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return false;
        try { return Camera.getNumberOfCameras() > 0; }
        catch (RuntimeException e) { Log.w(TAG, "camera enumeration failed", e); return false; }
    }

    void serve() {
        new Thread(new Runnable() {
            @Override public void run() { serveStills(); }
        }, "camera-stills").start();
        while (!stopping) {
            try (LocalSocket conn = server.accept()) {
                if (!LocalPeer.isOwn(conn, "techo5.cam")) continue;
                active = conn;
                serveOne(conn);
            } catch (IOException e) {
                if (!stopping) Log.w(TAG, "camera connection ended", e);
            } finally {
                active = null;
            }
        }
    }

    private void serveOne(final LocalSocket conn) throws IOException {
        OutputStream out = conn.getOutputStream();
        if (!available()) { out.write('E'); return; }
        final Capture capture = acquire();
        if (capture == null) { out.write('E'); return; }
        try {
            out.write('O');
            Log.i(TAG, "front camera started for a viewer");
            new Thread(new Runnable() {
                @Override public void run() {
                    try { conn.getInputStream().read(); }
                    catch (IOException ignored) { }
                    if (active == conn) active = null;
                    try { conn.close(); } catch (IOException ignored) { }
                }
            }, "camera-peer-watch").start();
            while (!stopping && active == conn) {
                byte[] jpg;
                try { jpg = capture.frames.poll(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (jpg == null) continue;
                if (jpg.length == 0 || jpg.length > MAX_JPEG) continue;
                int n = jpg.length;
                out.write(new byte[] {(byte)(n >>> 24), (byte)(n >>> 16), (byte)(n >>> 8), (byte)n});
                out.write(jpg);
            }
        } finally {
            release(capture);
            Log.i(TAG, "front camera stopped");
        }
    }

    private synchronized Capture acquire() {
        if (stopping) return null;
        if (capture == null) {
            Capture opened = new Capture(previewWidth, previewHeight);
            if (!opened.start()) { opened.stop(); return null; }
            capture = opened;
        }
        users++;
        return capture;
    }

    private synchronized void release(Capture held) {
        if (capture != held || users <= 0) return;
        if (--users == 0) {
            held.stop();
            capture = null;
        }
    }

    private void serveStills() {
        while (!stopping) {
            try (LocalSocket conn = stillServer.accept()) {
                if (!LocalPeer.isOwn(conn, "techo5.still")) continue;
                OutputStream out = conn.getOutputStream();
                Capture held = acquire();
                if (held == null) { out.write('E'); continue; }
                try {
                    byte[] jpg = held.takeStill();
                    if (jpg == null || jpg.length == 0 || jpg.length > 8 * 1024 * 1024) {
                        out.write('E');
                        continue;
                    }
                    out.write('O');
                    int n = jpg.length;
                    out.write(new byte[] {(byte)(n >>> 24), (byte)(n >>> 16), (byte)(n >>> 8), (byte)n});
                    out.write(jpg);
                } finally { release(held); }
            } catch (IOException e) {
                if (!stopping) Log.w(TAG, "camera still request ended", e);
            }
        }
    }

    void close() {
        stopping = true;
        if (ready.exists() && !ready.delete()) Log.w(TAG, "could not clear camera marker");
        try { server.close(); } catch (IOException ignored) { }
        try { stillServer.close(); } catch (IOException ignored) { }
        LocalSocket conn = active;
        if (conn != null) try { conn.close(); } catch (IOException ignored) { }
    }

    private static final class Capture {
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(2);
        final ArrayBlockingQueue<byte[]> rawFrames = new ArrayBlockingQueue<>(1);
        final HandlerThread thread = new HandlerThread("camera-capture");
        Handler handler;
        Camera camera;
        SurfaceTexture surface;
        Thread encoder;
        volatile boolean opened;
        volatile boolean closed;
        int width, height;
        final int requestedWidth, requestedHeight;

        Capture(int width, int height) {
            requestedWidth = width;
            requestedHeight = height;
        }

        boolean start() {
            thread.start();
            handler = new Handler(thread.getLooper());
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() {
                    try {
                        int id = frontCamera();
                        if (id < 0) throw new IllegalStateException("no front camera");
                        camera = Camera.open(id);
                        Camera.Parameters p = camera.getParameters();
                        Camera.Size size = previewSize(p.getSupportedPreviewSizes(), requestedWidth, requestedHeight);
                        int[] fps = bestFps(p.getSupportedPreviewFpsRange());
                        p.setPreviewSize(size.width, size.height);
                        p.setPreviewFormat(ImageFormat.NV21);
                        Camera.Size picture = largestPicture(p.getSupportedPictureSizes());
                        p.setPictureSize(picture.width, picture.height);
                        if (fps != null) p.setPreviewFpsRange(fps[0], fps[1]);
                        try {
                            camera.setParameters(p);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "preview with highest fps rejected; trying default fps", e);
                            p = camera.getParameters();
                            p.setPreviewSize(size.width, size.height);
                            p.setPreviewFormat(ImageFormat.NV21);
                            p.setPictureSize(picture.width, picture.height);
                            camera.setParameters(p);
                        }
                        width = size.width; height = size.height;
                        int[] chosenFps = new int[2];
                        camera.getParameters().getPreviewFpsRange(chosenFps);
                        Log.i(TAG, "front camera preview selected " + width + "x" + height
                                + " target fps " + chosenFps[0] / 1000 + "-" + chosenFps[1] / 1000);
                        Log.i(TAG, "front camera still selected " + picture.width + "x" + picture.height);
                        surface = new SurfaceTexture(0);
                        camera.setPreviewTexture(surface);
                        final int bytes = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
                        camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                            @Override public void onPreviewFrame(byte[] data, Camera device) {
                                if (closed || data == null) return;
                                if (!rawFrames.offer(data)) {
                                    byte[] older = rawFrames.poll();
                                    if (older != null) device.addCallbackBuffer(older);
                                    if (!rawFrames.offer(data)) device.addCallbackBuffer(data);
                                }
                            }
                        });
                        camera.addCallbackBuffer(new byte[bytes]);
                        camera.addCallbackBuffer(new byte[bytes]);
                        camera.addCallbackBuffer(new byte[bytes]);
                        camera.startPreview();
                        startEncoder();
                        opened = true;
                    } catch (Exception e) {
                        Log.w(TAG, "front camera could not start", e);
                        release();
                    } finally { done.countDown(); }
                }
            });
            try { return done.await(6, TimeUnit.SECONDS) && opened; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }

        private static int frontCamera() {
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return id;
            }
            return -1;
        }

        private static Camera.Size previewSize(List<Camera.Size> sizes, int width, int height) {
            if (sizes == null || sizes.isEmpty()) throw new IllegalStateException("no preview sizes");
            for (Camera.Size size : sizes) {
                if (size.width == width && size.height == height) return size;
            }
            throw new IllegalStateException("advertised preview mode unavailable to Camera1");
        }

        private static Camera.Size largestPicture(List<Camera.Size> sizes) {
            if (sizes == null || sizes.isEmpty()) throw new IllegalStateException("no picture sizes");
            Camera.Size best = sizes.get(0);
            for (Camera.Size size : sizes) {
                if ((long) size.width * size.height > (long) best.width * best.height) best = size;
            }
            return best;
        }

        byte[] takeStill() {
            if (closed || handler == null) return null;
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<byte[]> photo = new AtomicReference<>();
            handler.post(new Runnable() {
                @Override public void run() {
                    if (closed || camera == null) { done.countDown(); return; }
                    try {
                        camera.takePicture(null, null, new Camera.PictureCallback() {
                            @Override public void onPictureTaken(byte[] data, final Camera device) {
                                photo.set(data);
                                // Camera1 requires the JPEG callback to return before preview restarts.
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        try { if (!closed) device.startPreview(); }
                                        catch (RuntimeException e) { Log.w(TAG, "preview restart after still", e); }
                                        done.countDown();
                                    }
                                });
                            }
                        });
                    } catch (RuntimeException e) {
                        Log.w(TAG, "full-resolution still failed", e);
                        done.countDown();
                    }
                }
            });
            try {
                if (!done.await(10, TimeUnit.SECONDS)) return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return photo.get();
        }

        private static int[] bestFps(List<int[]> ranges) {
            if (ranges == null || ranges.isEmpty()) return null;
            int[] best = ranges.get(0);
            for (int[] range : ranges) {
                if (range[1] > best[1] || (range[1] == best[1] && range[0] > best[0])) best = range;
            }
            Log.i(TAG, "front camera fps ranges=" + ranges.size() + " highest="
                    + best[0] / 1000 + "-" + best[1] / 1000);
            return best;
        }

        private void startEncoder() {
            encoder = new Thread(new Runnable() {
                @Override public void run() {
                    while (!closed) {
                        byte[] raw;
                        try { raw = rawFrames.poll(500, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException e) { break; }
                        if (raw == null) continue;
                        try {
                            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
                            YuvImage image = new YuvImage(raw, ImageFormat.NV21, width, height, null);
                            if (image.compressToJpeg(new Rect(0, 0, width, height), 75, out)) {
                                byte[] jpg = out.toByteArray();
                                if (!frames.offer(jpg)) { frames.poll(); frames.offer(jpg); }
                            }
                        } catch (RuntimeException e) {
                            Log.w(TAG, "front camera JPEG encoding failed", e);
                        } finally {
                            final byte[] reuse = raw;
                            handler.post(new Runnable() {
                                @Override public void run() {
                                    if (!closed && camera != null) camera.addCallbackBuffer(reuse);
                                }
                            });
                        }
                    }
                }
            }, "camera-jpeg");
            encoder.start();
        }

        private void release() {
            closed = true;
            if (camera != null) {
                try { camera.setPreviewCallbackWithBuffer(null); camera.stopPreview(); }
                catch (RuntimeException ignored) { }
                camera.release();
                camera = null;
            }
            if (surface != null) { surface.release(); surface = null; }
        }

        void stop() {
            closed = true;
            if (encoder != null) {
                encoder.interrupt();
                try { encoder.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (handler != null) handler.post(new Runnable() {
                @Override public void run() { release(); thread.quitSafely(); }
            });
            else thread.quitSafely();
            try { thread.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
