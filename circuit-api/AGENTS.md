# Circuit API context

Read `UNITY_HANDOFF.md` for coordinate/asset contracts and `UNITY_TEAM_MESSAGE.md` for the unsent teammate message. `README.md` covers run/check commands; `reference/README.md` covers provenance. Keep these synchronized when shared geometry changes.

## Current work — 2026-09-19

- UI cleanup removes hackathon, provider and promotional copy; keeps practical safety guidance. SVG remains uniformly scaled; readable circles/component bodies are schematic, not measured hardware sizes. Narrow screens scroll inside the board.
- Runtime authority: `circuit/board.py`. Map version `person2-9d81633+rails1` preserves all 630 A–J holes, component/terminal/asset names, meter frame and A1/J1/A63 calibration. Only 200 rail coordinates changed. Exact old/new formulas are in the handoff.
- New rail X meters: L+=−0.01016, L−=−0.00762, R+=0.03556, R−=0.03810. For segment index i=1..25: row=start+6*floor((i−1)/5)+(i−1)%5, start=3 for A or 33 for B; Z=(row−1)*0.00254. Eight segment nets remain unchanged. Five-hole gaps do not split a segment's net.
- This is a symmetric model, not measured board/FBX alignment. Keep `physicalVerified=false`; do not claim universal standard dimensions. Never apply UI-only coordinate corrections. Preserve pinned `reference/person2/` bytes.
- Old saved placements are not rewritten. The browser rejects mismatched maps before replacing its preview. Regenerate old circuits or use a matching old map in XR. Restart the API after map changes.
- Placement v3 (2026-09-19) superseded the coordinate placement: components mount semantically (`mount` terminal maps to `BB1:` addresses), wires carry `from`/`to` endpoints, `nets[]` names electrical groups, and the Uno is an `externalDevices[]` entry with `mount: {"type": "external", "relativeTo": "BB1", "side": "left"}`. No XYZ in placements; renderers derive coordinates from the board map. The earlier uncommitted Arduino work (placement version 2, externalDevices/externalConnections/firmware, Uno asset `arduino_uno_r3_v1`) was folded into v3; version 1 fixtures remain supported. Unity needs a separate Uno pose and D13/GND pin anchors.

## Checks and packaging

From this directory:

```sh
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m circuit.export
git diff --check
```

Optional browser check: install Playwright separately and run `BROWSER_EXECUTABLE=/path/to/chromium python tests/browser_check.py`. It uses isolated temporary sessions and no paid provider calls. Browser outputs are `/tmp/circuit-desktop.png`, `/tmp/circuit-full-board.png`, `/tmp/circuit-mobile.png`.

Export regenerates schemas/fixture pairs and the ignored `handoff/board-hole-map.meters.json` plus `handoff/circuit-api-to-unity.zip`. ZIP includes this context, both Unity docs, placement schema, all three placement fixtures, board map, supplied board assets and the `unity/` C# importer. Never package `.env`, credentials, runtime sessions, virtual environments or agent logs. Generated files must match code. A local ZIP is not proof it was sent.

## Still requires hardware / Unity acceptance

Check actual A1/J1/A63 and both rail-side mesh alignment, rail offsets/group gaps/continuity, switch spacing/internal pairs, component pin geometry, positive-height calibration sign, Uno anchors and firmware upload. Local tests do not verify physical fit or XR. The Android-first migration now modifies the native app and tracked `../unity/` renderer. Unity editor export is verified; physical-device acceptance remains.

## Android-first integration (current)

- Product flow is native Android prompt → schematic/3D preview → native ARCore/Filament, with Unity-authored component meshes exported by `../scripts/export-native-models.sh`. The optional embedded Unity project is retained. The former Kotlin camera/PNG WebSocket client was removed.
- Keep Python generation/electrical validation server-side. Vercel is stateless and never writes session files; Android saves the response. The website and mock-server are optional reference tools, excluded from deployment.
- Importer source is `../unity/Assets/CircuitXR/Runtime/`; export reads it from there. After exporting, run `python ../scripts/sync-mobile-assets.py` to synchronize native/Unity assets.
- Preserve the team's prefab/FBX/material bytes in `../unity/Assets`. Current models are schematic artwork with exact generated hole/lead guides; the button's anchors are incomplete and the Uno is a proxy. Do not claim measured prefab fit.
- Unity AR Foundation supplies Unity coordinates directly. Do not apply the legacy Kotlin calibration reflection. Device flow is A1/J1/A63 on a flat board, anchored beside-board rendering.
