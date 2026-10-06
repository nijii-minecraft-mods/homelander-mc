#!/usr/bin/env bash
set -euo pipefail

for command in docker grep sed tr python3 mkdir sleep; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done

image=${1:-homelander-powers:ci}
name="homelander-ci-${GITHUB_RUN_ID:-local}-$$"
mkdir -p build/container-check
cleanup() {
  docker logs "$name" > build/container-check/server.log 2>&1 || true
  docker rm -fv "$name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The temporary server is reachable only through a random loopback port. RCON is not published.
docker run -d --name "$name" -p 127.0.0.1::25565 \
  -e EULA=TRUE -e ONLINE_MODE=FALSE -e MEMORY=2G \
  -e LEVEL=homelander-ci -e VIEW_DISTANCE=3 -e SIMULATION_DISTANCE=3 \
  "$image" > build/container-check/container-id.txt

ready=false
for ((attempt=0; attempt<120; attempt++)); do
  running=$(docker inspect -f '{{.State.Running}}' "$name")
  [[ "$running" == true ]] || { docker logs "$name"; exit 1; }
  if docker logs "$name" 2>&1 | grep -F 'Done (' >/dev/null; then ready=true; break; fi
  sleep 5
done
[[ "$ready" == true ]] || { echo 'Minecraft did not become ready within 10 minutes' >&2; exit 1; }

docker exec "$name" mc-health
properties=$(docker exec "$name" cat /data/server.properties)
printf '%s\n' "$properties" | tr -d '\r' | grep -Fx 'online-mode=false' >/dev/null
printf '%s\n' "$properties" | tr -d '\r' | grep -Fx 'server-port=25565' >/dev/null
docker exec "$name" cat /data/eula.txt | tr -d '\r' | grep -Fx 'eula=true' >/dev/null
docker exec "$name" test -f /data/mods/homelander-powers.jar
docker exec "$name" sh -c 'ls /data/mods/fabric-api*.jar' >/dev/null
docker exec "$name" sh -c 'ls /data/mods/polymer*.jar' >/dev/null

logs=$(docker logs "$name" 2>&1)
printf '%s\n' "$logs" | grep -F 'homelander' >/dev/null
if printf '%s\n' "$logs" | grep -Ei 'failed to load|mixin apply failed|crash report|encountered an unexpected exception' >/dev/null; then
  echo 'Server startup error detected' >&2
  exit 1
fi

port=$(docker port "$name" 25565/tcp | sed 's/.*://')
python3 scripts/check-server-status.py "$port" > build/container-check/status.json
docker logs "$name" > build/container-check/server.log 2>&1
echo 'PASS: Fabric 1.21.1 startup, mod/dependency loading, offline mode, EULA and Minecraft protocol status'
