# Android ↔ laptop overlay protocol

Transport: one local-network WebSocket per phone. The Android viewer connects to the URL entered in the app, for example `ws://192.168.1.42:8080/ar`.

Every message belongs to one `sessionId`. The laptop should only route circuit data and overlay frames to the matching phone session.

## Phone → laptop

### `hello`

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "sessionId": "demo-button-led",
  "client": "android-ar-viewer"
}
```

### `calibration`

Sent once after the student taps the breadboard origin, positive X reference, and positive Y reference.

```json
{
  "type": "calibration",
  "sessionId": "demo-button-led",
  "coordinateSystem": "ARCore world space, meters, right-handed",
  "boardFrame": {
    "originMeters": [0.0, 0.0, 0.0],
    "xAxis": [1.0, 0.0, 0.0],
    "yAxis": [0.0, 1.0, 0.0],
    "zAxis": [0.0, 0.0, 1.0],
    "xExtentMeters": 0.166,
    "yExtentMeters": 0.055
  }
}
```

Unity uses this frame to align the digital breadboard origin and pin map to the real board. Validate the basis conversion once with a three-axis test prefab before loading electronic components.

### `camera_pose`

Sent at 10 Hz while calibrated. Unity uses it to position a virtual camera that matches the phone camera.

```json
{
  "type": "camera_pose",
  "sessionId": "demo-button-led",
  "timestampNs": 123456789,
  "translationMeters": [0.0, 0.0, 0.0],
  "rotationQuaternion": [0.0, 0.0, 0.0, 1.0],
  "intrinsics": {
    "focalLengthPixels": [1000.0, 1000.0],
    "principalPointPixels": [540.0, 960.0],
    "imageDimensions": [1080, 1920],
    "viewport": [1080, 1920]
  }
}
```

## Laptop → phone

### `overlay_frame`

Unity renders only virtual parts, wires, and guide highlights into a transparent PNG. The image must be portrait and match the latest reported `viewport`; the Android app draws it over the local camera preview without additional coordinate transforms.

```json
{
  "type": "overlay_frame",
  "sessionId": "demo-button-led",
  "pngBase64": "iVBORw0KGgoAAAANSUhEUg..."
}
```

Send only the newest frame. Begin at 6–10 FPS and reduce PNG resolution if local Wi-Fi latency is noticeable. The laptop must render an alpha background; an opaque black background will hide the physical breadboard.

### `status`

```json
{
  "type": "status",
  "sessionId": "demo-button-led",
  "message": "Unity placement loaded"
}
```
