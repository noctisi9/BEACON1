# Phone Bridge

Control your Android phone from a Windows app over your own hotspot/LAN —
no TeamViewer, no cloud relay, no third party ever touches your traffic.
One Flutter codebase, two entry points (`Platform.isAndroid` picks the UI),
built by the same GitHub Actions push you're already using.

## What's here vs. what you need to generate

This folder has the **custom** files only — the Dart UI, the native Android
Kotlin (screen capture + input injection), the manifest additions, and the
CI workflow. It is *not* a full Flutter project skeleton (gradle wrapper,
`windows/` runner, etc.) because that's machine-generated boilerplate.

### One-time setup on your machine (or in Codespaces if your laptop is weak)

```bash
flutter create --org com.venom --project-name phone_bridge .
```

Run this **inside** this folder. It will generate the missing
`android/`, `windows/`, `ios/`, `macos/`, `linux/`, `web/` scaffolding
and gradle wrapper files, without touching the files already here (it
will ask before overwriting `AndroidManifest.xml` — say no, or just
re-copy these files back over afterward).

Then add coroutines + org.json to `android/app/build.gradle` dependencies block:

```gradle
dependencies {
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0"
    // org.json is already bundled in Android, no dependency needed
}
```

And bump `minSdkVersion` to at least 24 in the same file (MediaProjection +
modern AccessibilityService gesture APIs need it):

```gradle
defaultConfig {
    minSdkVersion 24
    ...
}
```

Commit, push, let GitHub Actions build both artifacts — grab the APK and
the Windows build from the workflow run's Artifacts tab.

## How it works

- **Phone app** (`lib/phone_agent_page.dart` + native Kotlin): asks for
  MediaProjection (screen capture) and Accessibility Service (input
  injection) permission once, then runs a foreground service that opens a
  plain TCP server on port 8888 and streams JPEG frames to whoever connects.
- **PC app** (`lib/pc_client_page.dart`): a TCP client, no external
  packages. Type in the phone's hotspot IP (shown right on the phone app's
  home screen), hit connect, and you get a live view you can tap and drag
  on — those get translated back into taps/swipes on the actual phone via
  the Accessibility Service.
- Everything stays on your hotspot's local network — nothing goes over the
  public internet, so there's no bandwidth fight with anything else and no
  outside server ever sees your screen or your files.

## Known first-pass limitations (by design, so you can build and iterate)

- Frames are JPEG snapshots at ~15fps, not real H.264 video — much simpler
  to implement correctly, costs more bandwidth per frame. If it feels
  sluggish over your specific hotspot, drop the JPEG quality (currently 60)
  or the target resolution in `ScreenStreamService.kt`, or lower `delay(66)`
  for faster/slower polling.
- The PC client's swipe handling only sends the drag *start* point twice
  (see the comment in `_handlePanEnd`) — wire up `onPanUpdate` if you want
  true swipe end-coordinates.
- No file-transfer feature yet (your ShareIt-style piece). Cleanest way to
  add it: a second small HTTP server in `ScreenStreamService.kt` (or a
  separate service) serving `/sdcard` over the same hotspot IP, with a
  "Browse phone files" button in the PC app hitting it. Say the word and
  I'll build that layer in next.
- No auth/pairing — anyone on your hotspot's subnet could connect to port
  8888. Since it's just you, fine for now; a shared PIN handshake on
  connect is a quick addition if you ever want it locked down.
