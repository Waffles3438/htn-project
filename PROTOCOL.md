# Android ↔ backend GLB delivery contract

The Android viewer does not maintain a WebSocket connection. It requests a finished
3-D model when the learner taps **Load GLB**.

## Implemented minimal endpoint

The app accepts a direct URL to a GLB. The backend can expose a route such as:

```text
GET /v1/designs/{designId}/ar-model.glb
```

Example request from the Android viewer:

```http
GET /v1/designs/button-led/ar-model.glb HTTP/1.1
Host: api.example.edu
Accept: model/gltf-binary, application/octet-stream
```

Example successful response:

```http
HTTP/1.1 200 OK
Content-Type: model/gltf-binary
Content-Length: 4213890
ETag: "button-led-v3"
Cache-Control: private, max-age=0

<binary GLB bytes>
```

`application/octet-stream` is also accepted. The current app verifies that the
download is a GLB 2.0 file (`glTF` header and matching byte length) and rejects
files larger than 32 MiB before giving them to Filament.

The backend should return normal HTTP errors for unavailable designs:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"No AR model has been generated for this design yet."}
```

There is no browser CORS requirement because this is a native Android HTTP client.
Use HTTPS in production. Plain HTTP is supported only so a laptop on the same LAN
can be used during the demo.

## Model requirements

Send one **self-contained binary glTF 2.0 (`.glb`)** file:

- Embed every texture and binary buffer in the GLB; do not rely on external `.bin`,
  PNG, JPG, or URL resources.
- Keep the file at or below 32 MiB.
- Supply a complete instructional/breadboard-overlay scene in one file. The Android
  app does not yet combine separate component GLBs or interpret placement JSON.
- Use source axes expected by the viewer: **X = long board direction**, **Y = model
  height/up**, **Z = short board direction**.
- The viewer centers the model, scales its X bounds to the physical 165 mm board
  length, rotates it into the AR board frame, and places it just north of the yellow
  physical-board outline. This is the side-by-side learning layout shown in the app.

If the team later wants virtual components inserted at exact breadboard holes rather
than a complete side-by-side overlay, add a versioned placement manifest with a
board coordinate frame and pin map. Raw GLB bytes alone cannot communicate those
semantic pin positions.

## Recommended production manifest

The current app needs only the direct `.glb` URL above. For a versioned production
backend, it is useful to expose a small manifest first:

```text
GET /v1/designs/{designId}/ar-model
```

```json
{
  "schemaVersion": 1,
  "modelId": "button-led-v3",
  "modelUrl": "https://api.example.edu/assets/button-led-v3.glb",
  "sha256": "<64-character lowercase SHA-256>",
  "byteLength": 4213890,
  "assetKind": "breadboard-overlay",
  "coordinateFrame": "breadboard-overlay-v1"
}
```

The app can later fetch this manifest, verify the SHA-256, and poll its ETag for
updates. Until then, a person or the app supplies `modelUrl` directly in the **GLB
model URL** field.

## Update behavior without WebSockets

HTTP is pull-based: the backend does not push a model into the phone. When a new
circuit model is ready, expose it at a new or updated URL and have the learner tap
**Load GLB** again. Automatic refresh can be added later by polling the recommended
manifest with `If-None-Match`.
