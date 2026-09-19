# Mock laptop server

This server verifies that the Android viewer can connect, calibrate, and send camera poses before the Unity overlay service exists.

```powershell
npm install
npm start
```

It listens on `ws://0.0.0.0:8080/ar`. In the Android app, use the laptop's Wi-Fi IPv4 address instead of `0.0.0.0`, for example `ws://192.168.1.42:8080/ar`.

The server deliberately does not send `overlay_frame` images. The Unity/laptop implementation replaces this mock with the protocol in `../PROTOCOL.md`.
