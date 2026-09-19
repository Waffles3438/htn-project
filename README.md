# Breadboard AR Viewer (Android)

This is the native Android AR viewer for the breadboard project. It detects the
physical breadboard with the phone camera, places a transparent Filament-rendered
GLB beside it, and keeps that model anchored while the phone moves.

It does **not** use Unity Remote or a WebSocket. The phone pulls a completed
`.glb` file from the backend over normal HTTP(S).

## What it does

1. Starts an ARCore camera session and detects the physical breadboard.
2. Lets the learner tap the detected board rectangle to lock it.
3. Draws the yellow board outline and a 3-D model beside the physical board.
4. Lets the learner enter a direct HTTP(S) `.glb` URL and tap **Load GLB** to
   replace the bundled demonstration model at runtime.
5. Keeps the bundled `breadboard.glb` as an offline fallback if no backend model
   has been loaded.

## Run it

1. Open this folder in Android Studio and let Gradle sync.
2. Connect an ARCore-capable Android phone with USB debugging enabled and run the
   `app` configuration.
3. Start a backend that serves a self-contained `.glb` over HTTP(S).
4. In the app, enter the direct model URL, for example
   `http://192.168.1.42:8080/models/breadboard.glb`, then tap **Load GLB**.
5. Tap **Calibrate**, point the phone at the physical breadboard, and tap its
   detected rectangle.

The phone must be able to reach the backend URL. Use your laptop's LAN IP for a
normal Wi-Fi demo; `localhost` on the phone means the phone itself, not the laptop.
The app currently permits local-network `http://` URLs for development. Use
`https://` for any deployment outside a controlled demo network.

## Quick local GLB server

The included replacement mock server serves the bundled GLB with ordinary HTTP:

```powershell
cd mock-server
npm start
```

Then enter `http://<laptop-LAN-IP>:8080/models/breadboard.glb` in the app. It is
only a test server; your real backend should implement the contract below.

## Backend contract

See [PROTOCOL.md](PROTOCOL.md) for the exact HTTP request/response and model-axis
contract to give the backend and 3-D asset teammates.

## Project boundaries

- The Android app does not call the circuit-design API itself. The backend creates
  or chooses the finished scene GLB, then exposes it at a URL.
- The downloaded GLB must be a binary glTF 2.0 file with all textures and buffers
  embedded. External `.bin` or texture URLs are not supported.
- The app currently treats a downloaded model as one breadboard-sized instructional
  overlay placed north of the detected board. It is not yet a per-component,
  per-breadboard-pin scene protocol.
