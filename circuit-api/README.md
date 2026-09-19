# Circuit API — Person 1

Your independent folder for **prompt → required components → breadboard holes → validated Unity placement JSON**. Python backend and a small browser control page; no Node, Unity, Android, database, or hosting setup required.

The board, kit, and LED asset metadata are imported from [`person2`](https://github.com/Waffles3438/htn-project/tree/person2/person2/data), pinned at commit `9d81633c65a42059bfc6307aa8fd0ec1554cd114`. The original files are preserved under `reference/person2/` so this folder runs independently of that unmerged branch.

## Start

Python 3.9+:

```sh
cd circuit-api
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
[ -f .env ] || cp .env.example .env
python server.py
```

Open **http://127.0.0.1:8000**. Use **Button + LED** under **Ready-made circuits** immediately, without an API key. The venv and dependencies are already installed on the current laptop.

For live generation through OpenRouter, set `OPENROUTER_API_KEY` and `OPENROUTER_MODEL=openai/gpt-4.1-mini` in `.env`, then restart the server. `CIRCUIT_PROVIDER=auto` selects OpenRouter when its key is present; you can also set it explicitly to `openrouter` or `openai`. Direct OpenAI remains supported through `OPENAI_API_KEY` and `OPENAI_MODEL`. Keys stay server-side and `.env` is Git-ignored. Keep real keys out of `.env.example`. No key is bundled. The fixture is **only** used via an explicitly selected demo endpoint: failed live calls never silently become canned circuits.

For a Unity laptop on the same Wi-Fi, set `HOST=0.0.0.0` and restart, then use `http://<API-laptop-IP>:8000`. This is a local hackathon server without authentication; keep it on the demo network. It does not use the Android mock server's port 8080.

## What is implemented

1. Validate prompt, inventory and `demo_breadboard_v1` selection.
2. Call the configured provider with a strict parts schema to identify required components and intent.
3. Reject missing inventory or unsupported circuits.
4. Ask the model to design the directed series connection order and choose a starting row/column. A deterministic placement engine expands the generated design into fixed component footprints and allocates distinct vacant wire endpoints on the reference board. It does not retrieve a predefined circuit layout.
5. Independently validate schema, inventory, unique hole occupancy, footprints, build order and electrical connectivity in both button states.
6. Calculate positions and rotations from the checked-in hole map. The model never invents coordinates, asset IDs, validation results or assembly instructions.
7. Export `placement.json` and atomically save the last valid circuit per session. Unity can poll that session's endpoint.

Scope: one 5 V source, one red LED, one 220 Ω resistor, jumpers, and optionally a momentary button (placement version 1). The existing Arduino path also supports an Uno R3 driving the external LED from D13/GND, steady or blinking one second high/one second low (version 2 with firmware; no separate supply or button). Latching, motors, multiple LEDs and other values are rejected. This is a limited electrical graph validator, not a general simulator or physical inspection system. Prompt interpretation is model-based. The button-LED path passed a prior live OpenRouter check; additional prompts need evaluation.

OpenRouter uses its [Structured Outputs API](https://openrouter.ai/docs/guides/features/structured-outputs), `response_format.json_schema`, and `provider.require_parameters=true`. Direct OpenAI integration follows the official [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs): `POST /v1/responses`, `text.format.type=json_schema`, `strict=true`, and `additionalProperties=false`. Refusals, incomplete output, invalid JSON and provider failures return errors without placement data.

## API

| Method | Path | Result |
|---|---|---|
| GET | `/api/health` | Server status and whether a key is configured; never the key |
| GET | `/api/kit` | Hardware owner's kit, with `jumper` normalized to `jumper_wire` |
| GET | `/api/breadboards/demo_breadboard_v1` | All 830 hole positions in meters, conductivity groups, references and assumptions |
| POST | `/api/circuits/analyze` | AI parts list only; does not publish a placement |
| POST | `/api/circuits/generate` | Two-stage AI generation; returns the placement object directly |
| POST | `/api/circuits/demo/button_led` | Explicit validated offline button lesson |
| POST | `/api/circuits/demo/led` | Explicit validated offline always-on LED lesson |
| POST | `/api/circuits/demo/arduino_led` | Uno external LED, version 2 with firmware |
| GET | `/api/sessions/{sessionId}/placement` | Last valid placement for that session, including after restart |
| GET | `/api/schema/placement` | Unity handoff JSON Schema |

Every POST accepts the same request shape:

```json
{
  "prompt": "Turn on an LED when a button is pressed",
  "availableParts": [
    {"type": "power_supply", "value": "5V", "quantity": 1},
    {"type": "led", "value": "red", "quantity": 1},
    {"type": "resistor", "value": "220ohm", "quantity": 1},
    {"type": "button", "value": "momentary", "quantity": 1},
    {"type": "jumper_wire", "value": "male-male", "quantity": 10}
  ],
  "breadboardModel": "demo_breadboard_v1",
  "sessionId": "demo-button-led"
}
```

`jumper` is also accepted for compatibility with Person 2's original inventory. Supported value spelling variants include `5 V` and `220 Ω`. Quantities must be integers. Session IDs allow 1–64 letters, digits, `_` and `-`. Demo endpoints explicitly replace the request prompt with their named lesson and mark the result `source: "fixture"`.

```sh
# Offline end-to-end handoff:
curl -fsS http://127.0.0.1:8000/api/circuits/demo/button_led \
  -H 'Content-Type: application/json' \
  --data-binary @fixtures/button_led.request.json > /tmp/placement.json

# Live AI: same body, different endpoint, API key required.
curl -fsS http://127.0.0.1:8000/api/circuits/generate \
  -H 'Content-Type: application/json' \
  --data-binary @fixtures/button_led.request.json
```

Errors contain **only** `{ "error": { "code", "message", "details" } }`; there is no candidate placement. Examples: 400 malformed request, 422 missing parts/invalid circuit, 429 generation already running, 502 provider error, 503 missing key/network error. Generation is serialized. A failed request preserves the previous session file and the browser explicitly labels its retained preview as the last valid circuit. Persistence is local to `data/{sessionId}.placement.json`, ignored by Git.

## Hand this to the Unity teammate

- [UNITY_TEAM_MESSAGE.md](UNITY_TEAM_MESSAGE.md): a ready-to-send integration message and the details we need from the Unity owner.
- [UNITY_HANDOFF.md](UNITY_HANDOFF.md): field names, units, axes, rotations, asset requirements and Android calibration conversion.
- [fixtures/button_led.placement.json](fixtures/button_led.placement.json): complete validated button lesson.
- [fixtures/led.placement.json](fixtures/led.placement.json): complete always-on lesson.
- [schemas/placement.schema.json](schemas/placement.schema.json): the contract.
- [reference/assets/breadboard.fbx](reference/assets/breadboard.fbx): supplied breadboard mesh; import and alignment notes are in the Unity handoff.
- Poll `GET /api/sessions/demo-button-led/placement` and keep the last successfully parsed, validated layout when the request fails.

## Hardware data still to confirm

The source supplies coordinates but no conductivity or footprint specifications. This implementation records these assumptions explicitly and sets `physicalVerified: false`:

- A–E share a conductor per row, F–J share another. Rails with the same prefix (`L+A`, `L+B`, etc.) are joined within that segment; different segments are isolated.
- The four-pin button spans E/F and three row intervals: 7.62 × 7.62 mm. Same-side `a1/a2` and `b1/b2` are internally joined. **Confirm against the actual switch or change the footprint rule before assembly.**
- The raw board declares dimensions 165 × 10 × 55 mm, but its hole extents are X = −5.08…43.18 mm and Z = 0…157.48 mm. Terminal coordinates are preserved; modeled rail corrections change runtime X extents to −10.16…38.10 mm. Nominal dimensions are not used to place components or scale the board mesh.
- Only LED asset metadata was uploaded; no GLB files are in that branch. Other prefab IDs and normalized pivots are specified in the handoff document, pending the assets owner.

## Verification and GitHub

### UI and rail update context

The browser now uses direct task labels instead of hackathon/provider slogans, distinguishes ready-made circuits, and renders readable symmetric rails with matching marker sizes and uniform X/Z scale. Mobile scrolling stays within the board. Physical safety guidance remains visible; circuit checks do not certify hardware fit.

Current map: `person2-9d81633+rails1`. All 630 terminal coordinates and component/asset names are unchanged; only 200 rail coordinates moved. This is a modeled correction, **not physical measurement**. See `UNITY_HANDOFF.md` for formulas, version 2 Arduino fields, names and migration. Old saved sessions are not rewritten; regenerate them or retain their old map. The browser refuses mismatched maps. Restart a running API to load the new geometry.

`python -m circuit.export` refreshes the schemas, three fixture pairs, `handoff/board-hole-map.meters.json`, and `handoff/circuit-api-to-unity.zip`. The ZIP contains docs/context, placement schema, all three placement fixtures, map and board assets; it excludes secrets and runtime data. Packaging does not send it to the teammate. `AGENTS.md` preserves the context for future agents.

Optional real-browser regression check (install Playwright separately; it is not a runtime dependency):

```sh
BROWSER_EXECUTABLE="/path/to/chromium" python tests/browser_check.py
```

It starts an isolated server with temporary session storage and exercises all three circuits, focus, map geometry/cropping, export, saved/old sessions, failure preservation, parts-only rendering (mock provider response), and mobile scrolling. Screenshots are written to `/tmp/circuit-{desktop,full-board,mobile}.png`. No paid provider calls are made by this check.

```sh
python -m unittest discover -s tests -v
python -m circuit.export
```

Tests cover schema failures, actual source-coordinate conversion, split rails, unsafe shorts including shorts only while pressed, reversed/unprotected LEDs, bypassed resistors/buttons, missing components/wires, HTTP responses, two-stage provider orchestration, and persistence across a failed generation/server restart. Unit-test provider calls are mocked. The button-LED design was also checked live through OpenRouter. Unity rendering and real hardware still require separate integration checks.

The work is isolated on branch `feat/circuit-api` in the existing `Waffles3438/htn-project` repository. `.github/workflows/circuit-api.yml` runs tests and checks that exported schemas/fixtures stay in sync on GitHub. The Android code and mock server are unchanged. Keep further circuit work on this feature branch rather than `main`. The local `.env`, runtime data, virtual environment and generated handoff ZIP are ignored by Git.

```sh
git add circuit-api .github/workflows/circuit-api.yml
git commit -m "Add prompt-to-breadboard circuit API and Unity contract"
git push -u origin feat/circuit-api
```
