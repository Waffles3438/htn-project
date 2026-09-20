> Current integration update (unsent): the mobile renderer is now tracked at `../unity/`, with AR Foundation and Unity-as-a-Library export scripts. The older laptop polling/streaming notes below are historical. Full Unity export and Android assembly pass; physical-device validation remains. Team model bytes are preserved; current pin guides are schematic, with exact circuit-map coordinates.

# Message for the Unity teammate

Draft for sharing; not yet sent. Refresh `handoff/circuit-api-to-unity.zip` with `python -m circuit.export` before attaching it.

**2026-09-19 geometry update:** use map `person2-9d81633+rails1`. All 630 A–J coordinates, component/terminal names, asset IDs and calibration references are unchanged. The 200 rail positions now use symmetric X offsets: L+=−0.01016, L−=−0.00762, R+=0.03556, R−=0.03810 meters. Five-hole groups span rows 3–31 in segment A and 33–61 in B; segment nets are unchanged. Exact old/new formulas and migration instructions are in `UNITY_HANDOFF.md`. This is modeled geometry, not a measured physical certification. Please check both rail sides against the FBX and real board.

Check `version == 3` and `breadboard.holeMapVersion` before rendering. Old saved sessions are not migrated automatically: regenerate or use their matching old map. Do not mix versions or compensate with UI-only transforms. Browser hole markers are not physical aperture measurements.

**2026-09-19 placement v3 update:** placements are now semantic — components carry `mount` maps from stable terminal ids to board addresses (`"anode": "BB1:A15"`), wires carry `from`/`to` endpoints, controllers mount externally (`{"type": "external", "relativeTo": "BB1", "side": "left"}`), and `nets[]` names the electrical groups. Placements carry no XYZ; derive every position from `board-hole-map.meters.json` plus your prefab anchors. The ZIP's Arduino fixture connects `mcu_1:D13 → BB1:C12` and `BB1:C16 → mcu_1:GND` through ordinary jumper entries. Unity still provides the Uno pose and measured D13/GND pin anchors. `firmware` includes `circuit.ino`; physical upload is still unverified. Address grammar and derivation rules are in `UNITY_HANDOFF.md`.

Our circuit API now takes a prompt, identifies the required components, assigns breadboard holes, validates the circuit, and exports `placement.json`. Can we integrate against the attached handoff package first, using its fixed button + LED fixture?

**What I'm providing**

- `fixtures/button_led.placement.json`: complete semantic circuit — mounts, terminal ids, board addresses, jumper endpoints, named nets, asset IDs and build steps (no coordinates; derive them from the board map).
- `board-hole-map.meters.json`: the versioned 830-hole runtime map in meters, with the coordinate frame and calibration references.
- `reference/assets/breadboard.fbx`: supplied breadboard model; its embedded scale/rotation still need normalization.
- `schemas/placement.schema.json` and `UNITY_HANDOFF.md`: exact field definitions and rendering/alignment notes.
- `unity/*.cs`: optional drop-in importer (typed v3 models, board-map/GridMapper resolvers, prefab catalog, `CircuitBuilder.Build()` entry point). Adapt freely; keep your existing anchoring authoritative.

**Proposed rendering agreement — please confirm or flag what your renderer already does differently**

Use one `BoardRoot`, with all component wrappers and wires as children. The placement names addresses; you derive board-local meters from the hole map: A1 is the origin, +X points toward J1, +Z toward A63, and +Y above the board. Resolve each `assetId` to one of your prefabs. The resolved terminal positions are the target insertion points; adjust each imported mesh's pivot/rotation/lead geometry inside its wrapper to match them. Avoid moving components independently to compensate for board alignment.

Start by rendering dots at A1, J1 and A63, then dots at every fixture terminal and wire endpoint. Once the dots match the breadboard mesh, add the models and wires. If a model misses the dots, fix its wrapper/pin geometry rather than the hole map.

For a virtual board **beside** the real board, move the whole `BoardRoot` to the chosen position. For an overlay **on** the real board, use the calibrated board pose. The local JSON stays the same in both modes.

**What I need back from you**

1. Unity repo/branch, Unity version, and the script currently loading/placing components, if any.
2. Which component models/prefabs you have: LED, resistor, four-pin button and jumpers. Send their paths/names and any named pin anchors or existing asset registry. We currently have LED metadata, but not those component model files.
3. Whether you can use this local-meter frame, or what your existing origin, axes and units are.
4. Whether the first demo is a board beside the real one or a directly aligned overlay; and whether you already have a board pose/calibration transform.
5. Whether you want to load the sample file first and then poll the API, or already have a different transport.

We also need the hardware owner to confirm the real button's pin spacing and internally connected pairs; the current fixture assumes a 7.62 mm square footprint. We should not adjust the electrical layout based only on how the switch model looks.

**First integration target**

Show the fixture's LED at A15/A16, resistor at B12/B15, button at E8/F8/E11/F11, and supply lead markers at J1/J2. Wires are I1→D8, G8→C12 and C16→I2. Moving/rotating `BoardRoot` should move all of them together. Then connect `GET http://<API-laptop-IP>:8000/api/sessions/demo-button-led/placement` with the shared session `demo-button-led`.

The API files belong to the `feat/circuit-api` branch; the ZIP also contains everything for this first rendering check. Send back your importer and prefab details so we can adapt the integration to your actual project before changing the shared JSON contract.
