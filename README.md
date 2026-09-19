# Breadboard AR Viewer (Android)

Native Android companion app for the laptop-hosted Unity breadboard demo. It keeps the physical camera and calibration on the phone, then draws Unity-rendered transparent overlay frames over the ARCore camera preview.

## What it does

1. Connects to the laptop over the same Wi-Fi network with a WebSocket.
2. Starts an ARCore camera session and sends pose/intrinsics at 10 Hz after calibration.
3. Guides the student through three-point breadboard calibration: origin, positive X reference, positive Y reference.
4. Receives transparent PNG frames rendered by Unity and layers them over the live camera view.
5. Lets the student reset calibration when the phone or breadboard moves.

The models from Sketchfab remain in the Unity project on the laptop. The phone receives rendered overlay frames, not Blender or mesh files.

## Run it

1. Open this folder in Android Studio and let Gradle sync.
2. If Android Studio does not find your Android SDK automatically, copy `local.properties.example` to `local.properties` and update `sdk.dir`.
3. Connect an ARCore-capable Android device with USB debugging enabled.
4. Run the `app` configuration once to install the debug APK. USB is only needed for installation/debugging; the final demo uses local Wi-Fi.
5. Start the laptop signalling/overlay service.
6. On the phone, enter `ws://<laptop-LAN-IP>:8080/ar` and a shared session ID, such as `demo-button-led`.
7. Tap **Connect**, then **Calibrate**. Tap the same three physical board references agreed by the Unity team.

The demo manifest allows `ws://` over the local network. Use `wss://` and remove clear-text traffic for anything beyond a controlled demo.

## Laptop contract

See [PROTOCOL.md](PROTOCOL.md). Unity must use the camera pose, intrinsics, and calibration data to render the relevant components into a transparent PNG at the reported phone viewport size. Send each frame back with the `overlay_frame` message.

## Project boundaries

- This is the Android viewer only. It does not call the circuit-design API or import Sketchfab assets.
- It intentionally does not use Unity Remote; Unity Remote is a development preview tool, not the final AR viewer.
- Portrait orientation is fixed for the demo. Both the Android camera and Unity overlay must use the same orientation and viewport dimensions.
