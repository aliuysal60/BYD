# ParkCam — Dashcam recording on BYD 51.1.X.XXXXXX firmware, Sealion 7 and Tang models. 360° Camera Access and 4-Camera Automatic Recording System

A system that accesses the 360° surround cameras on the BYD DiLink **51.x**
(SA8155, Android 11) head unit, with live viewing and **simultaneous automatic
H.264 recording of all 4 cameras**.

> **Notable:** this app **enables Developer options, USB debugging and wireless
> ADB by itself** — without the user ever opening the developer menu. It flips
> `development_settings_enabled` / `adb_enabled` / `adb_wifi_enabled` and brings
> up `adbd` on TCP 5555 through the TS Settings and OTA-test back doors (see
> [Enabling Wireless ADB](#enabling-wireless-adb)). As far as we know there is no
> other tool that does this on this firmware version, which is why it's worth
> calling out.

> ⚠️ **Warning:** This project runs on the in-vehicle Android system. Misuse may
> compromise system stability. Recordings and vehicle camera use are subject to
> your country's laws. All risk is the user's.

---

## Installing the APK (USB method, recommended for first install)

On a first install, `adb install` does **not** work: ADB is not enabled on the
car yet, and it's this app itself that enables ADB afterwards. So install the APK
from a USB drive instead:

1. Format a USB flash drive as **FAT32**.
2. On it, create a folder named **`Third Party Apps <country-code>`** (e.g.
   `Third Party Apps 90` — `90` is Turkey's country code). Use your own country's
   code and test it.
3. Put the APK you want to install (`DiLink-Inspector-<version>.apk`) inside that
   folder.
4. Plug the USB drive into the car and install the APK from it. The password for
   installing third-party apps is **`BYD8155F`**.

Applies to BYD Global on the 8155 (51.x) chipset. Tested on Tang 2025
(firmware `51.1.2.2411280.1`).

Once the app is installed and opened once, it enables wireless ADB, after which
you can update it over ADB (see [Installing on the vehicle](#installing-on-the-vehicle)).

---

## Removal

To remove the app from the car completely, first open the left slide-out menu
and tap the **Prepare for Removal** button (under Settings). It stops and cleans
up everything running in the background — the helper processes (camera recording,
qcam tools), the files under `/data/local/tmp`, and it also turns wireless ADB
back off. After that, uninstall the app from the car as usual.

> If you uninstall the app **without** doing this first, the background tools
> keep running on the car (ADB, the camera recording processes, etc.), because
> Android does not run any app code during an uninstall. Your recordings in
> `Movies/DiLinkCam` are kept either way.

---

## Table of Contents

0. [Installing the APK (USB method)](#installing-the-apk-usb-method-recommended-for-first-install)
0. [Removal](#removal)
1. [Features](#features)
2. [Requirements](#requirements)
3. [Architecture](#architecture)
4. [Build and Installation](#build-and-installation)
5. [Enabling Wireless ADB](#enabling-wireless-adb)
6. [Camera Access (qcarcam chain)](#camera-access-qcarcam-chain)
7. [Recording System](#recording-system)
8. [BYDAuto SDK — Vehicle State, Gear, Speed](#bydauto-sdk--vehicle-state-gear-speed)
9. [Power Management Realities (what works / what doesn't)](#power-management-realities)
10. [Keeper — shell-uid process](#keeper--shell-uid-process)
11. [Automatic Update and Deployment](#automatic-update-and-deployment)
12. [SELinux / Permission Matrix](#selinux--permission-matrix)
13. [Known Pitfalls (beware!)](#known-pitfalls)
14. [Diagnostics](#diagnostics)
15. [File Map](#file-map)

---

## Features

| Feature | Status |
|---|---|
| 4-camera (Front/Rear/Right/Left) simultaneous access | ✅ ~7-10 fps/camera |
| Auto-start recording when vehicle powers on | ✅ triggered by `getPowerLevel()==2` |
| +2 min recording and shutdown when vehicle powers off | ✅ |
| Recovery of recording interrupted by sleep | ✅ journal + remux |
| 10-minute segments, ≥20 GB free-space guarantee | ✅ oldest recording auto-deleted |
| H.264 800 kbps (4 cameras ≈ 360 MB/hour) | ✅ color (COLOR_Format pitfall fixed) |
| Live viewing (selectable camera) | ✅ |
| Vehicle state / gear / speed cloud telemetry | ✅ every 10 s |
| Low-CPU recording (buffer reuse + NEON NV12) | ✅ 4-camera recording CPU 92%→26% |
| Auto-refresh keeper + qcam on APK update | ✅ `MY_PACKAGE_REPLACED` + version stamp + MD5 |
| Blocking the vehicle's own AVM | ❌ no (our processes only kill our own names) |

---

## Requirements

- BYD DiLink 51.x (SA8155, Android 11) — test vehicles: BYD Tang/Sealion family
- Windows PC + Python 3 (for the build script)
- Android SDK (aapt2, d8, apksigner), JDK 8 (for the keeper.jar build), NDK r25 (for qcam_loop8)
- WiFi on the same network as the vehicle

---

## Architecture

```
┌─────────────────────── Android (head unit) ──────────────────────────┐
│                                                                       │
│  ParkCam APK (uid: app)                   keeper (uid: shell 2000)    │
│  ├─ MainActivity  ☰ menu UI              ├─ PARTIAL_WAKE_LOCK         │
│  ├─ ParkCamService                       ├─ WiFi watch (svc wifi)    │
│  │   ├─ CAN pulse (BYDAuto SDK)          ├─ app resurrection         │
│  │   ├─ auto-recording decision          ├─ command server :7778     │
│  │   ├─ 4× Mp4Recorder (MediaCodec)      └─ camera watch + orphan    │
│  │   └─ TCP frame pullers (7800-7803)         reaper                 │
│  └─ CameraEngine (ADB bridge, setup)                                  │
│                                       qcam_loop8 0..3 (shell uid)    │
│  Movies/DiLinkCam/*.mp4  ←──────────   └─ qcam_patched_cN tools       │
└───────────────────────────────────────────┬───────────────────────────┘
                                            │ VHAL (vendor.qti.automotive.qcarcam@1.0)
                                   QNX/AIS → MAX96722 → 4× cameras
```

- **App**: recording decision, encoding, UI. Does **not** touch ADB (the
  recording path is entirely over TCP; ADB is used only during setup).
- **keeper** (`/data/local/tmp/keeper.jar`): a watchdog running under the shell
  uid via `app_process`. It is exempt from TsCarPower's third-party sweep;
  resurrects the app if it dies, re-enables WiFi when it's turned off, and
  supervises the camera instances and orphan tools.
- **qcam_loop8 + qcam_patched_cN** (shell uid): one instance per camera. Dumps
  frames to `/data/local/tmp` (instead of a RAM disk) and distributes them over TCP.

---

## Build and Installation

### Required tools and versions

| Tool | Version (tested) | Used for |
|---|---|---|
| Python | 3.11 | the `build.py` pipeline |
| Android SDK → **build-tools** | 33.0.3 and 35.0.0 | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android SDK → **platforms** | android-33 and android-35 | `android.jar` (classpath + d8 `--lib`) |
| Android SDK → **platform-tools** | current | `adb` |
| Android **NDK** | r25.1.8937393 | building `qcam_loop8` (`aarch64-linux-android24-clang`) |
| JDK | 8 (`jdk1.8.0_351`) | `PowerKeeper.java` → jar (d8's `-Djava.ext.dirs` mode needs JDK 8; pass JDK 8 as `JAVA_HOME` to d8.bat) |
| JDK (for the APK) | 11+ is fine too | `build.py` uses the Android Studio JRE for javac |

### Building the APK

```bat
cd DiLink-Inspector
python build.py
```

The script runs, in order: `tools/prepare_vendor.py` (prepares the LibADB
source) → `aapt2 compile/link` (resources + manifest) → `javac` (all Java
sources + vendor jars) → `d8` (dex) → `zipalign` → `apksigner` (v1+v2+v3) →
`dist/DiLink-Inspector-<version>.apk`. The version is managed by `VERSION` in
`build.py` and `versionCode/versionName` in `AndroidManifest.xml` (update both
together).

Signing: `inspector-local.p12` is not included in the repo. Create it once:

```bat
keytool -genkeypair -storetype PKCS12 -keystore inspector-local.p12 ^
  -alias inspector -storepass inspector-local -keyalg RSA -validity 10950
```

(Adjust the `--ks` path and passwords in `build.py` to match your own key.)

### Building `keeper.jar` (`tools/native/PowerKeeper.java`)

```bat
set JAVAC=%JAVA8_HOME%\bin\javac.exe
set D8=%SDK%\build-tools\33.0.3\d8.bat
set ANDROID=%SDK%\platforms\android-33\android.jar

%JAVAC% -encoding UTF-8 -source 8 -target 8 -classpath %ANDROID% ^
  -d build_keeper tools\native\PowerKeeper.java

:: d8.bat needs JAVA 8:
cmd /C "set JAVA_HOME=%JAVA8_HOME%&& %D8% --release --min-api 26 ^
  --lib %ANDROID% --output build_keeper build_keeper\PowerKeeper.class"

mkdir build_keeper\jar && copy build_keeper\classes.dex build_keeper\jar\
%JAVA8_HOME%\bin\jar.exe cf app\src\main\assets\bin\keeper.jar ^
  -C build_keeper\jar classes.dex
```

- **The comments are UTF-8** → `-encoding UTF-8` is required, otherwise the
  build fails.
- The asset embedded in the APK is updated; during setup the app compares the
  remote `/data/local/tmp/keeper.jar` **by MD5**, replaces it, and restarts the
  old process. If you need to deploy manually (learned trick): `adb push` to
  `/data/local/tmp` is **not possible** (fchown) → push to `/sdcard` first, then
  `cp` + `chmod 644`; kill the old keeper **by PID** (`pkill -f PowerKeeper`
  would also kill your own shell!) and restart with the **CLASSPATH** command
  (see [Keeper section](#keeper--shell-uid-process)).

### Building `qcam_loop8` (`tools/native/qcam_loop8.c`)

```bat
set CLANG=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang
%CLANG% -O2 -o qcam_loop8 tools\native\qcam_loop8.c
copy qcam_loop8 app\src\main\assets\bin\qcam_loop8
```

- **Version match is critical:** `QCAM_VERSION` in the C source must equal
  `HELPER_VER` in `CameraEngine.java`. The app reads this value from
  `/data/local/tmp/qcam_loop_0.ver`; if it doesn't match, it kills the instances
  during setup and reinstalls them. When you change `qcam_loop8.c`: bump
  `QCAM_VERSION` → build → `assets/bin/qcam_loop8` → set `HELPER_VER` to the same
  number → bump `versionCode` → build the APK.
- The binary must land on the vehicle **with the execute bit set**;
  `installHelpers` runs `chmod 755` on every setup (losing permissions after a
  push is a known pitfall — the code guarantees it after every write).

### Per-camera patched clients (`qcam_patched_c0..c3`)

Produced from the project copy (`qcam_patched`) of the vehicle's `qcarcam_test`
binary (see [dump filename](#dump-filename--the-most-critical-detail)):

```bat
python tools\native\patch_dumpname.py qcam_patched
:: → produces qcam_patched_c0..c3; copy them under app\src\main\assets\bin\
```

These are also written by `installHelpers` with MD5 verification and
`chmod 755`. If `qcarcam_test` changes in new vehicle firmware, put the new copy
as `qcam_patched` and re-run the script (if the pattern isn't found the script
errors — meaning the dump format in the binary has changed).

### Assets → on-device deployment chain

```
assets/bin/keeper.jar       → /data/local/tmp/keeper.jar   (MD5 differs OR version old → kill + write + start with CLASSPATH)
assets/bin/qcam_loop8       → /data/local/tmp/qcam_loop8   (MD5 differs OR version old → reinstall, chmod 755)
assets/bin/qcam_patched_c0-3→ /data/local/tmp/...          (MD5-checked, chmod 755)
assets/bin/qcarcam_config.xml → sample config (derived per camera)
```

The whole chain runs automatically in the app's `bootstrap()` step (on every
service start and from the **Prepare Vehicle** button). Manual intervention is
only needed during development. Both keeper and qcam are refreshed via a
**version stamp + content (MD5) check** — see
[Automatic Update and Deployment](#automatic-update-and-deployment) for details.

### Installing on the vehicle

```bat
adb connect <vehicle_ip>:5555
adb install -r dist\DiLink-Inspector-<version>.apk
```

> If you run `adb install -r` **while the app is running**, the
> `MY_PACKAGE_REPLACED` broadcast starts the service automatically; keeper and
> qcam refresh themselves due to the version/MD5 difference — nothing manual is
> needed.
>
> On a first install (app never opened) or an install after `am force-stop`,
> Android does not send the broadcast (the app is in the "stopped" state). In
> that case, **opening the app once** is enough; MainActivity starts the service
> and triggers the chain. On the first ADB connection an RSA prompt may appear
> on screen → "Always allow".

---

## Enabling Wireless ADB

There are two ways to enable ADB without touching developer options on the head
unit (the app tries them automatically):

1. **TS Settings interface**: wireless ADB properties (`persist.adb.tcp.port`
   etc.) can be written via `transact` to the `com.ts.settings` provider.
   See `TsPropBridge.java`.
2. **OTA service test command**: `y(31,77)` → `adb_enabled=1`. See `OtaTestCommand.java`.

Then `adb connect <vehicle_ip>:5555` (an RSA prompt may appear on screen on the
first connection → "Always allow").

A critical note about the ADB bridge: the app contains **its own ADB protocol
client** (`AdbBridge.java`, LibADB-based, connects to `127.0.0.1:5555` with its
own RSA identity). ADB is not used during recording; the bridge is only for
setup/update tasks.

---

## Camera Access (qcarcam chain)

The cameras are **not exposed** to the Android Camera2 API (only an external DVR
device appears in the system). Frames go through the Qualcomm side: camera →
MAX96722 → AIS(QNX) → `vendor.qti.automotive.qcarcam@1.0-service` → clients. To
become a client on the head-unit side, the `qcarcam_test` tool is used (under
`/vendor/bin`; carried in the project as a patched copy named `qcam_patched_*`).

### Config (single input per camera)

```xml
<!-- /data/local/tmp/qcam_config_0.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<qcarcam_inputs>
    <input_device>
        <properties input_id="0"/>   <!-- 0=Front 1=Rear 2=Right 3=Left (measured) -->
        <output_setting width="1920" height="1024"
                        pixelformat="V4L2_PIX_FMT_UYVY"/>
    </input_device>
</qcarcam_inputs>
```

> Requesting 1280x960 in `output_setting` **does not work** — the source always
> produces 1920x1024 UYVY (3,932,160 bytes/frame). The active area is the left
> 1280x960; the bottom 64 rows and the right strip are padding → cropping is
> applied. In v10 this crop + the UYVY→NV12 conversion is done **inside
> `qcam_loop8` (NEON)**; `CameraEngine.toNv12()/toBitmap()` remains only for the
> legacy UYVY path (backward compatibility).

### Dumping: `-dumpFrame=N` (autonomous mode)

```bash
setsid nohup /data/local/tmp/qcam_patched_c0 \
  -config=qcam_config_0.xml -dumpFrame=3 </dev/null >/dev/null 2>&1 &
# → every 3rd frame is dumped: 30fps source ≈ 10 dumps/sec
```

- `-dumpFrame=N` = **dump every N-th frame per camera**. N=1→30fps, N=2→15,
  N=3→10 fps. Sending the '21' command one at a time from the menu is limited to
  ~1 fps (the tool polls stdin once a second) — always use `-dumpFrame` when you
  need speed.
- **PTY pitfall:** PTY stdin does not work with `-dumpFrame`; the tool exits
  immediately with `code=0`. stdin must be **/dev/null** (or a pipe kept open).
- Dump files are written to `/data/local/tmp/frame_*_*.raw`; `qcam_loop8` moves
  them to `snap_<cam>.raw` with an atomic rename (a half-written file is never read).

### Dump filename — the most critical detail

The `qcarcam_test` dump writes with the format `frame_%d_%i.raw`, where **`%d`
is not the camera ID, it is the device ARRAY index** and is **always 0** with a
single-input config. So the 4 instances write to the same `frame_0_*` name and
overwrite each other (along with corrupt frames similar to "session crossover").
The fix: a same-length byte patch in the format string inside the binary:

```
/data/local/tmp/frame_%d_%i.raw   →   frame_%d1%i.raw   (for camera 1)
                                          ^ camera digit instead of the '_' separator
```

A `qcam_patched_c0..c3` copy is produced per camera (script in `tools/`).
`qcam_loop8` runs the correct variant and collects the `frame_0<cam>*` pattern.

### Demand gate (disk protection)

Instances dump **only while there is demand**. Demand is refreshed two ways:
- File: `touch /data/local/tmp/cam_demand_<cam>` (demand exists if mtime is within the last 10 s)
- TCP: sending a `D\n` line to the frame socket (counts as 10 s of demand)

If demand is interrupted for 25 s the tool is stopped → **disk writing drops to
zero.** Without this, 4 cameras × 10fps × 3.9MB ≈ ~150 MB/sec of raw writing
occurs; the disk fills and the system UI freezes (it happened once — see
[Pitfalls](#known-pitfalls)).

### TCP protocol (127.0.0.1:7800+cam)

```
client → "D\n"                    (demand; PING\n → "PONG\n")
server → "FRME" | int32 cam | int32 counter | int32 size  (LITTLE-ENDIAN!)
       | size bytes of body
```

> **v10: the body is now NV12 (1280x960 = 1,843,200 bytes), not UYVY.**
> `qcam_loop8` converts UYVY to NV12 **on-device with NEON** (including the
> active-area crop) and sends that. Benefit: Java `toNv12` is removed from the
> app + the TCP load drops from 3.9 MB to 1.8 MB per camera. The Java side tells
> them apart by `size`: NV12_BYTES (1843200) → straight to the encoder;
> FRAME_BYTES (3932160) → legacy UYVY (backward compatible). The `snap_*.raw`
> file stays UYVY (for the ADB/file fallback).

On the Java side, **use `127.0.0.1` explicitly** —
`InetAddress.getLoopbackAddress()` may return `::1` (IPv6) on this platform
while the listener is IPv4 → ECONNREFUSED.

### Coexistence with the vehicle's own AVM

We are both clients of the same qcarcam service. Our processes manage only the
processes matching **our own names** (`qcam_loop8`, `qcam_patched_0..3`); they
don't touch the vehicle's AVM/panorama processes. The reverse-gear/turn-signal/
proximity AVM opens normally. The only possible symptom from resource sharing is
on our side (a gap/crossed frame in the recording); the vehicle side is unaffected.

---

## Recording System

### Automatic flow

```
getPowerLevel()==2 (VEHICLE ON)  →  4-camera recording STARTS
getPowerLevel()==0 (OFF)         →  +2 more minutes of recording → clean shutdown
if the system goes to sleep       →  journal remains → recoverPending() on boot
                                    converts the broken file into a playable MP4
```

Code: `ParkCamService.canPulse()` makes a BYDAuto call + decision every 10 s;

```java
boolean open = "ACIK".equals(aracDurum);
if (open && !RECORDING) startRecording("vehicle on");
if (!open && RECORDING) {
    if (closedSinceMs == 0) closedSinceMs = System.currentTimeMillis();
    else if (System.currentTimeMillis() - closedSinceMs >= TAIL_MS)
        stopRecording("vehicle off +2 min elapsed");
}
```

### Encoder settings

```java
fmt.setInteger(KEY_BIT_RATE, 800_000);      // ~360 MB/hour (4 cameras)
fmt.setInteger(KEY_FRAME_RATE, 30);
fmt.setInteger(KEY_I_FRAME_INTERVAL, 2);
// video/hevc is tried; this build has no HEVC ENCODER → falls back to video/avc
```

> **The HEVC reality:** This vehicle firmware has only a **decoder** for
> `video/hevc` (`c2.qti.hevc.decoder`); the encoder component is not in the
> codec list (even if the hardware is capable). The `video/avc` encoder
> (`c2.qti.avc.encoder`) is present. So recording is H.264.
> `MediaCodec.createEncoderByType("video/hevc")` throws an exception → the
> fall-back to AVC is done with exception handling in the code.

#### The color format pitfall — recording came out BLACK-AND-WHITE (fixed)

The AVC encoder on this vehicle **does not accept** concrete YUV420 color
formats (`COLOR_FormatYUV420SemiPlanar` = NV12, `COLOR_FormatYUV420Planar` =
I420, QCOM SemiPlanar) at the `configure()` stage. `pickColorFormat()` tries all
three, fails, and selects `COLOR_FormatYUV420Flexible` (0x7F420888).

> **Pitfall:** In the Flexible format the encoder **decides the plane layout**
> of the input buffer (`rowStride`, `pixelStride`) itself and reports it via
> `Image`. If you write raw NV12 bytes directly with `inputBuffer.put(nv12)`,
> the **UV plane is misaligned → color is lost**. Because Y (luma) stays
> correct, the video comes out *sharp but gray*. The raw frame (TCP) is in
> color; the loss is only at the encoding stage.

Correct feeding (`Mp4Recorder.feed` + `fillImageFromNv12`):

```java
if (LAST_COLOR_FORMAT == COLOR_FormatYUV420Flexible) {
    Image img = encoder.getInputImage(inIndex);     // the encoder's Image
    // NV12 → write to the Image's Y/U/V planes honoring rowStride/pixelStride
    // (semi-planar: U/V in the same buffer, pixelStride=2; planar: separate — the API handles it)
    fillImageFromNv12(img, nv12);
    encoder.queueInputBuffer(inIndex, 0, w*h*3/2, ptsUs, 0);
} else {
    inputBuffer.put(nv12);                           // concrete format: raw copy
}
```

The selected format appears for diagnostics in the `COLOR_FORMAT:` line of `durum.txt`.

### Performance — 4-camera recording was slowing down the system (fixed)

Simultaneous 4-camera recording was slowing Android down noticeably (with 32
channels open the system had nearly halted). Live on-vehicle measurement showed
two bottlenecks; both were eliminated.

| Metric (during recording) | Before | Buffer reuse | + NEON NV12 |
|---|---|---|---|
| App CPU | 92.8% | 65.7% | **25.7%** |
| `pull-w` thread (each) | ~15% | 10-18% | **~4%** |
| GC (`HeapTaskDaemon`) | 22% | **0%** | 0% |
| TCP load (per camera) | 3.9 MB | 3.9 MB | **1.8 MB** |

1. **GC pressure → buffer reuse.** `pullFrames` allocated `new byte[3.9MB]`
   (raw) every frame + `toNv12` allocated `new byte[1.8MB]` every frame = ~230
   MB of garbage per second. GC was eating 22% of a core and causing
   stop-the-world pauses (stutter). Fix: allocate per-camera `frameBuf`,
   `nv12Buf`, `bmpScratch` arrays once and reuse them every frame. Since `feed()`
   copies NV12 into the encoder, the buffer is reusable after it returns.
   (`pull-w-<cam>` threads are separate per camera → no lock.)

2. **Java conversion → NEON (device side).** The `toNv12` Java loop (4 cameras ×
   ~10 fps × 1.2 Mpixels) kept the `pull-w` threads at ~15%. The conversion was
   moved into `qcam_loop8` (aarch64 NEON); ready NV12 now arrives over TCP.
   Thanks to NEON, `qcam_loop8` CPU did not rise, and the TCP load halved.

The remaining load is mostly in the hardware encoder (`media.hwcodec` ~25%) and
in TCP reading.

### The idle-frame filter was REMOVED — why?

Filters like `changedEnough()` ("skip the frame if the scene hasn't changed")
fall below the threshold in a night scene → only ~20 frames accumulate in a
10-minute recording (this was the source of the "frozen video" problem). Since
the encoder already compresses a static P-frame down to ~1 kbps, no software
filter is needed: **every frame is fed.**

### Segment + space management

- A new file per camera every 10 minutes (`Movies/DiLinkCam/DiLinkCam_*.mp4`)
- At every segment open and at the start of recording, `ensureFreeSpace(20GB)`:
  the **oldest** recording is deleted, continuing until 20 GB is free → the disk
  never fills.
- On boot, `recoverPending()`: a half recording is rebuilt from the journal and
  `.pending-*` leftovers are cleaned up.

---

## BYDAuto SDK — Vehicle State, Gear, Speed

The SDK is inside the system app `BydAutoService.apk`; it is loaded via reflection.

### Loading + permission bypass

```java
PathClassLoader loader = new PathClassLoader(
    "/system/app/BydAutoService/BydAutoService.apk",
    "/system/lib64", getClassLoader());
Context bydCx = createBydBypass();  // below
Class<?> cls = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice",
    true, loader);
Object dev = cls.getMethod("getInstance", Context.class).invoke(null, bydCx);
```

Permission bypass — **overriding `getApplicationContext()` is required**:

```java
new ContextWrapper(this) {
    @Override public Context getApplicationContext() { return this; }  // CRITICAL!
    @Override public int checkCallingOrSelfPermission(String p) {
        return p != null && p.startsWith("android.permission.BYDAUTO_")
            ? PackageManager.PERMISSION_GRANTED
            : super.checkCallingOrSelfPermission(p);
    }
    @Override public void enforceCallingOrSelfPermission(String p, String m) {
        if (p != null && p.startsWith("android.permission.BYDAUTO_")) return;
        super.enforceCallingOrSelfPermission(p, m);
    }
};
```

> Why it's critical: apps like `GearboxManagerImpl` call
> `con.getApplicationContext().checkCallingOrSelfPermission(...)` in their
> constructor — if not overridden, the wrapper is stripped, the permission is
> asked against the real app context, and you get a `SecurityException` (gear
> shows `?`, the device doesn't load).

### Method reference (with measured values)

| Class | Method | Returns | Note |
|---|---|---|---|
| `BYDAutoPowerDevice` | `getPowerLevel()` | **0 = OFF, 2 = ON** | observed; other values are transition states |
| `BYDAutoGearboxDevice` | `getGear()` | **1=P, 2=R, 3=N, 4=D** | ⚠️ The SDK constants (`GEAR_P=3` etc.) are **wrong**; the raw CAN value arrives shifted. This table was calibrated on the vehicle. |
| `BYDAutoSpeedDevice` | `getCurrentSpeed()` | `double` km/h | |
| `BYDAutoPowerDevice` | `wakeUpMcu()`, `qcomWakeup(int)` | always **-1** | **STUB** — internally `mForTest!=1 → return -1`; they send nothing to the MCU |
| `BYDAutoPanoramaDevice` | `getPanoWorkState()` etc. | int | state only; **no frame data** |
| `BYDAutoBodyworkDevice` | door/lock etc. | — | reading works |

```java
// reading gear (calibrated mapping)
Object g = gearCls.getMethod("getGear").invoke(gearDev);
int v = ((Number) g).intValue();          // 1=P 2=R 3=N 4=D
String gear = v==1?"P":v==2?"R":v==3?"N":v==4?"D":"?";
```

Cloud telemetry: `ParkCamService.canPulse()` produces a ping + notification
update every 10 s in the form `OFF_P1_0km/h`.

---

## Power Management Realities

When the vehicle powers off, TsCarPower enters standby, kills third-party apps,
turns off WiFi, and the CPMS calls `nativeForceSuspend`. All of the following
were **tried and do NOT prevent sleep** (evidence in the logs):

| Method | Result |
|---|---|
| `PARTIAL_WAKE_LOCK` (app and shell) | ❌ forceSuspend ignores the wakelock; processes freeze |
| `SCREEN_BRIGHT_WAKE_LOCK` | ❌ froze even while held |
| `IPowerManager.userActivity()` every 4 s (shell binder) | ❌ the call is accepted, standby doesn't change |
| `Settings.Global stay_on_while_plugged_in=7` | ❌ the setting is written, but ineffective because the battery doesn't report "plugged in" |
| `AlarmManager.RTC_WAKEUP` 60 s | ❌ the kernel doesn't run during suspend; the alarm is delivered at wake time (log: 19-26 min of silence) |
| Holding a loopback ADB session | ❌ does not prevent sleep |
| `wakeUpMcu()` / `qcomWakeup()` | ❌ stub (above) |
| `persist.vendor.car.lpm=0` | ❌ cannot be written (vendor internal prop) |
| `cmd car_service ...` | ❌ "requires non-user build" (ro.debuggable=0) |

**Sleep is governed externally (T-Box → CAN → MCU).** Periodic self-wake windows
occur (~every 20-30 min, 5-20 min long); the system recovers in each window
(see `keeper_power.log`). There is nothing that can be done from inside to force
continuous wakefulness on this platform.

WiFi: in the standby dance TsCarPower turns WiFi off; keeper re-enables it within
2-4 s via `svc wifi enable` (`startWifiWatch`).

---

## Keeper — shell-uid process

`PowerKeeper.java` → `/data/local/tmp/keeper.jar` → runs under the shell uid via
`app_process` → **exempt from TsCarPower's forceStopPackage sweep**.

### Start command (this is the correct one)

```bash
cd /data/local/tmp && \
CLASSPATH=/data/local/tmp/keeper.jar \
LD_LIBRARY_PATH=/system/lib64 \
setsid nohup /system/bin/app_process64 /system/bin \
  --nice-name=keeper PowerKeeper > keeper_out.log 2>&1 < /dev/null &
```

> **CLASSPATH is required.** If started without `CLASSPATH`, `app_process`
> cannot find the class and exits **silently, without printing any error** — the
> #1 reason keeper appears to be "not running".

Its tasks: PARTIAL wakelock, WiFi watch, app resurrection (`am start`),
**command server 7778** (run a shell command via telnet), a cloud ping every
10 s + power diagnostics in `keeper_power.log`, camera watch (restarts a dead
instance), and the **orphan reaper** (kills a camera tool it finds without a loop).

---

## Automatic Update and Deployment

When a new APK is installed, the long-lived `keeper` and `qcam_loop8` processes
under `/data/local/tmp` must be updated too. If an old process stays alive, the
new binary doesn't take effect. The chain works like this:

### Triggers

| Event | Starts the service | Automatic? |
|---|---|---|
| Vehicle power-on | `BootReceiver` (`BOOT_COMPLETED`) | ✅ |
| APK update | `BootReceiver` (`MY_PACKAGE_REPLACED`, with the `setup` intent) | ✅ |
| First install (app never opened) | none — Android doesn't broadcast to a "stopped" app | ⚠️ open the app once |

The service runs `bootstrap()` (ADB → keeper → qcam) automatically on every
`onCreate`; the **Prepare Vehicle** button triggers the same `bootstrap`.

### Version stamp + content check

Both keeper and qcam base their "running, leave it alone" decision on two criteria:

- **Version stamp:** keeper writes `KEEPER_VER` to `/data/local/tmp/keeper.ver`;
  qcam writes `QCAM_VERSION` (`= HELPER_VER`) to `qcam_loop_<cam>.ver` for each
  camera. If it doesn't match the constant in the app, it is refreshed.
- **Content (MD5):** even if the version matches, the on-device binary is
  MD5-compared with the APK asset. If it differs (e.g. code was fixed without
  bumping the version constant), it is refreshed anyway.

> **The version constants are kept in sync manually.** When you change keeper,
> both `PowerKeeper.KEEPER_VER` and `ParkCamService.KEEPER_VER` must be bumped;
> for qcam, `QCAM_VERSION` in `qcam_loop8.c` must equal `CameraEngine.HELPER_VER`.
> Thanks to the MD5 check, even if you forget the constant it's caught by the
> content difference, but bumping the stamp avoids an unnecessary reinstall.

### Killing the old keeper — the pitfall to watch

keeper starts as `app_process64 --nice-name=keeper`. In this case
`/proc/<pid>/comm` is **`main`** (the JVM main thread), and only
`/proc/<pid>/cmdline` is **`keeper`**. Therefore:

- `pkill -x keeper` → **doesn't match** because `comm=main`.
- `pkill -f PowerKeeper` → **doesn't match** because `PowerKeeper` isn't in the cmdline.

The only reliable way is to scan for pids whose cmdline is exactly `keeper` and
`kill -9` them (`gatekeeper` and other uids are naturally excluded):

```bash
for p in $(ls /proc | grep -E '^[0-9]+$'); do
  c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' ' | sed 's/ *$//')
  [ "$c" = keeper ] && kill -9 $p 2>/dev/null
done
```

Otherwise the old + new keeper pile up on each update.

---

## SELinux / Permission Matrix

| Operation | shell (adb) | app (untrusted_app) |
|---|---|---|
| read/write/execute `/data/local/tmp` | ✅ | ❌ **cannot read** files (SELinux) → uses the ADB bridge or TCP |
| `settings put global ...` | ✅ | ❌ (no WRITE_SECURE_SETTINGS) |
| `svc wifi enable` / `svc power` | ✅ | ❌ |
| `dumpsys`, `service call` | ✅ (most) | limited to its own process |
| write `/sys/power/wake_lock` | ❌ | ❌ |
| write `/vendor` | ❌ (RO + verity) | ❌ |
| `persist.vendor.*` setprop | ❌ internal | ❌ |
| `cmd car_service set-prop` | ❌ not a user build | ❌ |
| TCP 127.0.0.1:7800+ (to the shell process) | — | ✅ **via IPv4** |
| `media.camera` (Camera2) 360° | — | ❌ only the DVR device is listed |
| IQnx (`com.ts.lib.qnx`) | binder message | message only; **cannot carry frames/video** |
| bmmcamera.jar (AVMCamera) | — | ❌ **not present on 51.x** (specific to DiLink 3.0/5.0) |

ADB file-transfer pitfalls — **on a user build**:
- `adb push /data/local/tmp/...` → `remote fchown failed: Operation not permitted`
  → push to `/sdcard` first and move it with `cp`.
- `adb pull ...` → `forbid_pull_file through adb on user version` → **forbidden**.
- `adb shell cat binary` → CRLF conversion corrupts the file (the size changes!)
  → use `base64 file | tr -d '\r' | base64 -d` or `adb exec-out 'cat ...'`.
- `adb exec-out 'cat "/sdcard/.../file (1).mp4"' > local.mp4` ✓ this is the correct one.

---

## Known Pitfalls

1. **chmod after a push**: after rewriting a binary its permission drops to 644 →
   it **silently** never starts, with `Permission denied`. `chmod 755` after
   every write is required (the code guarantees it).
2. **`adb install` doesn't kill the process** (on this platform): after an
   update, `am force-stop <package>` + reopen, otherwise the old code keeps running.
3. **`pkill -f <pattern>`** also **kills your own shell** if the pattern appears
   on your command line. Either use comm-based `pkill -x exact_name` or use the PID.
4. **Tool storm**: if the loop-child sync drifts, a new camera tool spawns every
   cycle (32 instances observed) → the disk fills with ~GB/s of writing, and the
   **head unit becomes extremely slow**. Protection: single-child `waitpid`
   tracking + `prctl(PR_SET_PDEATHSIG, SIGKILL)` in the child (child dies when
   the parent dies) + the loop cleans up its own orphans at startup + the keeper
   orphan reaper.
5. **Socket fd inheritance**: if a forked child inherits the listener socket, the
   tool breaks. `FD_CLOEXEC` on all sockets.
6. **`getLoopbackAddress()` → `::1`**: listener is IPv4 → connection refused.
   Write `getByName("127.0.0.1")`.
7. **Reader thread freeze**: persistent reader threads occasionally block and
   can't recover. Fix: a **fresh short-lived worker** each cycle + a 15 s
   watchdog (the stuck worker's socket is force-closed).
8. **Nothing runs during kernel suspend**: alarm/service/thread, no difference;
   it stops at the hardware level. Everything is delivered/restored at wake time.
9. **`/data/local/tmp` is NOT tmpfs**: it's a real disk. Raw frame dumping
   produces serious writes — never dump continuously without the demand gate.
10. **Verify a patched binary deploy by MD5** — different versions with the same
    size can silently stay stale.

---

## Diagnostics

| Source | What it gives |
|---|---|
| `/sdcard/Android/data/.../files/durum.txt` | in-app status + **RD0-3 frame counters** (updated every 10 s) |
| `/data/local/tmp/keeper_alive.log` | keeper pulse, resurrections, camera watch |
| `/data/local/tmp/keeper_power.log` | mWakefulness trace (sleep/wake windows) |
| `/data/local/tmp/q8_<cam>.log` | instance log (moved counter = moved frames) |
| `/data/local/tmp/q8_tool_<cam>.log` | qcarcam_test output (FPS report) |
| `http://<server>/status.json` | cloud ping liveness (cut off during sleep = normal) |

Test-recording trigger: `touch /sdcard/parkcam_rectest` (60 s recording;
mtime-protected, single-shot). During recording `ps -A | grep -c qcam_patched`
should be **exactly 4** — more than that is a sign of a storm.

---

## File Map

```
app/src/main/java/com/solutera/dilinkinspector/
  MainActivity.java      ☰ menu UI (Home / Live Camera / Settings)
  ParkCamService.java    the heart: CAN pulse, auto-recording, TCP pullers
  CameraEngine.java      ADB bridge, camera setup, UYVY→NV12/Bitmap
  Mp4Recorder.java       MediaCodec H.264/HEVC, segment, space, recovery
  AdbBridge.java         in-app ADB protocol client (RSA)
  TsPropBridge.java      wireless ADB property interface
  OtaTestCommand.java    adb_enabled test command
  BootReceiver.java      service + ADB chain on boot
app/src/main/assets/bin/
  keeper.jar             shell watchdog
  qcam_loop8             per-camera loop (C, built with the NDK)
  qcam_patched_c0..c3    qcarcam clients patched with a per-camera dump name
  qcarcam_config.xml     sample config
build.py                 aapt2+javac+d8+apksigner build pipeline
tools/native/qcam_loop8.c      the loop source (built with the NDK)
tools/native/PowerKeeper.java  the watchdog source (jar via javac+d8)
tools/native/patch_dumpname.py per-camera patched-client generator
tools/                   build and test helpers
```

---

## Notes and Limits

- Recording frame rate is ~7-10 fps/camera (the source's `-dumpFrame=3`
  setting). 15 fps is possible with N=2; raw disk writing increases
  proportionally (a UFS-wear trade-off).
- The raw frame is 1920x1024 UYVY; the active-area crop is done in software.
- An occasional AVM contribution (a crossed frame) may appear in the image — it's
  resource sharing; the wrong-source detector catches most of them.
- Continuous wakefulness (while the vehicle is off) is not possible from inside
  on this platform; the system recovers during the periodic wake windows.

You are welcome to open issues/PRs for contributions and findings.
