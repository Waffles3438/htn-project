# Circuit API → Unity

The Android app receives a validated placement directly from `POST /api/circuits/generate`, draws its schematic, and opens an embedded Unity activity with the same JSON. The renderer now lives in the tracked [`../unity/`](../unity/README.md) project. No session polling or laptop overlay stream is part of the phone flow.

**Current verification:** Full Unity ARM64 export and embedded Android APK assembly pass, along with 59 backend tests, 8 native contract tests, 3 Android emulator flow tests, and 5 Unity EditMode tests. The native schematic and rendered Unity artwork have been visually checked. Physical AR acceptance is still pending on an ARCore phone. A build made without a Unity export is explicitly a designer preview.

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
| LED | anode BB1:A15; cathode BB1:A16 |
| 220 Ω resistor | a BB1:B12; b BB1:B15 |
| Button | a1 BB1:E8; b1 BB1:F8; a2 BB1:E11; b2 BB1:F11 |
| External 5 V supply markers | positive BB1:J1; negative BB1:J2 |
| Red jumper | BB1:I1 → BB1:D8 |
| Yellow jumper | BB1:G8 → BB1:C12 |
| Black jumper | BB1:C16 → BB1:I2 |

Moving or rotating `BoardRoot` must keep all pin and wire endpoints attached to the same board holes. Reimporting the same placement should replace the old circuit, not duplicate it. An invalid/missing new response should keep the last valid display. These checks are the first renderer acceptance test; physical alignment comes after this local scene works.

### Beside-board versus aligned-overlay mode

Both modes use identical circuit JSON. A virtual board beside the physical one uses a user-selected root pose, optionally offset from the calibrated board pose. A direct overlay uses the calibrated pose without that offset. Apply this placement only at `BoardRoot`; do not bake a demonstration offset into component positions or modify the reference hole map. In overlay mode, the breadboard mesh may be hidden while component and hole guides remain visible. The mobile implementation uses beside-board mode; aligned overlay remains a future measured calibration option.

## Placement v3 — semantic contract (2026-09-19)

Placement version 3 is the canonical, renderer-independent circuit description: it names what connects where and carries no coordinates. Coordinates are derived data that each renderer computes from the board map and component definitions. The same JSON must produce the same electrical circuit in the website and in XR.

- `components[]`: `id`, `type`, `value`, `assetId`, `buildStep`, and a semantic `mount`. Breadboard-mounted parts use `mount: {"type": "breadboard", "board": "BB1", "terminals": {"anode": "BB1:A15", ...}}` — a map from stable terminal id to board address. There is no `position`, `rotation`, or per-terminal `position` anymore. Footprint-based mounting is reserved in the format for breadboard-compatible controllers (Nano/Pico/ESP32 class): such a mount may instead carry `anchor` + `orientation` (for example `{"anchor": "BB1:E12", "orientation": "north"}`), with the controller definition deriving the remaining pin addresses. The current generator emits explicit terminal maps only.
- `jumperWires[]`: `id`, `from`, `to`, `color`, `buildStep`. Endpoints are semantic: board addresses (`BB1:I1`) or component terminals (`mcu_1:D13`). There is no `startPosition`/`endPosition`.
- `externalDevices[]` (Uno only): `id`, `type`, `model`, `assetId`, and `mount: {"type": "external", "relativeTo": "BB1", "side": "left"}`. Unity chooses the actual pose from its own layout rules and measured pin anchors; never expect an XYZ pose.
- `nets[]`: named electrical groups of terminal endpoints, derived by the API from mounts, wires and internal component joins — for example `{"id": "GND", "members": ["led_1:cathode", "mcu_1:GND"]}`. Naming is deterministic: power nets are `VCC`/`GND`, role nets are `LED_SIGNAL`/`BUTTON_SIGNAL`, remaining nets are `SIGNAL_n`. Nets are sufficient to re-validate connectivity without rendering.
- `breadboard` is now only `{model, holeMapVersion, physicalVerified}`; the coordinate frame and calibration references live in `board-hole-map.meters.json` (which also carries the board `id` the addresses use).

Semantic address grammar — never coordinates:

- `BB1:<hole>` for terminal-strip holes: `BB1:A1` … `BB1:J63`.
- `BB1:RAIL:<L|R>:<+|->:<A|B>:<index>` for one rail hole, where index is the 1-based hole index inside the segment: `BB1:RAIL:L:+:A:12` is hole `L+A12`. `BB1:RAIL:L:+:A` (without index) names a whole segment net.
- `<componentId>:<terminalId>` for component terminals: `led_1:anode`, `resistor_1:b`, `mcu_1:D13`. Terminal ids are stable between the website and Unity.

Only display a newly fetched layout when `version == 3`, `breadboardModel == "demo_breadboard_v1"`, `sessionId` matches the active session, and `validation.valid == true`. Match `breadboard.holeMapVersion` to the rendered map too. Reject other versions — the API itself rejects old saved sessions with `OUTDATED_PLACEMENT`. Preserve the previous circuit and compatible map after HTTP errors and while generating. `source` is `fixture`, `openai`, or `openrouter`; `generatedAt` changes when a new circuit is saved. A polling rate of 1 Hz is sufficient. Parsers should ignore unknown additive fields (Kotlin `ignoreUnknownKeys=true`).

## Tracked mobile importer

Source: `../unity/Assets/CircuitXR/Runtime/`. The handoff ZIP includes its portable circuit model, board-map resolver, prefab catalog and builder. The complete AR runtime, generated-scene editor command and Android export scripts are in `../unity/` and `../scripts/`.

- `CircuitDefinition` requires version 3, the supported board, and an explicit successful validation result.
- `BoardMapResolver` reads `{x,y,z}` position objects from the authoritative map and resolves strip and indexed rail addresses.
- `CircuitBuilder` validates all terminal and wire endpoints before staging a replacement circuit. Every generated object and local-space wire belongs to BoardRoot. Failed imports preserve the previous root.
- The team's LED/resistor/button meshes are preserved and uniformly normalized as **schematic artwork**, with separate exact hole markers and generated leads. No measured physical pin alignment is claimed. The button prefab has only three named anchors, not the four terminals required by the electrical contract.
- The external Uno uses a labeled schematic proxy and display-only D13/GND positions; no Uno model exists in the team's branch. The breadboard is drawn from the map, while the source FBX remains preserved for later calibration.
- `CircuitArController` owns horizontal-surface taps, ARAnchor placement, tracking-loss visibility, left/right placement and cumulative assembly steps. Unity alone owns the camera.
- Legacy `GridMapperResolver` remains separately under `../unity/Assets/Scripts/` for the team's original scene. Its row/column vectors and indexed rail validation are corrected. The mobile AR scene uses the meter map, not the original scene's arbitrary world scale.

Run `../scripts/export-unity.sh` from a licensed installation to create the library and build the native host. No model provider key enters this project or APK.

## Coordinates

### Supplied breadboard mesh

The user-supplied model is now included at [`reference/assets/breadboard.fbx`](reference/assets/breadboard.fbx), with inspected metadata in [`breadboard.metadata.json`](reference/assets/breadboard.metadata.json). This is the breadboard model, separate from the component prefabs below. It has not been imported or visually checked in Unity.

The FBX contains mesh objects named `LP` and `HP`. Both have approximately −90° local X rotation; their embedded local scales are `(812.4294, 100, 45.0953)` and `(200, 200, 200)` respectively. Treat them as unnormalized input. Inspect which representation is intended and whether they are alternatives before rendering both.

Import the chosen mesh under a board wrapper, then align its **actual labeled holes** A1, J1 and A63 to the API reference points. A1–J1 must measure 0.02794 m and A1–A63 must measure 0.15748 m. Check that both distances agree with one uniform scale and verify intermediate holes; matching the model's outer dimensions alone is insufficient. Keep import scale/rotation corrections on the mesh child, and the API coordinate frame on the wrapper. Do not change the API hole coordinates to compensate for FBX import settings.

The accompanying pasted JSON was truncated during C57. All 562 complete hole entries match the pinned GitHub map. The complete GitHub JSON remains the raw source; runtime terminal coordinates are preserved, while rails use the versioned correction below. The raw snapshot and FBX remain untouched.

The raw board uses millimeters. **Every position in the API placement is in meters**, already divided by 1000. Do not divide it again. Do not use the raw `dimensions` for scaling: they conflict with hole extents.

| Axis | Meaning | Reference |
|---|---|---|
| +X | Across columns | A1 → J1 |
| +Y | Above the board | Component height |
| +Z | Along numbered rows | A1 → A63 |

The API declares this as a Unity left-handed local frame, with A1 at `(0,0,0)`. Handedness was not specified in the raw source; the explicit convention here completes that contract. Board-hole positions have Y=0. Calibration references are A1 `(0,0,0)`, J1 `(0.02794,0,0)`, and A63 `(0,0,0.15748)`.

### Deriving coordinates from addresses

The placement carries no coordinates; derive them per renderer:

1. Resolve every board address to a hole position from `board-hole-map.meters.json` (meters, board-local, Y=0).
2. A breadboard-mounted component's pose is the mean of its terminal insertion points; orient the normalized prefab wrapper so local +X runs from the first terminal toward the second and +Y points above the board. Terminal order per type (also the order of each mount's terminal map keys):

| Type | Terminal order | Asset ID |
|---|---|---|
| LED | anode, cathode | `led_red_v1` (existing Person 2 ID) |
| Resistor | a, b | `resistor_220ohm_v1` (prefab required) |
| Button | a1, a2, b1, b2 | `button_momentary_v1` (prefab required) |
| External supply | positive, negative | `power_supply_5v_v1` (connection marker required) |

3. Jumper wires: resolve `from` and `to` (board address → hole position; component terminal → that terminal's resolved position, or the controller's measured pin anchor for external devices) and render a raised curve between them. Raise the curve in +Y and keep insertion endpoints at Y=0.
4. External controllers: `mount.relativeTo`/`side` selects which side of the board the controller sits on; Unity places it from its own layout rules and measured pin anchors.

Do not assume the LED GLB already has this pivot/orientation merely because its asset ID exists. Normalize it once in a wrapper, keep the imported model corrections on its child, then apply the derived transform to the wrapper. For the supplied LED at BB1:A15/BB1:A16 the derived center is `(0,0,0.03683)` with yaw −90° — identical to the old v1 numbers, because the derivation is the same rule the API used to compute them.

Pin positions are authoritative. For resistors, form/render flexible leads to each resolved terminal point; a fixed mesh's pin span will not necessarily fit every generated resistor span. For the external supply, render two lead markers at its resolved terminal positions; the centroid is not an instruction to place the supply body across those two holes.

```csharp
// Resolve semantic addresses with your hole registry, then derive the pose.
Vector3 BoardPoint(string address) { /* address → hole map lookup → board-local meters */ }
var points = component.mount.terminals.Values.Select(BoardPoint).ToList();
part.transform.SetParent(boardRoot, false);
part.transform.localPosition = MeanOf(points);
part.transform.localRotation = Orientation(points[0], points[1]); // +X terminal0 → terminal1, +Y up
// Resolve component.assetId to the normalized prefab wrapper above.
```

Sort components and wires together by `buildStep`. The shared `instructions[]` contains matching IDs and user-facing text. Power connects last. The tracked mobile project implements beside-board rendering. The notes above on normalized prefab pins describe the requirements for future measured mesh alignment, not a claim about the current schematic bodies.

## On-device Unity calibration

The app opens Unity as a Library; **AR Foundation supplies Unity-coordinate hit poses and camera tracking directly**. Tap A1 → J1 → A63 on a flat breadboard. `BoardCalibration` checks the 27.94 mm and 157.48 mm reference distances and an approximately right angle without stretching the map. It computes +X from A1→J1, +Z from A1→A63, and +Y from `cross(Z,X)`, rejecting an inverted normal.

An ARAnchor stores that pose. BoardRoot is a child with an initial +X offset of 0.15 m, placing the virtual circuit beside the physical board. Side switching changes only this root offset. Reposition destroys the old anchor and requires three new taps. Keep the physical board still; this is spatial anchoring, not automatic object recognition.

The former Kotlin ARCore camera and WebSocket/PNG client have been removed. Do **not** apply the legacy `(x,z,-y)` conversion or an additional ARCore-to-Unity reflection in this path. `../PROTOCOL.md` describes only the retired laptop-streaming experiment.

## Known source limitations

### Power rail correction — 2026-09-19

Current `holeMapVersion` is **`person2-9d81633+rails1`**, replacing `person2-9d81633`. This is a symmetric **modeled correction**, not measured hardware or a universal 830-point standard. `physicalVerified=false` remains intentional. All 630 A–J hole coordinates, component IDs, asset IDs, terminal names, frame and A1/J1/A63 calibration references are unchanged. Only the 200 rail-hole positions move:

| Rail | Old X (meters) | New X (meters) |
|---|---:|---:|
| L+ | −0.00508 | −0.01016 |
| L− | −0.00254 | −0.00762 |
| R+ | 0.04064 | 0.03556 |
| R− | 0.04318 | 0.03810 |

For each segment's 1-based hole index `i=1..25`, with pitch `p=0.00254 m` and Y=0:

```text
old A: z = (i - 1) * p
old B: z = (25 + i - 1) * p
new row = start + 6*floor((i-1)/5) + ((i-1) % 5)
          start = 3 for A, 33 for B
new z = (new row - 1) * p
```

Each segment now has five groups of five holes with one empty pitch between groups. A spans terminal rows 3–31 (Z=0.00508…0.07620 m); B spans rows 33–61 (Z=0.08128…0.15240 m). IDs and eight segment nets are unchanged: all 25 holes in a segment remain joined in the electrical model; different segments, sides and polarities remain isolated. Group gaps do not imply additional electrical splits.

**Migration:** old saved `data/*.placement.json` files are not automatically rewritten. Regenerate for the current map or retain the matching old map; never mix rail placements across map versions. The browser rejects mismatched saved maps without replacing its current layout. Current fixture terminals use A–J, so their coordinates are unchanged. Restart the API after geometry changes; it loads the map at process startup.

`python -m circuit.export` refreshes all fixtures/schemas, `handoff/board-hole-map.meters.json` and `handoff/circuit-api-to-unity.zip`. The archive contains this document, the team message, agent context, placement schema, all three placement fixtures, board map, and supplied board assets. This is a local package, not evidence of delivery to the teammate.

**Physical acceptance:** align A1/J1/A63, then inspect both rail sides, first/last holes, group gaps and segment breaks against the actual board and FBX. Measure rail offsets and continuity. The browser uses one uniform scale for X/Z; hole circles and component bodies are schematic markers, not measured aperture/body dimensions. Do not fix discrepancies with UI-only coordinate shifts or by scaling X and Z differently. Unity still needs verified prefab pin geometry and physical XR alignment.

### External controller in placement v3

`fixtures/arduino_led.placement.json` represents an external LED driven by Uno R3 D13/GND through 220 Ω, not its built-in LED. No separate supply is used. The Uno is not a breadboard `components[]` entry:

- `externalDevices[]`: `id` (`mcu_1`), `type: "arduino_uno"`, `model: "uno_r3"`, `assetId: "arduino_uno_r3_v1"`, and `mount: {"type": "external", "relativeTo": "BB1", "side": "left"}`. There is no invented board-local Uno pose and no XYZ anywhere.
- The controller's wires are ordinary `jumperWires[]` entries with component endpoints: `mcu_1:D13 → BB1:C12` (red) and `BB1:C16 → mcu_1:GND` (black). Resolve `mcu_1:*` from the Uno's measured pin anchors and the other side from the board map. Two external leads are counted in inventory.
- `nets[]` includes the controller pins: `GND` contains `mcu_1:GND`; the D13 drive net contains `mcu_1:D13`.
- `firmware`: `filename: "circuit.ino"`, `board: "Arduino Uno R3"`, `language: "arduino"`, `code`, `uploadInstructions`. Supports steady HIGH or one second HIGH/one second LOW; physical upload remains unverified.

Sort components and wires by `buildStep`; instructions reference their IDs. Unity must supply the Uno prefab, choose the body pose from the mount side, and measure its D13/GND pin anchors. Wire with USB disconnected, check polarity, then connect USB and upload.

`physicalVerified=false` is intentional: the source has no real continuity measurements, switch footprint, normalized meshes, or physical board certification. The validator proves connectivity under the documented model, not the real-world build. The button's 7.62 mm square pin arrangement must match the actual kit. The raw source's `placement.json` only demonstrates syntax; use this folder's complete validated fixtures for integration.
