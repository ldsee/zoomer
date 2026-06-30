# Zoomer

A pinch-to-zoom overlay for Android. Magnify and pan **any** app's content —
blow up a 4:3 video to fill a tall or foldable display, zoom into a UI that has
no zoom of its own — using a floating overlay driven by screen capture.

Think of it as YouTube's pinch-zoom, but available over any single app.

## Features

- **Pinch to zoom, drag to pan** over a live capture of your screen.
- **Two render backends, switchable in-app:**
  - **GPU** (OpenGL ES) — hardware accelerated, smooth, low power. Recommended.
  - **CPU** (Canvas) — software fallback, maximally compatible.
- **Two modes**, toggled from the notification:
  - **Zoom** — the magnified view, with gestures.
  - **Pass** — transparent passthrough so you can tap the real app (pause,
    scrub, etc.); your zoom is preserved when you switch back.
- **Quick Settings tile** to start/stop without opening the app.
- Screen stays awake while the overlay is active.

## Known limitations

- **Use single-app capture.** When the system screen-capture dialog appears,
  choose a single app rather than the whole screen. **Whole-screen capture is
  experimental and not currently working** (the overlay ends up inside its own
  capture). It is left in place for future work; single-app capture is the
  supported path for now.

## How it works

```
MediaProjection ──▶ VirtualDisplay ──▶ capture surface ──▶ render with zoom/pan
                                          │
                          GPU: SurfaceTexture → OpenGL shader
                          CPU: ImageReader   → Bitmap → Canvas
```

A single `CaptureEngine` owns the `MediaProjection`/`VirtualDisplay` lifecycle.
Both renderers implement a common `ZoomRenderer` interface, so the overlay
service drives either one identically. Zoom state is shared and preserved across
mode and backend changes.

## Architecture

| File | Responsibility |
|------|----------------|
| `RenderCore.kt` | Shared types: `RenderMode`, `ZoomState`, `CaptureDimensions`, `ZoomRenderer` interface |
| `CaptureEngine.kt` | Owns MediaProjection + VirtualDisplay (created once) |
| `GpuZoomRenderer.kt` | OpenGL ES backend |
| `CpuZoomRenderer.kt` | Canvas/software backend |
| `ZoomGestureHandler.kt` | Shared pinch/pan gesture logic |
| `ZoomOverlayService.kt` | Foreground service hosting the overlay |
| `MainActivity.kt` | Permission flow + backend selector |
| `ZoomQuickSettingsTile.kt` | Start/stop tile |

## Building

Requires Android Studio (or the Gradle CLI) with a JDK 17 toolchain.

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Permissions

- **Display over other apps** — to show the overlay.
- **Screen capture** — granted per-session via the system dialog; used only to
  mirror your screen into the zoom view. Nothing is recorded or sent anywhere.

## Requirements

- Android 10 (API 29) or newer.

## License

See [LICENSE](LICENSE).
