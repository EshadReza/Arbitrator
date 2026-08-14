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

# A plain `docker build` targets whatever architecture the machine running
# THIS script happens to be — on an Apple Silicon Mac that's linux/arm64,
# silently. Every real lab PC (Windows or Linux, virtually always x86_64) is
# linux/amd64, so an image built on an ARM Mac and shipped as-is forces
# QEMU emulation on every target machine at best ("requested image's
# platform (linux/arm64) does not match the detected host platform") and
# broke compiles outright at worst in practice. Always target linux/amd64
# explicitly unless the deployment target genuinely is ARM (rare enough to
# be an opt-in override, not the default).
PLATFORM="${ARBITRATOR_DOCKER_PLATFORM:-linux/amd64}"

docker buildx build --platform "$PLATFORM" -t "$IMAGE" -f Dockerfile --load .

echo "Built $IMAGE for $PLATFORM"
