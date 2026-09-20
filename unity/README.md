## Native Android mesh export (current app path)

The merged native AR viewer reads the selected placement and builds its complete scene on the phone. Run `../scripts/export-native-models.sh` from this directory (or `./scripts/export-native-models.sh` from the repository root) after editing Led, Resistor, or Pushbutton prefabs. The export preserves submeshes and material colors, bakes prefab transforms, converts to right-handed coordinates, and normalizes each artwork uniformly. Android generates exact lead guides from the circuit map; model geometry is still schematic, not certified physical pin geometry. `components.json` is bundled with the app, so no model URL or Unity runtime is required for this native path.

The sections below describe the retained optional embedded Unity renderer.

# Circuit AR on Android

This project embeds the team's Unity meshes in the Android app through **Unity as a Library**. It uses Unity **6000.6.2f1**, AR Foundation **6.6.2**, and the ARCore provider. The Android activity sends the current placement through private app storage and JNI; the Unity scene reads it on startup. There is no laptop renderer, websocket, session polling, or provider key in Unity.

From the repository root, with an activated editor:

```sh
./scripts/test-unity.sh
./scripts/export-unity.sh
```

`Circuit > Prepare Android scene` generates the scene, prefab catalog, AR camera/input, horizontal plane detection, and ARCore loader configuration. `Circuit > Export Android library` exports `unity-export/`; the native Gradle host imports its `unityLibrary` and `.androidlib` modules. Run the shell script to also transfer Unity's generated build properties and build the APK. The editor Android module, JDK, SDK, NDK r27c, CMake and Gradle are required. On this machine, `scripts/android-env.sh` selects the installed workspace tools. Elsewhere, set `UNITY_EDITOR`, `JAVA_HOME`, and `ANDROID_HOME`.

## Runtime contract

`Assets/CircuitXR/Runtime/` is the tracked source. `circuit-api/circuit/export.py` packages those importer files in the handoff ZIP. `scripts/sync-mobile-assets.py` copies the exported map and explicit lessons into Android and Unity. Never edit generated map coordinates to make a mesh look aligned.

`CircuitBuilder` checks the version, explicit validation result, board model/map, part IDs/types, terminal maps, occupied lead holes, and every wire endpoint. It builds under an inactive temporary root and swaps only after success. Wire coordinates are local; translating or rotating BoardRoot moves everything together.

The supplied component prefabs and original FBX/material bytes came from `origin/unity` (see `ASSET_PROVENANCE.md`). Their meshes are reused as **schematic bodies**, normalized with one uniform scale from mesh bounds. Separate insertion markers and leads connect to the exact semantic holes. They are not calibrated physical pin meshes. In particular, Pushbutton has only Anode/Cathode/Output markers, not the four contract terminals. The Arduino is a labeled schematic proxy because the branch contains no Uno prefab. The virtual breadboard is generated from the authoritative map; the original FBX is retained for later measured mesh calibration.

## Placement

Lay the reference breadboard flat and keep it still. Scan until ARCore tracks a horizontal plane. Tap **A1, J1, A63**, using the labeled hole centers (not outer corners). The calibrator checks distances and right angles without stretching the map. A Unity ARAnchor holds the measured pose; the virtual circuit initially appears 15 cm along +X, beside the physical board. Move side changes only BoardRoot's offset. Reposition discards the old anchor and starts calibration again. Tracking loss hides the virtual board until tracking recovers.

AR Foundation already uses Unity coordinates. `+X = A1→J1`, `+Z = A1→A63`, `+Y = cross(Z,X)`. Do not apply the retired Android-camera reflection again.

## Verification

The five EditMode tests pass, the ARM64 library exports, and the full Android host compiles with it. A rendered preview confirms bundled artwork and shaders. Test the installed APK on a physical ARCore phone before claiming physical AR acceptance. Confirm camera permission/denial, tracking loss, all three reference points, positive height, side switching, repeated entry/exit, and retained circuit after a failed generation. The physical breadboard is not automatically recognized, and `physicalVerified=false` remains intentional.
