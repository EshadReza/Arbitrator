#!/usr/bin/env python3

# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
"""
Prepares zero-download standalone distribution packages for Admin PC and Participant PCs.
Usage:
  python3 scripts/prepare-zero-download-bundles.py [--platform linux] [--jre /path/to/jre_directory]
"""

import argparse
import os
import shutil
import stat
import sys
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent.parent

PARTICIPANT_DIR = ROOT / "dist" / "participant-bundle"
ADMIN_DIR = ROOT / "dist" / "admin-bundle"
SERVER_PROPERTIES_SRC = ROOT / "arbitrator-client" / "src" / "main" / "resources" / "server.properties"
DOCKER_SRC = ROOT / "scripts" / "docker"
INIT_DB_SRC = ROOT / "scripts" / "init-db.sql"
TEMPLATES_ADMIN = ROOT / "scripts" / "bundle-templates" / "admin"
TEMPLATES_PARTICIPANT = ROOT / "scripts" / "bundle-templates" / "participant"


def copy_jre(src_jre: Path, target_jre: Path):
    if target_jre.exists():
        shutil.rmtree(target_jre)
    shutil.copytree(src_jre, target_jre)
    print(f"[OK] Copied JRE from {src_jre} -> {target_jre}")


def find_client_jar(platform: str) -> Optional[Path]:
    target_dir = ROOT / "arbitrator-client" / "target"
    candidate = target_dir / f"arbitrator-client-{platform}.jar"
    if candidate.exists():
        return candidate
    for f in target_dir.glob("arbitrator-client*.jar"):
        if not f.name.endswith(".original") and not f.name.endswith("-sources.jar"):
            return f
    return None


def find_server_jar() -> Optional[Path]:
    target_dir = ROOT / "arbitrator-server" / "target"
    candidate = target_dir / "arbitrator-server-0.1.0-SNAPSHOT.jar"
    if candidate.exists():
        return candidate
    for f in target_dir.glob("arbitrator-server*.jar"):
        if not f.name.endswith(".original") and not f.name.endswith("-sources.jar"):
            return f
    return None


def make_executable(path: Path):
    if path.exists():
        mode = os.stat(path).st_mode
        os.chmod(path, mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def main():
    parser = argparse.ArgumentParser(description="Assemble Arbitrator distribution packages.")
    parser.add_argument("--platform", type=str, default="linux",
                        help="JavaFX target platform classifier (default: linux)")
    parser.add_argument("--jre", type=str, help="Path to extracted JRE directory (containing bin/java)")
    args = parser.parse_args()

    print("=== Arbitrator Bundle Assembly ===")

    client_jar = find_client_jar(args.platform)
    server_jar = find_server_jar()

    if not client_jar:
        print(f"[ERROR] Client JAR for platform '{args.platform}' not found at arbitrator-client/target/.")
        print(f"Please build first by running:  mvn clean package -DskipTests -Djavafx.platform={args.platform}")
        sys.exit(1)

    if not server_jar:
        print("[ERROR] Server JAR not found at arbitrator-server/target/.")
        print(f"Please build first by running:  mvn clean package -DskipTests -Djavafx.platform={args.platform}")
        sys.exit(1)

    PARTICIPANT_DIR.mkdir(parents=True, exist_ok=True)
    ADMIN_DIR.mkdir(parents=True, exist_ok=True)

    # Clean old client jars in participant bundle to prevent stale jar conflicts
    for old_jar in PARTICIPANT_DIR.glob("arbitrator-client*.jar"):
        if old_jar.name != client_jar.name:
            old_jar.unlink()
            print(f"[CLEAN] Removed obsolete client JAR from bundle: {old_jar.name}")

    dest_client = PARTICIPANT_DIR / client_jar.name
    dest_server = ADMIN_DIR / server_jar.name

    shutil.copy2(client_jar, dest_client)
    print(f"[OK] Copied {client_jar.name} -> {dest_client}")

    shutil.copy2(server_jar, dest_server)
    print(f"[OK] Copied {server_jar.name} -> {dest_server}")

    # Copy server.properties template to participant bundle if not present
    dest_props = PARTICIPANT_DIR / "server.properties"
    if SERVER_PROPERTIES_SRC.exists() and not dest_props.exists():
        shutil.copy2(SERVER_PROPERTIES_SRC, dest_props)
        print(f"[OK] Copied default server.properties -> {dest_props}")

    # Copy docker sandbox files into admin bundle
    dest_docker = ADMIN_DIR / "docker"
    if DOCKER_SRC.exists():
        if dest_docker.exists():
            shutil.rmtree(dest_docker)
        shutil.copytree(DOCKER_SRC, dest_docker)
        make_executable(dest_docker / "build-sandbox-image.sh")
        print(f"[OK] Copied docker sandbox setup -> {dest_docker}")

    # Copy init-db.sql into admin bundle if present
    dest_init_db = ADMIN_DIR / "init-db.sql"
    if INIT_DB_SRC.exists():
        shutil.copy2(INIT_DB_SRC, dest_init_db)
        print(f"[OK] Copied init-db.sql -> {dest_init_db}")

    # Copy script templates into bundles
    if TEMPLATES_ADMIN.exists():
        for src_file in TEMPLATES_ADMIN.glob("*"):
            dest_file = ADMIN_DIR / src_file.name
            shutil.copy2(src_file, dest_file)
            make_executable(dest_file)

    if TEMPLATES_PARTICIPANT.exists():
        for src_file in TEMPLATES_PARTICIPANT.glob("*"):
            dest_file = PARTICIPANT_DIR / src_file.name
            shutil.copy2(src_file, dest_file)
            make_executable(dest_file)

    # Ensure scripts have execute bits
    make_executable(PARTICIPANT_DIR / "start-client.sh")
    make_executable(ADMIN_DIR / "start-server.sh")
    make_executable(ADMIN_DIR / "setup.sh")

    if args.jre:
        src_jre = Path(args.jre).resolve()
        if not src_jre.exists() or not (src_jre / "bin").exists():
            print(f"[ERROR] Specified JRE path '{src_jre}' is invalid or missing bin/ directory.")
            sys.exit(1)

        copy_jre(src_jre, PARTICIPANT_DIR / "jre")
        copy_jre(src_jre, ADMIN_DIR / "jre")
        print("\n[SUCCESS] JRE successfully bundled into both participant and admin packages!")
    else:
        has_participant_jre = (PARTICIPANT_DIR / "jre" / "bin").exists()
        has_admin_jre = (ADMIN_DIR / "jre" / "bin").exists()
        if has_participant_jre and has_admin_jre:
            print("[OK] Existing bundled JREs preserved in dist/participant-bundle and dist/admin-bundle.")
        else:
            print("\n--- NOTICE: ZERO-DOWNLOAD JRE BUNDLE ---")
            print("To include a standalone JRE, pass --jre /path/to/extracted_jre")
            print("Otherwise, scripts will fall back to system 'java' if jre/ is missing.")


if __name__ == "__main__":
    main()
