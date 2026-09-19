import { WebSocketServer } from "ws";

const port = Number.parseInt(process.env.PORT ?? "8080", 10);
const server = new WebSocketServer({ port, path: "/ar" });
const lastPoseLogBySession = new Map();

server.on("connection", (socket, request) => {
  const clientAddress = request.socket.remoteAddress ?? "unknown client";
  console.log(`Phone connected from ${clientAddress}`);

  socket.on("message", (data) => {
    let message;
    try {
      message = JSON.parse(data.toString());
    } catch {
      socket.send(JSON.stringify({ type: "status", message: "Invalid JSON received." }));
      return;
    }

    const sessionId = message.sessionId ?? "unknown-session";
    switch (message.type) {
      case "hello":
        console.log(`[${sessionId}] viewer connected`);
        sendStatus(socket, sessionId, "Laptop connected. Ready to calibrate.");
        break;
      case "calibration":
        console.log(`[${sessionId}] calibration received`, message.boardFrame);
        sendStatus(socket, sessionId, "Calibration received. Sending camera poses to Unity is active.");
        break;
      case "camera_pose":
        logCameraPose(sessionId, message);
        break;
      default:
        console.log(`[${sessionId}] ignored message type: ${message.type}`);
    }
  });

  socket.on("close", () => console.log(`Phone disconnected: ${clientAddress}`));
  socket.on("error", (error) => console.warn(`Socket error for ${clientAddress}: ${error.message}`));
});

server.on("listening", () => {
  console.log(`Mock server running at ws://0.0.0.0:${port}/ar`);
  console.log("Use the laptop's Wi-Fi IPv4 address in the Android app, not 0.0.0.0.");
});

function sendStatus(socket, sessionId, message) {
  socket.send(JSON.stringify({ type: "status", sessionId, message }));
}

function logCameraPose(sessionId, message) {
  const now = Date.now();
  const lastLog = lastPoseLogBySession.get(sessionId) ?? 0;
  if (now - lastLog < 1000) return;
  lastPoseLogBySession.set(sessionId, now);
  const [x, y, z] = message.translationMeters ?? [];
  console.log(`[${sessionId}] camera pose: ${x?.toFixed(2)}, ${y?.toFixed(2)}, ${z?.toFixed(2)}`);
}
