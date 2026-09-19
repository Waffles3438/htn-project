import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const port = Number.parseInt(process.env.PORT ?? "8080", 10);
const serverDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(serverDirectory, "..");
const modelFile = resolve(
  repositoryRoot,
  process.env.MODEL_FILE ?? "app/src/main/assets/models/breadboard.glb",
);

const server = createServer(async (request, response) => {
  const path = new URL(request.url ?? "/", "http://localhost").pathname;

  if (request.method === "GET" && path === "/health") {
    sendJson(response, 200, { ok: true });
    return;
  }

  if (request.method === "GET" && path === "/models/breadboard.glb") {
    await sendGlb(request, response);
    return;
  }

  sendJson(response, 404, {
    error: "Use GET /models/breadboard.glb or GET /health.",
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`GLB test server running at http://0.0.0.0:${port}/models/breadboard.glb`);
  console.log("Enter your laptop's LAN IPv4 address in the Android app, not 0.0.0.0.");
  console.log(`Serving ${modelFile}`);
});

async function sendGlb(request, response) {
  try {
    const details = await stat(modelFile);
    const etag = `"${details.size}-${Math.trunc(details.mtimeMs)}"`;
    if (request.headers["if-none-match"] === etag) {
      response.writeHead(304, { ETag: etag });
      response.end();
      return;
    }

    response.writeHead(200, {
      "Content-Type": "model/gltf-binary",
      "Content-Length": details.size,
      "Cache-Control": "no-store",
      ETag: etag,
    });
    createReadStream(modelFile)
      .on("error", (error) => {
        console.error(`Could not read GLB: ${error.message}`);
        if (!response.headersSent) sendJson(response, 500, { error: "Could not read model." });
        else response.destroy(error);
      })
      .pipe(response);
  } catch (error) {
    console.error(`Could not serve GLB: ${error.message}`);
    sendJson(response, 500, { error: "Could not find model file." });
  }
}

function sendJson(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
  });
  response.end(payload);
}
