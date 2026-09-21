# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
# scripts/docker/Dockerfile — owner: Mahir (rules.md Rule 1)
#
# The judge's sandbox image (FR-10, NFR-S03/S04). One shared image for every
# language in languages.yml, same design as SandboxExecutor itself: the image
# just needs the toolchains on PATH, the actual compile/run commands come
# from languages.yml at judge time.
#
# GNU `time` (the real /usr/bin/time binary, not the shell builtin) is
# required — SandboxExecutor wraps every containerized command with it to
# read back precise elapsed time and peak RSS, the same technique the
# previous host-side sandbox-run.sh used.
#
# Runs as a fixed non-root user; SandboxExecutor never runs anything as root
# inside the container. The submission's work dir is made world-writable by
# the host (SandboxExecutor.createWorkDir) specifically so this fixed uid can
# write into it under both native Linux Docker and Docker Desktop.
FROM ubuntu:22.04

RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ \
        openjdk-17-jdk-headless \
        python3 \
        time \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sandbox

USER sandbox
WORKDIR /sandbox
