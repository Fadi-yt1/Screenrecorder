# QORVEXA — Screen Recorder

**Capture Every Frame. Experience Every Detail.**

Native Android screen recorder: Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, coroutines/Flow.
Package `com.qorvexa.recorder`, minSdk 21, targetSdk 35.

## Build

Requirements: JDK 17 and the Android SDK (Android Studio Ladybug or newer works).

```bash
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:assembleRelease                # release APK (minified)
./gradlew :app:lintDebug                      # lint
./gradlew :app:connectedDebugAndroidTest      # device/emulator tests
```

APK locations:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Every push runs the same steps in GitHub Actions (`.github/workflows/android.yml`) and uploads both APKs as the **qorvexa-apks** artifact on the run page.

### Release signing

Add `keystore.properties` at the repo root (git-ignored):

```
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Or set `QORVEXA_KEYSTORE`, `QORVEXA_KEYSTORE_PASSWORD`, `QORVEXA_KEY_ALIAS` and `QORVEXA_KEY_PASSWORD`. Without these, the release APK is signed with the debug key so it can still be installed for testing. **Don't publish a debug-signed APK.**

## Architecture

```
core/   Pure Kotlin (JVM unit-tested): models, config resolver, bitrate math, device
        profiling, engine selection, PCM mixing/resampling/filters, state machine,
        pause-aware presentation clock, auto-stop guard, file naming, stroke history.
app/
  recording/        RecorderService (foreground: mediaProjection|microphone),
                    RecordingSession (owns all native resources), RecorderController
                    (shared status), ProjectionRequestActivity (consent), notifications
  recording/encoder VideoEncoder (surface input, sync or async), AudioEncoder (AAC), MuxerSink
  recording/audio   AudioCapture: mic + AudioPlaybackCapture, effects, mixing
  recording/engine  Basic / Advanced (direct VirtualDisplay → encoder), OpenGL ES compositor
  recording/gl      EGL + shaders
  overlay/          OverlayService: bubble, control panel, drawing canvas + toolbar, countdown
  device/           Capability detection (MediaCodecList, EGL, sysfs, …), device test
  storage/          MediaStore / legacy file output, thumbnails
  library/          Room metadata + crash recovery
  settings/         DataStore
  ui/               Compose screens: Home, Recording, Settings (Video, Audio, Engine,
                    Overlay, Gaming, Advanced), Device, Recordings, Player, About
```

Pipeline: `MediaProjection → VirtualDisplay → Surface → MediaCodec → MediaMuxer → MP4`. The OpenGL engine inserts `SurfaceTexture → GLES → encoder surface` for an exact FPS cap, watermark and GPU screenshots.

Files are saved to `Movies/ScreenRecorder/<year>/<Month>/` (screenshots to `Pictures/ScreenRecorder/…`). Everything stays local: no account, no network permission.

## What is honest about this app

These are platform limits, and the UI explains them where they apply:

- **FPS ≤ display refresh rate.** The screen never produces more frames than it refreshes. 240 FPS is only offered on a 240 Hz panel whose encoder supports it.
- **Resolution ≤ native screen.** 4K is offered only on a 4K-class screen with a 4K-capable encoder. Nothing is upscaled.
- **HDR is not available.** Android screen capture is SDR.
- **Internal audio needs Android 10+.** Apps can opt out of capture (calls, DRM media), and those sounds are silent.
- **Touch indicators** use Android's "Show taps" developer option. Apps can read this setting but can't turn it on, so the app links you to it.
- **Overlay and drawings appear in recordings**, because MediaProjection captures everything on screen. You can hide the overlay while recording.
- **Vulkan:** the device's Vulkan support is detected and shown, but this build has **no Vulkan compositor**. That engine is marked "not included", and the OpenGL engine provides GPU composition.
- **Crash recovery:** MP4 writes its index when recording stops. If the process is killed mid-recording, the file is kept if it's playable; otherwise it's deleted on the next launch.
- **Android 14+** asks for screen-capture consent for every recording.
- **Device test** is a short peak-throughput check. It doesn't prove the phone can sustain those settings for a long recording.

## Testing status

- `core`: 52 JVM unit tests, run locally and in CI.
- `app`: JVM tests, plus instrumentation tests covering:
  - the real encoder → muxer → MP4 path, including pause with no gap
  - MediaStore create/publish/delete
  - Room
  - hardware detection
  - Compose navigation

  They run on an API 34 emulator in CI.
- Screen capture itself, internal audio, the overlay and the drawing tools need a physical phone and user consent, so they can't be automated here. **Test them on real hardware before release.**
