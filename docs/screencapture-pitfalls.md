# Screen capture pitfalls (Bekon Gateway)

Notes for `pro.potoki.bekon` MediaProjection capture — useful when debugging snapshot timeouts or empty JPEGs.

## Deadlock: ImageAvailableListener on main thread

**Symptom:** `Capture timeout` after ~5 seconds. VirtualDisplay exists, but `acquireLatestImage()` returns null.

**Cause:** `capture()` on the main thread with `ImageReader` listener on `Looper.getMainLooper()`:

```
Main thread: latch.await() ← BLOCKS
Main looper: imageAvailableListener ← NEVER RUNS
```

**Fix:** Polling loop instead of listener+latch, or a dedicated `HandlerThread` for the listener.

## Logcat truncation of large responses

**Symptom:** JSON with base64 screenshot cut at ~4 KB in logcat.

**Fix:** Write JPEG to app files, return path in JSON. Retrieve with:

```bash
adb shell run-as pro.potoki.bekon cat files/screenshot_*.jpg > out.jpg
```

## acquireLatestImage() null on listener fire (Unisoc)

On some Unisoc chipsets the listener fires before the frame is queued. Prefer polling over listener+latch.

## PixelFormat compatibility

On low-end devices `RGBA_8888` may produce no frames. Fall back to `YUV_420_888` and convert with `YuvImage`.
