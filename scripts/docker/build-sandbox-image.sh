#!/usr/bin/env bash
# scripts/docker/build-sandbox-image.sh — owner: Mahir (rules.md Rule 1)
#
# Builds the judge's sandbox image. Run once per machine (and again whenever
# Dockerfile changes) before starting the server — SandboxExecutor refuses to
# judge anything if the image named by arbitrator.judge.docker-image
# (default: arbitrator-judge:latest) isn't present.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

IMAGE="${ARBITRATOR_DOCKER_IMAGE:-arbitrator-judge:latest}"

docker build -t "$IMAGE" -f Dockerfile .

echo "Built $IMAGE"
