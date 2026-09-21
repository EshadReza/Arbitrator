#!/usr/bin/env python3

# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
"""
Prepares zero-download standalone distribution packages for Admin PC and Participant PCs.
Usage:
  python3 scripts/prepare-zero-download-bundles.py [--jre /path/to/jre_directory]
"""

import argparse
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT_JAR = ROOT / "arbitrator-client" / "target" / "arbitrator-client-0.1.0-SNAPSHOT.jar"
SERVER_JAR = ROOT / "arbitrator-server" / "target" / "arbitrator-server-0.1.0-SNAPSHOT.jar"

PARTICIPANT_DIR = ROOT / "dist" / "participant-bundle"
ADMIN_DIR = ROOT / "dist" / "admin-bundle"

def copy_jre(src_jre: Path, target_jre: Path):
    if target_jre.exists():
        shutil.rmtree(target_jre)
    shutil.copytree(src_jre, target_jre)
    print(f"[OK] Copied JRE from {src_jre} -> {target_jre}")

def main():
    parser = argparse.ArgumentParser(description="Assemble Arbitrator distribution packages.")
    parser.add_argument("--jre", type=str, help="Path to extracted JRE directory (containing bin/java)")
    args = parser.parse_args()

    print("=== Arbitrator Bundle Assembly ===")
    
    if not CLIENT_JAR.exists():
        print(f"[ERROR] Client JAR not found at {CLIENT_JAR}")
        print("Please build the project first by running:  mvn clean package -DskipTests")
        sys.exit(1)
        
    if not SERVER_JAR.exists():
        print(f"[ERROR] Server JAR not found at {SERVER_JAR}")
        print("Please build the project first by running:  mvn clean package -DskipTests")
        sys.exit(1)

    PARTICIPANT_DIR.mkdir(parents=True, exist_ok=True)
    ADMIN_DIR.mkdir(parents=True, exist_ok=True)

    dest_client = PARTICIPANT_DIR / CLIENT_JAR.name
    dest_server = ADMIN_DIR / SERVER_JAR.name

    shutil.copy2(CLIENT_JAR, dest_client)
    print(f"[OK] Copied {CLIENT_JAR.name} -> {dest_client}")

    shutil.copy2(SERVER_JAR, dest_server)
    print(f"[OK] Copied {SERVER_JAR.name} -> {dest_server}")

    if args.jre:
        src_jre = Path(args.jre).resolve()
        if not src_jre.exists() or not (src_jre / "bin").exists():
            print(f"[ERROR] Specified JRE path '{src_jre}' is invalid or missing bin/ directory.")
            sys.exit(1)
        
        copy_jre(src_jre, PARTICIPANT_DIR / "jre")
        copy_jre(src_jre, ADMIN_DIR / "jre")
        print("\n[SUCCESS] JRE successfully bundled into both participant and admin packages!")
    else:
        print("\n--- NEXT STEPS FOR ZERO-DOWNLOAD DISTRIBUTION ---")
        print("Copy your extracted JRE directory into:")
        print(f"   - Participant PCs folder: {PARTICIPANT_DIR}/jre/")
        print(f"   - Admin PC folder:        {ADMIN_DIR}/jre/")
        print("Or re-run this script with: --jre /path/to/jre_directory")

if __name__ == "__main__":
    main()
