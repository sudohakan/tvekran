package dev.hakan.tvekran;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Serves the current screen as JPEG over HTTP, using the accessibility screenshot API (no consent dialog). */
public class ScreenService extends AccessibilityService {
    private static final String TAG = "TvEkran";
    private static final int PORT = 8085;
    private static final int JPEG_QUALITY = 80;
    private static final long CAPTURE_TIMEOUT_MS = 5000;
    private static final long MIN_INTERVAL_MS = 1100;
    private static final int MAX_HEADER_BYTES = 16384;

    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientPool = Executors.newFixedThreadPool(2);
    private final Object captureLock = new Object();
    private volatile ServerSocket server;
    private byte[] lastJpeg;
    private long lastCaptureAt;

    @Override
    protected void onServiceConnected() {
        Thread t = new Thread(this::serve, "tvekran-http");
        t.setDaemon(true);
        t.start();
    }

    private void serve() {
        try (ServerSocket s = new ServerSocket(PORT)) {
            server = s;
            Log.i(TAG, "listening on " + PORT);
            while (!s.isClosed()) {
                Socket c = s.accept();
                clientPool.execute(() -> handle(c));
            }
        } catch (IOException e) {
            Log.w(TAG, "server.failed", e);
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c; InputStream in = sock.getInputStream(); OutputStream out = sock.getOutputStream()) {
            sock.setSoTimeout(10000);
            String path = readRequestPath(in);
            if (path.startsWith("/screen.jpg")) {
                byte[] jpeg = capture();
                if (jpeg == null) {
                    respond(out, "503 Service Unavailable", "text/plain", "capture failed".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(out, "200 OK", "image/jpeg", jpeg);
                }
            } else if (path.startsWith("/health")) {
                respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            sock.shutdownOutput();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "client.failed", e);
        }
    }

    private static String readRequestPath(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') line.append((char) b);
            if (line.length() > 2048) break;
        }
        consumeHeaders(in);
        String[] parts = line.toString().split(" ");
        return parts.length >= 2 ? parts[1] : "";
    }

    /** Reads the rest of the request up to the blank line; closing with unread bytes makes the kernel send RST. */
    private static void consumeHeaders(InputStream in) throws IOException {
        int lineLength = 0;
        int total = 0;
        int b;
        while ((b = in.read()) != -1 && total++ < MAX_HEADER_BYTES) {
            if (b == '\n') {
                if (lineLength == 0) return;
                lineLength = 0;
            } else if (b != '\r') {
                lineLength++;
            }
        }
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.0 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Takes a fresh screenshot; the platform allows one per second, so a request inside that window reuses the last frame. A failed capture returns null, never a stale frame. */
    private byte[] capture() {
        synchronized (captureLock) {
            long now = System.currentTimeMillis();
            if (lastJpeg != null && now - lastCaptureAt < MIN_INTERVAL_MS) return lastJpeg;
            final byte[][] result = new byte[1][];
            final CountDownLatch done = new CountDownLatch(1);
            takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult shot) {
                    try (HardwareBuffer hb = shot.getHardwareBuffer()) {
                        ColorSpace cs = shot.getColorSpace();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            sw.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
                            sw.recycle();
                            result[0] = bos.toByteArray();
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "encode.failed", e);
                    }
                    done.countDown();
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.w(TAG, "screenshot.failed code=" + errorCode);
                    done.countDown();
                }
            });
            try {
                done.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (result[0] != null) {
                lastJpeg = result[0];
                lastCaptureAt = System.currentTimeMillis();
                return lastJpeg;
            }
            return null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        ServerSocket s = server;
        if (s != null) {
            try { s.close(); } catch (IOException e) { Log.w(TAG, "close.failed", e); }
        }
        clientPool.shutdownNow();
        callbackExecutor.shutdownNow();
        super.onDestroy();
    }
}
