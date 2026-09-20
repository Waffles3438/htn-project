#!/usr/bin/env bash
# Run a real model-backed circuit flow over USB; provider secrets stay on the Mac.
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
cd "$CIRCUIT_REPO"
mkdir -p build
if ! curl -fsS --max-time 3 http://127.0.0.1:8000/api/health > build/api-health.json; then
  (cd circuit-api && nohup .venv/bin/python server.py > ../build/api-usb.log 2>&1 &)
  for attempt in {1..20}; do
    if curl -fsS --max-time 2 http://127.0.0.1:8000/api/health > build/api-health.json; then break; fi
    sleep 1
  done
fi
python3 - <<'PY'
import json
health=json.load(open('build/api-health.json'))
if not health.get('liveConfigured'):
    raise SystemExit('API is running, but its model provider key is missing. Configure circuit-api/.env and restart it.')
print('Circuit API ready; model provider configured.')
PY
CIRCUIT_DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$CIRCUIT_DEVICES" ]; then
  echo 'Connect the phone by USB, enable USB debugging, and accept the debugging prompt on the phone.'
  exit 1
fi
while IFS= read -r device; do
  adb -s "$device" reverse tcp:8000 tcp:8000
done <<< "$CIRCUIT_DEVICES"
./scripts/build-android.sh -PcircuitLocalApi=true
while IFS= read -r device; do
  adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk
done <<< "$CIRCUIT_DEVICES"
echo 'Installed. Open Circuit. In Settings use http://127.0.0.1:8000 if you previously saved another URL.'
echo 'Keep the Mac API running and the USB cable connected. Try: make red led with button.'
