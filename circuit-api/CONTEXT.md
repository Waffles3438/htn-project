# Circuit website — quick handoff

Scope: **this `circuit-api/` folder only**. Use this for design/planning/implementation; read specific files below rather than the whole repository. Preserve existing worktree changes. Do not open/package `.env` or agent logs.

## Architecture / LLM
- `server.py`: Python HTTP server, static files and `/api` routes. Start: `.venv/bin/python server.py` (default port 8000).
- `circuit/provider.py`: OpenRouter/OpenAI structured JSON. Two calls: identify parts/intent, then choose series order/start row/column. `placement.py` deterministically assigns holes; the LLM never supplies coordinates or asset IDs.
- `service.py`: orchestrates validation, computes terminal positions/centroids/quaternions, instructions, and atomic session saves under ignored `data/`.
- `validation.py`: inventory, occupancy, footprints, shorts and circuit connectivity. `contracts.py`: schema source. `fixtures.py`: explicit offline circuits; failed live generation never silently becomes a fixture.

## Components / positions
- Supported: one red LED + 220 Ω resistor; external 5 V supply, optionally momentary button (v1); or Uno R3 D13/GND driving steady/blinking LED (v2).
- Assets / ordered terminals: `led_red_v1` (anode,cathode), `resistor_220ohm_v1` (a,b), `button_momentary_v1` (a1,b1,a2,b2), `power_supply_5v_v1` (positive,negative).
- Button fixture: LED A15/A16; resistor B12/B15; button E8/F8/E11/F11; supply J1/J2. Generated layouts vary—never hardcode these positions in UI.
- `mcu.py`: Uno asset `arduino_uno_r3_v1` in `externalDevices`, not board components; `externalConnections` supplies board endpoints; `firmware` supplies sketch. Unity provides separate Uno pose/pin anchors.
- `board.py` is geometry authority: meters, A1 origin, +X toward J1, +Z toward A63, +Y above board; pitch .00254. Component pivot = mean terminal insertion points; pins are authoritative.
- Map `person2-9d81633+rails1`: symmetric modeled rails; all A–J coordinates unchanged. Keep uniform X/Z scale and `physicalVerified=false`. Do not alter raw `reference/person2/`. Old saved maps require regeneration, not silent mixing. Exact rail formulas/rotations: `UNITY_HANDOFF.md`.

## UI / workflow
- Plain HTML/CSS/JS: `static/index.html`, `style.css`, `app.js`; no frontend build step.
- Prompt + inventory → generate/analyze, or load ready-made circuit → SVG board, selectable connections/steps, JSON/sketch download. Session ID links saved layouts to XR polling.
- Keep copy direct, no hackathon/AI slogans. Preserve safety guidance, responsive scrolling, polarity labels and map-version checks. Marker/body sizes are schematic, not hardware measurements.

## Verify / share
```sh
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m circuit.export
git diff --check
```
Optional Playwright: `BROWSER_EXECUTABLE=/path/to/chromium python tests/browser_check.py`.
Export refreshes schemas, fixtures, map and ignored `handoff/circuit-api-to-unity.zip`; don't hand-edit generated JSON. Prior verification: 46 tests + browser checks passed. Physical/XR alignment remains unverified. See `AGENTS.md` for ongoing guardrails and `UNITY_TEAM_MESSAGE.md` for the unsent handoff.
