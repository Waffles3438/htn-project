# Circuit — designer + native Android AR

Type a circuit idea on the phone, review its breadboard schematic and assembly steps, then choose **Show beside my board**. That launches the bundled native ARCore viewer, which tracks the physical breadboard and places the selected circuit beside it. The native renderer assembles the team’s Unity LED, resistor, and button meshes plus wires from the validated placement JSON. The project no longer needs a laptop renderer, a WebSocket, or a Unity export for this path.

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

The app now defaults to the hosted service at **https://circuit-api.vercel.app**. Use Wi-Fi or mobile data; neither USB nor the Mac server is needed. Existing installations can set this origin in **Settings**. New builds also migrate the old localhost USB setting once, while preserving custom service origins.

For optional local development over USB:

```sh
./scripts/run-android-usb.sh
```

That script deliberately builds with `-PcircuitLocalApi=true`, starts/checks the Mac API, forwards port 8000 with `adb reverse`, and installs the debug APK. Only this local mode requires the cable and Mac server. Build normally with `./scripts/build-android.sh` to return to the hosted default. A custom hosted origin can be supplied with `-PcircuitApiUrl=https://your-service.vercel.app`.

For another machine, install JDK 17, Android SDK 35 + build tools 36, NDK r27c (`27.2.12479018`), CMake 3.22.1, and the matching Unity editor with Android Build Support. Set `JAVA_HOME`, `ANDROID_HOME`, and `UNITY_EDITOR`, plus `sdk.dir` in local.properties if your IDE needs it. AGP 9.0/Gradle 9.1 matches the Unity version's generated build; Kotlin is provided by AGP.

## Why the Python API remains

`circuit-api/` chooses parts with the model provider, derives hole assignments deterministically, and validates electrical connectivity before returning placement-v3 JSON. The model never invents XYZ positions. Keeping this small service avoids bundling a shared provider secret in the APK and duplicating the electrical validator in two languages. `server.py` is only the local-development host; Vercel runs `api/index.py` directly. There is no Python runtime on Android.

See [API hosting](circuit-api/README.md). The Vercel bundle excludes the React website, legacy static page, development data and handoff assets. Existing `web/`, `static/`, `mock-server/` and `PROTOCOL.md` remain optional historical/reference tools; they are not Android runtime or deployment dependencies. The obsolete native camera/PNG streaming client was removed.

## Unity integration

[Unity project and export](unity/README.md) documents the AR scene, tracked importer, prefab provenance, calibration and device checks. Models and metadata from the team's Unity branch are preserved. Their geometry is reused as schematic artwork with exact generated lead guides; unmeasured prefab anchors are not treated as verified physical pins. The Uno is a labeled schematic proxy because the team branch has no Uno prefab.

The native app atomically saves the last valid circuit. On preview/AR entry it writes that exact placement to `ar-circuit.json`. `CircuitGlbBuilder` validates it and creates one self-contained scene from the bundled detailed board, Unity component geometry/materials, and generated leads/wires. Board coordinates remain in meters even when external devices expand the scene bounds. The camera-free preview and AR viewer use the same builder.

After your teammate updates prefabs, run `./scripts/export-native-models.sh` and rebuild Android. The editor exports mesh geometry and source material colors into `app/src/main/assets/models/components.json`; Android does not require Unity running or an exported Unity library for this path. The existing optional embedded Unity integration is retained. When present, its ARCore runtime is shared instead of packaging a conflicting second AAR.

The app sends the complete default kit with each prompt. This also works against older running servers that reject prompt-only requests. `scripts/sync-mobile-assets.py` regenerates the kit from the backend authority.

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

Current verification: Android build and lint, 73 native unit tests (including calibration/tracking tests and circuit-to-GLB regression tests), and Khronos glTF validation of all three generated scenes with zero errors or warnings. Emulator flow tests cover request compatibility, review persistence, failure recovery, and rendered 3D preview. The opt-in live-provider test passed against **https://circuit-api.vercel.app** with **make red led with button**, verified an OpenRouter response rather than a fixture, and rendered the generated circuit through the app’s native 3D screen. Local ADB port forwarding was removed for this check.

Physical tracking and hole alignment still require acceptance on an ARCore phone; `physicalVerified=false` remains intentional. The public API is deployed at https://circuit-api.vercel.app. Hosting smoke tests verify health and validated demo generation without local files or a browser login. Deployments currently use the Vercel CLI; GitHub automatic deployment is not connected.
