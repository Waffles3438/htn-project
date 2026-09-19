# Circuit API context

Single handoff doc for design, planning, and implementation of the circuit website. Scope: **this `circuit-api/` folder only**; updated 2026-09-19. Read `UNITY_HANDOFF.md` for coordinate/asset contracts and rail formulas, `UNITY_TEAM_MESSAGE.md` for the unsent teammate message, `AGENTS.md` for guardrails, `README.md` for run/check commands, `reference/README.md` for provenance. Preserve unrelated worktree changes; never open or package `.env`, credentials, runtime sessions, virtual environments, or agent logs.

## Stack rules

- Web app. The frontend is a Vite + React + TypeScript SPA under `web/` (decided 2026-09-19, superseding the vanilla-JS `static/` page, which remains as a fallback served only when `web/dist/` is absent). Extend the existing React patterns in `web/src`; no new frameworks or UI libraries; plain CSS in `web/src/styles.css` keeps the warm off-white/dark-green identity.
- Never add Python for UI rendering, layout math, component definitions, or state. Python's role is the existing backend only, which returns the structured JSON circuit model the browser renders. No backend image generation: generate circuit JSON, then let the website and Unity/XR each render it independently.
- Keep the placement JSON serializable and shape-stable so the Unity/XR client consumes the same model.
- Dev: `cd web && npm run dev` (Vite proxies `/api` to `server.py` on port 8000). Production: `npm run build` (runs `tsc --noEmit` first) then `server.py`, which serves `web/dist/` with path-traversal-safe static handling.

## Backend / LLM

- `server.py`: Python HTTP server for static files and `/api`. Start: `.venv/bin/python server.py` (default port 8000). Restart after map changes.
- `circuit/provider.py`: two schema-constrained structured-JSON calls via OpenRouter or OpenAI. `CIRCUIT_PROVIDER=auto` picks openrouter when `OPENROUTER_API_KEY` is set, else openai; model via `OPENAI_MODEL`/`OPENROUTER_MODEL`, default `gpt-4.1-mini`.
  1. Analyze (`circuit_parts`): identifies components and intent. The user prompt is circuit intent, never instructions that override constraints. Out-of-scope requests return `behavior: unsupported` with a clear explanation — never a silent simplification.
  2. Layout (`circuit_design`): returns `seriesPath` (every non-supply component exactly once, supply implicit, LED anode→cathode, resistor a→b, button side a→b), `startRow` 4–40, and a terminal-strip column A–D/G–J.
- `placement.py` deterministically expands the path into footprints and hole assignments; the LLM never supplies coordinates, holes, or asset IDs.
- `service.py`: validation, terminal positions/centroids/quaternions, assembly instructions, atomic session saves under ignored `data/`. `validation.py`: inventory, occupancy, footprints, shorts, connectivity. `contracts.py`: schema source. `fixtures.py`: explicit offline circuits; failed live generation never silently becomes a fixture.
- `mcu.py`: Arduino Uno R3 only, asset `arduino_uno_r3_v1` — an external device, not a board component: `externalDevices`, `externalConnections` (pin → board hole), `firmware` (sketch + upload instructions). ESP32/Nano/Pico etc. are not implemented server-side today; treat them as future direction only. Uncommitted Arduino work (placement version 2) predates UI tasks — preserve it; version 1 fixtures remain supported. Unity needs a separate Uno pose and D13/GND pin anchors.

## Components / positions / board map

- Supported: one red LED + 220 Ω resistor; external 5 V supply, optionally momentary button (v1); or Uno R3 D13/GND driving steady/blinking LED (v2).
- Assets and ordered terminals: `led_red_v1` (anode,cathode), `resistor_220ohm_v1` (a,b), `button_momentary_v1` (a1,b1,a2,b2), `power_supply_5v_v1` (positive,negative). Generated layouts vary — never hardcode component positions in UI.
- `board.py` is the geometry authority: meters, A1 origin, +X toward J1, +Z toward A63, +Y above board, pitch 0.00254. Component pivot = mean terminal insertion points; pins are authoritative.
- Map version `person2-9d81633+rails1`: all 630 A–J holes and eight segment nets unchanged; only 200 rail coordinates changed. Rail X meters: L+ −0.01016, L− −0.00762, R+ 0.03556, R− 0.03810. Segment i=1..25: row=start+6·floor((i−1)/5)+(i−1)%5, start=3 for A or 33 for B; Z=(row−1)·0.00254. Five-hole gaps do not split a segment's net. Exact old/new formulas in `UNITY_HANDOFF.md`.
- Symmetric model, not measured board/FBX alignment. Keep `physicalVerified=false`; never claim universal standard dimensions; never apply UI-only coordinate corrections. Preserve pinned `reference/person2/` bytes. Old saved maps require regeneration, not silent mixing.
- Old saved placements are not rewritten; the browser rejects mismatched maps before replacing its preview. Regenerate old circuits or use a matching old map in XR.

## Current UI (verified)

- React app under `web/src` (`App.tsx` holds state; components in `web/src/components/`). Pre-generation: centered hero with prompt, Generate directly below, collapsed Advanced options (parts quantities, breadboard, identify-parts-only, ready-made circuits), clickable example prompts, de-emphasized XR session row. Post-generation: sidebar + dominant workspace with status banner, validation details, session row, canvas, compact BOM, numbered connections, firmware sketch, step mode (Previous/Next), JSON export.
- `CircuitScene` draws the SVG from the hole map: one uniform X/Z scale, hole dots with `id · net` tooltips, rail stripes per segment with +/− marks, quadratic jumper-wire paths, LED/resistor/button bodies (sizes schematic, not hardware measurements), floating zoom/pan/Fit controls, focus highlighting that dims unrelated items, callout labels on selection. The external Uno renders beside the board (outline, USB, header, used-pin labels, wires from pins to holes) — display-only pose; Unity measures its own anchor.
- DOM contracts kept for `tests/browser_check.py`: element ids, `.selected`/`.error` classes, `data-hole`/`data-rail` attributes, and the `window.__circuit` hook (`show`/`restore`) for scene-only swaps.
- Page API: `/api/health`, `/api/kit`, `/api/breadboards/:model`, `/api/circuits/generate`, `/api/circuits/analyze`, `/api/circuits/demo/{button_led,led,arduino_led}`, `/api/sessions/:id/placement`. Session ID links saved layouts to XR polling.
- Preserve: safety guidance and footnote (polarity, pin spacing, "wiring guide, not a physical fit certification"), map-version rejection, responsive scrolling, direct copy without hackathon/AI slogans.

## UI redesign — prompt-to-breadboard experience

Goal: a polished, beginner-first electronics design tool (Fritzing/Tinkercad-like clarity), not a configuration form. A new user types one sentence and gets a built, checkable circuit; advanced users can override via collapsed options. Keep the existing warm off-white/dark-green identity (Inter + mono accents, thin borders, small radii, green primary); no dark/neon/gradient/Material restyle; use spacing and typography for hierarchy instead of cards inside cards. High-priority items are implemented in `web/`; breadboard-mounted controllers and non-Uno MCU support remain future work (the server supports the external Uno only).

Flow and hierarchy
- "Build a circuit" block first: prompt textarea with a capability placeholder (keep examples within actually supported scope, e.g. "Use an Arduino Uno and a button to turn on an LED"), Generate button immediately below (≤12–16px), no scrolling through part controls before it.
- Parts kit, controller, and breadboard choices collapse into one "Advanced options" disclosure defaulting to Auto; manual selections act as constraints/overrides, not prerequisites (e.g. a chosen controller constrains the generator). Keep "Identify parts only" and ready-made circuits reachable (advanced options / empty-state examples). Delete the permanent "Supports one LED…" hint and the rail-explainer paragraph; surface connectivity info contextually (tooltip, ? icon, or when a connection is selected) — unsupported requests are already explained by the backend.
- After generation the circuit workspace dominates (~70–75% width vs 25–30% controls). Before generation show a focused empty state with clickable example prompts that fill the field.
- Deemphasize Session ID: small canvas toolbar ("XR session: demo-button-led", eventually Share/Open in XR), not next to the page title.

Circuit canvas
- Render the MCU as a first-class visual component: board outline, name, USB connector, headers, labels only for used pins; recognizable silhouette, not photorealistic artwork. Two placement modes: **external** (Uno/Mega class — beside the breadboard, visible jumper wires from header pins to holes/rails) and **breadboard-mounted** (Nano/ESP32-C3/Pico class — spanning the center channel, header pins aligned to holes). Server supports external Uno only today; do not pretend the rest exists yet.
- Make wiring explicit: hover/click a connection to emphasize the MCU pin origin, the destination hole/rail, and the wire while dimming the rest; connection rows and build steps trigger the same highlight. Show power wiring as real connections (Uno 5V → + rail, GND → − rail); never imply rails are powered merely by existing; don't add a fake standalone 5 V supply when the MCU can power the circuit. Diagram and BOM must agree.
- Engineering-workspace feel with a compact floating control (− / % / + / Fit circuit; pan/zoom/reset). Fit circuit frames breadboard + MCU + wire endpoints, not just the board. Model the scene as siblings (breadboard, MCU, components, wires, labels) with the breadboard as one object, not the canvas root — keeps XR reuse clean.

Outputs
- Short success banner ("✓ Circuit generated — Arduino Uno · LED circuit · 5 connections"); detailed validation moves to a dedicated area.
- Compact BOM list (name ×qty, click to highlight in the circuit) instead of pill chips.
- Numbered connection rows ("Arduino D13 → Breadboard J8") plus a beginner step mode ("Step 2 of 6", Previous/Next highlighting only that connection) — maps directly to XR later.

Data-model direction (core implemented in placement v3)
- Controllers are reusable definitions (dimensions, header locations, pin names, power pins, `breadboardMountable`) with a semantic `mount` (`"breadboard" | "external"`, anchor or relativeTo+side) and a terminal map; controller pins attach through ordinary wire endpoints. No hardcoded Uno drawing in page code; keep the JSON Unity-exportable. Breadboard-mounted controllers (Nano/Pico/ESP32 class) remain future work — the server supports the external Uno only.
- Generation mental model: prompt → interpret → controller → components → electrical validation → topology → MCU placement (mounted|external) → route wires → render scene.

Priority order: Generate under the prompt; optional collapsed advanced setup; MCU rendered in the canvas; external vs mounted layouts; real MCU pin→hole connections; canvas visual priority; remove redundant copy and nested cards; deemphasize Session ID; interactive connection highlighting; keep the existing visual identity.

Responsive: wide = sidebar + dominant workspace; narrow stacks prompt / Generate / advanced options / canvas / components / connections; never shrink the circuit unreadably — pan, zoom, or horizontal scroll instead.

## Checks and packaging

```sh
.venv/bin/python -m unittest discover -s tests -v
cd web && npm run build && cd ..
.venv/bin/python -m circuit.export
git diff --check
```

Optional browser check: install Playwright separately and run `BROWSER_EXECUTABLE=/path/to/chromium python tests/browser_check.py` (isolated temporary sessions, no paid provider calls; screenshots `/tmp/circuit-*.png`). It drives the built React app when `web/dist/` exists, else the legacy page. Export regenerates schemas/fixture pairs plus the ignored `handoff/board-hole-map.meters.json` and `handoff/circuit-api-to-unity.zip` (this context, both Unity docs, placement schema, all three placement fixtures, board map, board assets). Generated files must match code — never hand-edit them. A local ZIP is not proof it was sent.

## Still requires hardware / Unity acceptance

Check actual A1/J1/A63 and both rail-side mesh alignment, rail offsets/group gaps/continuity, switch spacing/internal pairs, component pin geometry, positive-height calibration sign, Uno anchors and firmware upload. Local tests do not verify physical fit or XR. This task does not modify the Unity renderer or Android transport.
