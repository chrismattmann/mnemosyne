#!/bin/sh
# Verify the generated stack. Keep it running for inspection after the test.
set -eu
cd "$(dirname "$0")/.."

on_exit() {
  status=$?
  if [ "$status" -ne 0 ]; then
    docker compose ps --all
    docker compose logs --no-color --tail=100
  fi
  exit "$status"
}
trap on_exit EXIT

docker compose up --build --wait --wait-timeout 180
docker compose --profile test build smoke
docker compose --profile test run --rm smoke
docker compose --profile crawler up --build --wait --wait-timeout 180 crawler
docker compose --profile test run --rm smoke crawler
printf '%s\n' 'RADiX verification passed. Open http://localhost:8080/opsui/'
