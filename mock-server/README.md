# Local GLB test server

This small Node server replaces the former WebSocket mock. It serves one GLB using
ordinary HTTP so the Android viewer can test its runtime model download.

```powershell
npm start
```

It listens at:

```text
http://<laptop-LAN-IP>:8080/models/breadboard.glb
```

Enter that exact URL in the Android app's **GLB model URL** field and tap
**Load GLB**. Do not use `0.0.0.0`; that is only the server bind address.

By default the server exposes the bundled model at
`../app/src/main/assets/models/breadboard.glb`. To serve a different local GLB:

```powershell
$env:MODEL_FILE = 'C:\path\to\your\scene.glb'
npm start
```

The response includes `Content-Type: model/gltf-binary`, `Content-Length`, and an
ETag. No `npm install` is required for the current server because it uses only
Node's built-in modules.
