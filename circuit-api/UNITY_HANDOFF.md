# Circuit API → Unity

Start by importing `fixtures/button_led.placement.json`. Then poll `http://<API-laptop-IP>:8000/api/sessions/demo-button-led/placement`. The POST generation response is the same placement object, without a wrapper.

For the coordination message and information needed from the renderer owner, see [UNITY_TEAM_MESSAGE.md](UNITY_TEAM_MESSAGE.md). The API frame below is an implemented proposal; it has not yet been confirmed against the Unity project.

## First rendering check

Use a single root for the board and circuit:

```text
BoardRoot (demo positioning or calibrated pose; local scale 1,1,1)
  BreadboardMesh (FBX import corrections stay here)
  Components (normalized prefab wrappers)
  Wires (endpoints in board-local meters)
  DebugHoleMarkers (same hole coordinates as the circuit)
```

First show the three reference markers and the fixture's terminal/wire endpoint markers. Confirm the markers match the labeled mesh holes before adding component bodies. Then resolve prefab IDs, match each prefab's pins to its terminal markers, and render wires with raised curves. A component body can look centered while its leads miss the holes, so check the pins as well as the center.

The first fixture has these exact assignments:

| Element | Hole assignments |
|---|---|
| LED | anode A15; cathode A16 |
| 220 Ω resistor | a B12; b B15 |
| Button | a1 E8; b1 F8; a2 E11; b2 F11 |
| External 5 V supply markers | positive J1; negative J2 |
| Red jumper | I1 → D8 |
| Yellow jumper | G8 → C12 |
| Black jumper | C16 → I2 |

Moving or rotating `BoardRoot` must keep all pin and wire endpoints attached to the same board holes. Reimporting the same placement should replace the old circuit, not duplicate it. An invalid/missing new response should keep the last valid display. These checks are the first renderer acceptance test; physical alignment comes after this local scene works.

### Beside-board versus aligned-overlay mode

Both modes use identical circuit JSON. A virtual board beside the physical one uses a user-selected root pose, optionally offset from the calibrated board pose. A direct overlay uses the calibrated pose without that offset. Apply this placement only at `BoardRoot`; do not bake a demonstration offset into component positions or modify the reference hole map. In overlay mode, the breadboard mesh may be hidden while component and hole guides remain visible. Confirm the intended demo mode with the team before integrating camera calibration.

## Existing field names preserved

The hardware owner's `person2/data/placement.json` defines `version`, `breadboardModel`, `components`, `components[].assetId`, `terminals[].id`, `terminals[].holeId`, `buildStep`, and `jumperWires` with `fromHole`/`toHole`. These names are unchanged. V1 adds `sessionId`, computed transforms, metadata, validation and instructions. Parsers should ignore unknown additive fields (Kotlin `ignoreUnknownKeys=true`); the Person 2 sample itself is not a complete electrical fixture.

Only display a newly fetched layout when `version == 1`, `breadboardModel == "demo_breadboard_v1"`, `sessionId` matches the active session, and `validation.valid == true`. Preserve the previous circuit after HTTP errors and while generating. `source` is `fixture`, `openai`, or `openrouter`; `generatedAt` changes when a new circuit is saved. A polling rate of 1 Hz is sufficient.

## Coordinates

### Supplied breadboard mesh

The user-supplied model is now included at [`reference/assets/breadboard.fbx`](reference/assets/breadboard.fbx), with inspected metadata in [`breadboard.metadata.json`](reference/assets/breadboard.metadata.json). This is the breadboard model, separate from the component prefabs below. It has not been imported or visually checked in Unity.

The FBX contains mesh objects named `LP` and `HP`. Both have approximately −90° local X rotation; their embedded local scales are `(812.4294, 100, 45.0953)` and `(200, 200, 200)` respectively. Treat them as unnormalized input. Inspect which representation is intended and whether they are alternatives before rendering both.

Import the chosen mesh under a board wrapper, then align its **actual labeled holes** A1, J1 and A63 to the API reference points. A1–J1 must measure 0.02794 m and A1–A63 must measure 0.15748 m. Check that both distances agree with one uniform scale and verify intermediate holes; matching the model's outer dimensions alone is insufficient. Keep import scale/rotation corrections on the mesh child, and the API coordinate frame on the wrapper. Do not change the API hole coordinates to compensate for FBX import settings.

The accompanying pasted JSON was truncated during C57. All 562 complete hole entries match the pinned GitHub map, so the complete 830-hole GitHub JSON remains the geometry source.

The raw board uses millimeters. **Every position in the API placement is in meters**, already divided by 1000. Do not divide it again. Do not use the raw `dimensions` for scaling: they conflict with hole extents.

| Axis | Meaning | Reference |
|---|---|---|
| +X | Across columns | A1 → J1 |
| +Y | Above the board | Component height |
| +Z | Along numbered rows | A1 → A63 |

The API declares this as a Unity left-handed local frame, with A1 at `(0,0,0)`. Handedness was not specified in the raw source; the explicit convention here completes that contract. Board-hole positions have Y=0. Calibration references are A1 `(0,0,0)`, J1 `(0.02794,0,0)`, and A63 `(0,0,0.15748)`.

All `components[].position` values are the mean of their terminal insertion points on the board surface. `rotation` is a Unity quaternion `{x,y,z,w}`. Use a **normalized prefab wrapper** whose pivot is the mean terminal insertion point, local +Y is above the board, and local +X points from terminal 0 toward terminal 1. The JSON orders terminals:

| Type | Terminal order | Asset ID |
|---|---|---|
| LED | anode, cathode | `led_red_v1` (existing Person 2 ID) |
| Resistor | a, b | `resistor_220ohm_v1` (prefab required) |
| Button | a1, b1, a2, b2 | `button_momentary_v1` (prefab required) |
| External supply | positive, negative | `power_supply_5v_v1` (connection marker required) |

Do not assume the LED GLB already has this pivot/orientation merely because its asset ID exists. Normalize it once in a wrapper, keep the imported model corrections on its child, then apply generated transforms to the wrapper. For the supplied LED at A15/A16, center = `(0,0,0.03683)` and quaternion = `(0,-0.70710678,0,0.70710678)`.

Pin positions are authoritative. For resistors, form/render flexible leads to each supplied terminal point; a fixed mesh's pin span will not necessarily fit every generated resistor span. For the external supply, render two lead markers at its terminal positions; the centroid is not an instruction to place the supply body across those two holes. Jumper wire endpoints are `startPosition` and `endPosition`; render a raised curve between them. Raise the curve in +Y and keep its insertion endpoints at Y=0.

```csharp
// After parsing with your chosen JSON library; positions are board-local meters.
part.transform.SetParent(boardRoot, false);
part.transform.localPosition = new Vector3(p.position.x, p.position.y, p.position.z);
part.transform.localRotation = new Quaternion(p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w);
// Resolve p.assetId to the normalized prefab wrapper above.
```

Sort components and wires together by `buildStep`. The shared `instructions[]` contains matching IDs and user-facing text. Power connects last. This folder supplies JSON; it does not modify the Unity renderer, Android viewer, or WebSocket overlay transport.

## Android calibration conversion

The existing Android `PROTOCOL.md` describes a **right-handed** calibration frame with two in-plane axes. Tap **A1, then J1, then A63**, so Android's `boardFrame.xAxis` follows source +X and `boardFrame.yAxis` follows source +Z. Android computes its normal with a cross product; do not apply the placement XYZ directly to that frame.

If `O`, `U`, `V`, `N` are Android's origin, xAxis, yAxis, zAxis, the ARCore-world point for this placement convention is:

```text
world_ARCore = O + x * U + z * V - y * N
```

Equivalently, convert board-local `(x,y,z)` to calibration coordinates `(x,z,-y)` first. This assumes the chosen physical A1/J1/A63 orientation gives the intended +Y above the board. Test it with a positive-height marker, and confirm the sign against the actual board before loading assets. Then apply your existing ARCore-right-handed → Unity-left-handed world conversion **once**, consistently to camera poses and board geometry. Do not apply the reflection twice or pass a reflection matrix to a quaternion constructor. Calibration/camera handling remains the Unity owner's responsibility.

## Known source limitations

`physicalVerified=false` is intentional: the source has no real continuity measurements, switch footprint, normalized meshes, or physical board certification. The validator proves connectivity under the documented model, not the real-world build. The button's 7.62 mm square pin arrangement must match the actual kit. The raw source's `placement.json` only demonstrates syntax; use this folder's complete validated fixtures for integration.
