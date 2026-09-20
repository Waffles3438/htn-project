# Circuit — designer + native Android AR

Type a circuit idea on the phone, review its breadboard schematic and assembly steps, then choose **Show beside my board**. That launches the bundled native ARCore viewer, which detects the physical breadboard and places a GLB instructional model next to it. The project no longer needs a laptop renderer, a WebSocket, or a Unity export for this path.

The Android app includes the circuit designer, the Python circuit API contract, and the native AR viewer. Physical alignment must still be verified on an ARCore-capable phone. The Unity project remains in the repository as an optional alternative renderer, not as the launcher's required AR module.

## Native AR viewing

Tap **Calibrate**, hold the whole breadboard in view, then tap its detected outline. Once the app says **Board tracked**, move slowly around it while keeping the physical board visible. Camera-image measurements correct the outline and model position continuously, even when ARCore's world map drifts or pauses. ARCore motion is used only for short interpolation after it agrees with repeated image measurements; large tracking jumps are rejected. The model remains three-dimensional and follows the measured viewing angle. Brief missed detections are tolerated; if the full outline is lost for longer, bring it back into view for automatic reacquisition.

Calibration accepts only corner orders that keep the model's printed top facing the camera. It fixes the adjacent model's side at calibration time, so it does not switch sides while you walk around. The detector does not read the board's printed labels. Tap **Calibrate** again after moving the physical breadboard.

Start calibration from above the board: cyan selection outlines (and the green selected outline) are rectangles. Once calibrated, the yellow outline uses the measured perspective quadrilateral, not that selection rectangle. Calibration compares 165 × 55 mm and 165 × 65 mm board profiles and locks the chosen dimensions for tracking; these are supported assumptions, not a measurement of an arbitrary board. A second brightness pass separates bright carpet threads from the plastic. If edge fitting fails, selection remains active so you can tap again without restarting calibration.

Enclosing boxes with no measured edge support remain selection targets only; they cannot replace a tracked perspective pose. Sudden changes exceeding roughly 12° or 25 mm require at least three consistent measurements over 120 ms before the tracker accepts them. Small continuous movements still update immediately. Rejected detections do not update corner identity or prolong the visibility timeout.

On Windows, build, test and install on a USB-connected phone with USB debugging enabled:

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
if ($LASTEXITCODE -eq 0) {
    & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "$PWD\app\build\outputs\apk\debug\app-debug.apk"
}
```

## Build on this Mac

Java 17, Android SDK/adb/emulator, Unity 6000.6.2f1, Android Build Support, and Unity Hub are installed under `../.android-tools/`. The install is local to this workspace, not `/Applications`. Unity Hub is `../.android-tools/Unity Hub.app`; the editor is `../.android-tools/Unity-6000.6.2f1/Unity.app`.

1. The local editor is activated. On another machine, activate an eligible Unity license in Hub and add the matching editor.
2. From this folder:

```sh
./scripts/build-android.sh         # Android APK + circuit-contract tests
./scripts/test-unity.sh            # Optional Unity EditMode tests
./gradlew :app:assembleDebug        # Native designer + AR viewer APK
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With a physical Android 8+ ARCore-capable phone and USB debugging:

```sh
source scripts/android-env.sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set a deployed API origin in the app's **Settings**, or bake it into a build with `-PcircuitApiUrl=https://your-service.vercel.app`. Debug builds accept a local HTTP origin such as `http://10.0.2.2:8000`; release builds require HTTPS. Offline examples are explicitly labeled and work without a backend or API key.

For another machine, install JDK 17, Android SDK 35 + build tools 36, NDK r27c (`27.2.12479018`), CMake 3.22.1, and the matching Unity editor with Android Build Support. Set `JAVA_HOME`, `ANDROID_HOME`, and `UNITY_EDITOR`, plus `sdk.dir` in local.properties if your IDE needs it. AGP 9.0/Gradle 9.1 matches the Unity version's generated build; Kotlin is provided by AGP.

## Why the Python API remains

`circuit-api/` chooses parts with the model provider, derives hole assignments deterministically, and validates electrical connectivity before returning placement-v3 JSON. The model never invents XYZ positions. Keeping this small service avoids bundling a shared provider secret in the APK and duplicating the electrical validator in two languages. `server.py` is only the local-development host; Vercel runs `api/index.py` directly. There is no Python runtime on Android.

See [API hosting](circuit-api/README.md). The Vercel bundle excludes the React website, legacy static page, development data and handoff assets. Existing `web/`, `static/`, `mock-server/` and `PROTOCOL.md` remain optional historical/reference tools; they are not Android runtime or deployment dependencies. The obsolete native camera/PNG streaming client was removed.

## Unity integration

[Unity project and export](unity/README.md) documents the AR scene, tracked importer, prefab provenance, calibration and device checks. Models and metadata from the team's Unity branch are preserved. Their geometry is reused as schematic artwork with exact generated lead guides; unmeasured prefab anchors are not treated as verified physical pins. The Uno is a labeled schematic proxy because the team branch has no Uno prefab.

The native app atomically saves the last valid circuit. On AR entry it writes the selected placement JSON to private app storage, then launches `ArViewerActivity`. The optional Unity handoff remains available for the Unity project, but it is not used by the native viewer launch path.

## Checks and current acceptance

```sh
cd circuit-api
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m circuit.export
cd ..
python3 scripts/sync-mobile-assets.py
./scripts/build-android.sh
./scripts/test-unity.sh
source scripts/android-env.sh
./gradlew :app:connectedDebugAndroidTest  # running emulator or USB device
```

Verified here: 59 backend tests, 8 Android contract tests, 3 Android emulator flow tests, 5 Unity EditMode tests, full ARM64 Unity library export, embedded Android APK assembly and lint, and a Unity scene render with the bundled component meshes. The emulator reaches Unity’s explicit unsupported-AR screen with the reviewed circuit loaded. Physical camera tracking, calibration accuracy, and repeated AR entry/exit require acceptance on an ARCore phone. No hosted API deployment was created in this session. `physicalVerified=false` is intentional.

The emulator network tests use a local fake provider response; they do not spend API credits. A real generation requires a configured provider on your deployed API. After installing, open **Settings**, enter that API origin, generate a circuit, review its steps, then choose **Show beside my board** and allow the camera. In the viewer, load the generated self-contained GLB URL and calibrate the physical breadboard.
