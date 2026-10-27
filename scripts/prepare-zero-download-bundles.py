#!/usr/bin/env python3

# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
"""
Prepares zero-download standalone distribution packages for Linux, Windows, and macOS.

Each target gets dist/<target>/admin (server + Docker/MySQL setup) and
dist/<target>/client (participant client) with that OS's one-click scripts.

Usage:
  python3 scripts/prepare-zero-download-bundles.py [--target {all,linux,windows,mac}]
      [--jre-linux DIR] [--jre-windows DIR] [--jre-mac DIR] [--with-images]

A JRE is OS- and CPU-specific, so each target takes its own. --jre DIR is
accepted only together with a single --target.

--with-images saves the mysql:8.0 and judge Docker images from this machine
into dist/linux/admin/images/, so the lab's start-server.sh loads them instead
of downloading (about 0.5 GB compressed). Build the judge image first with
scripts/docker/build-sandbox-image.sh.
"""

import argparse
import gzip
import os
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import List, Optional

ROOT = Path(__file__).resolve().parent.parent

DIST_DIR = ROOT / "dist"
SERVER_PROPERTIES_SRC = ROOT / "arbitrator-client" / "src" / "main" / "resources" / "server.properties"
DOCKER_SRC = ROOT / "scripts" / "docker"
INIT_DB_SRC = ROOT / "scripts" / "init-db.sql"
TEMPLATES_DIR = ROOT / "scripts" / "bundle-templates"


def make_executable(path: Path):
    if path.exists():
        mode = os.stat(path).st_mode
        os.chmod(path, mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


JAVA_EXECUTABLE = {"linux": "bin/java", "mac": "bin/java", "windows": "bin/java.exe"}


def resolve_jre(target_os: str, jre: Path) -> Path:
    """Return the JRE home, accepting a macOS bundle layout (Contents/Home)."""
    if target_os == "mac" and (jre / "Contents" / "Home" / "bin").is_dir():
        jre = jre / "Contents" / "Home"
    if not (jre / JAVA_EXECUTABLE[target_os]).is_file():
        print(f"[ERROR] {jre} is not a {target_os} JRE: missing {JAVA_EXECUTABLE[target_os]}")
        sys.exit(1)
    return jre


def copy_jre(src_jre: Path, target_jre: Path):
    if target_jre.exists():
        shutil.rmtree(target_jre)
    shutil.copytree(src_jre, target_jre, symlinks=True)
    print(f"[OK] Copied JRE: {src_jre} -> {target_jre}")


def copy_script(src: Path, dest: Path):
    if src.suffix == ".bat":
        # cmd.exe mis-parses labels and goto in LF-only batch files.
        text = src.read_text(encoding="utf-8").replace("\r\n", "\n")
        dest.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
    else:
        shutil.copy2(src, dest)
        if src.suffix in (".sh", ".command"):
            make_executable(dest)


# (image, file name in admin/images/) loaded by the Linux start-server.sh.
BUNDLED_IMAGES = [
    ("mysql:8.0", "mysql-8.0.tar.gz"),
    (os.environ.get("ARBITRATOR_DOCKER_IMAGE", "arbitrator-judge:latest"), "arbitrator-judge.tar.gz"),
]


def docker_image_id(image: str) -> Optional[str]:
    result = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", image],
                            capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def export_images(admin_dir: Path):
    images_dir = admin_dir / "images"
    images_dir.mkdir(exist_ok=True)
    for image, file_name in BUNDLED_IMAGES:
        image_id = docker_image_id(image)
        if image_id is None and image.startswith("mysql:"):
            for attempt in range(1, 6):
                print(f"[INFO] Pulling {image} (attempt {attempt}/5)...")
                if subprocess.run(["docker", "pull", image]).returncode == 0:
                    break
                time.sleep(5 * attempt)
            image_id = docker_image_id(image)
        if image_id is None:
            print(f"[ERROR] Docker image {image} is not available on this machine.")
            if "judge" in file_name:
                print("Build it first with:  scripts/docker/build-sandbox-image.sh")
            sys.exit(1)

        dest = images_dir / file_name
        id_file = images_dir / (file_name + ".id")
        if dest.exists() and id_file.exists() and id_file.read_text().strip() == image_id:
            print(f"[OK] {dest.name} is up to date")
            continue

        print(f"[INFO] Saving {image} -> {dest} (this takes a few minutes)...")
        partial = dest.with_suffix(".partial")
        with subprocess.Popen(["docker", "save", image], stdout=subprocess.PIPE) as proc, \
                gzip.open(partial, "wb", compresslevel=1) as out:
            shutil.copyfileobj(proc.stdout, out, 1024 * 1024)
        if proc.returncode != 0:
            partial.unlink(missing_ok=True)
            print(f"[ERROR] docker save {image} failed.")
            sys.exit(1)
        partial.replace(dest)
        id_file.write_text(image_id + "\n")
        print(f"[OK] {dest.name}: {dest.stat().st_size / 1e6:.0f} MB")


def find_server_jar() -> Optional[Path]:
    target_dir = ROOT / "arbitrator-server" / "target"
    candidate = target_dir / "arbitrator-server-0.1.0-SNAPSHOT.jar"
    if candidate.exists():
        return candidate
    for f in target_dir.glob("arbitrator-server*.jar"):
        if not f.name.endswith(".original") and not f.name.endswith("-sources.jar"):
            return f
    return None


def find_client_jars(platform: str) -> List[Path]:
    target_dir = ROOT / "arbitrator-client" / "target"
    if not target_dir.exists():
        return []

    jars = []
    if platform == "linux":
        p = target_dir / "arbitrator-client-linux.jar"
        if p.exists():
            jars.append(p)
    elif platform == "windows":
        p = target_dir / "arbitrator-client-win.jar"
        if p.exists():
            jars.append(p)
    elif platform == "mac":
        for name in ["arbitrator-client-mac-aarch64.jar", "arbitrator-client-mac.jar"]:
            p = target_dir / name
            if p.exists():
                jars.append(p)
    return jars


def assemble_bundle(target_os: str, server_jar: Path, jre_path: Optional[Path], with_images: bool):
    os_dir = DIST_DIR / target_os
    admin_dir = os_dir / "admin"
    client_dir = os_dir / "client"

    admin_dir.mkdir(parents=True, exist_ok=True)
    client_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n--- Assembling distribution for: {target_os.upper()} ---")

    # 1. Admin JAR
    dest_server = admin_dir / server_jar.name
    shutil.copy2(server_jar, dest_server)
    print(f"[OK] Server JAR -> {dest_server}")

    # 2. Docker files & init-db.sql
    dest_docker = admin_dir / "docker"
    if DOCKER_SRC.exists():
        if dest_docker.exists():
            shutil.rmtree(dest_docker)
        shutil.copytree(DOCKER_SRC, dest_docker)
        make_executable(dest_docker / "build-sandbox-image.sh")
        print(f"[OK] Docker setup -> {dest_docker}")

    if INIT_DB_SRC.exists():
        dest_init_db = admin_dir / "init-db.sql"
        shutil.copy2(INIT_DB_SRC, dest_init_db)
        print(f"[OK] init-db.sql -> {dest_init_db}")

    # 3. Client JARs
    client_jars = find_client_jars(target_os)
    if not client_jars:
        platforms = {"linux": "linux", "windows": "win", "mac": "mac and mac-aarch64"}[target_os]
        print(f"[WARN] No client JARs found for '{target_os}'. Build with: "
              f"mvn -pl arbitrator-client -am package -DskipTests -Djavafx.platform=<{platforms}>")
    else:
        if target_os == "mac" and len(client_jars) == 1:
            print(f"[WARN] Only {client_jars[0].name} found; Macs with the other CPU type cannot run the client.")
        # Clean obsolete client jars in destination
        for old_jar in client_dir.glob("arbitrator-client*.jar"):
            if old_jar.name not in [j.name for j in client_jars]:
                old_jar.unlink()
        for jar in client_jars:
            dest_client = client_dir / jar.name
            shutil.copy2(jar, dest_client)
            print(f"[OK] Client JAR -> {dest_client}")

    # 4. server.properties
    if SERVER_PROPERTIES_SRC.exists():
        dest_props = client_dir / "server.properties"
        if not dest_props.exists():
            shutil.copy2(SERVER_PROPERTIES_SRC, dest_props)
            print(f"[OK] server.properties -> {dest_props}")

    # 5. OS-specific scripts and quick-start notes
    os_templates = TEMPLATES_DIR / target_os
    for role, dest_dir in (("admin", admin_dir), ("client", client_dir)):
        for f in sorted((os_templates / role).glob("*")):
            copy_script(f, dest_dir / f.name)
            print(f"[OK] {role.capitalize()} script -> {dest_dir / f.name}")
    if (os_templates / "README.txt").exists():
        copy_script(os_templates / "README.txt", os_dir / "README.txt")

    # 6. JRE Handling
    if jre_path:
        copy_jre(jre_path, admin_dir / "jre")
        copy_jre(jre_path, client_dir / "jre")
    elif (admin_dir / "jre" / "bin").exists() and (client_dir / "jre" / "bin").exists():
        print(f"[OK] Preserved existing bundled JRE in {target_os}")
    else:
        print(f"[NOTE] No JRE bundled for {target_os}; target machines need Java 17 installed.")

    # 7. Offline Docker images (only the Linux launcher loads them)
    if with_images and target_os == "linux":
        export_images(admin_dir)


def main():
    parser = argparse.ArgumentParser(description="Assemble Arbitrator distribution packages.")
    parser.add_argument("--target", type=str, default="all", choices=["all", "linux", "windows", "mac"],
                        help="Target OS distribution to build (default: all)")
    parser.add_argument("--platform", type=str, dest="legacy_platform",
                        help="Legacy alias for --target")
    for os_name in ("linux", "windows", "mac"):
        parser.add_argument(f"--jre-{os_name}", type=str, dest=f"jre_{os_name}",
                            help=f"Path to an extracted {os_name} JRE 17")
    parser.add_argument("--jre", type=str, help="Path to an extracted JRE (single --target only)")
    parser.add_argument("--with-images", action="store_true",
                        help="Bundle the mysql:8.0 and judge Docker images into the Linux admin folder")
    args = parser.parse_args()

    target = args.legacy_platform or args.target
    if target == "win":
        target = "windows"
    if target not in ("all", "linux", "windows", "mac"):
        parser.error(f"unknown target '{target}'")

    targets = ["linux", "windows", "mac"] if target == "all" else [target]

    jres = {t: getattr(args, f"jre_{t}") for t in ("linux", "windows", "mac")}
    if args.jre:
        if len(targets) != 1:
            parser.error("--jre needs a single --target; use --jre-linux/--jre-windows/--jre-mac instead")
        jres[targets[0]] = args.jre

    if args.with_images and "linux" not in targets:
        parser.error("--with-images applies to the linux target only")

    server_jar = find_server_jar()
    if not server_jar:
        print("[ERROR] Server JAR not found at arbitrator-server/target/.")
        print("Please build first by running:  mvn clean package -DskipTests")
        sys.exit(1)

    print("====================================================")
    print(f"  Assembling Arbitrator Distributions: {', '.join(targets)}")
    print("====================================================")

    for t in targets:
        jre_path = resolve_jre(t, Path(jres[t]).resolve()) if jres[t] else None
        assemble_bundle(t, server_jar, jre_path, args.with_images)

    print("\n====================================================")
    print("  Distributions assembled under dist/<os>/{admin,client}")
    print("====================================================")


if __name__ == "__main__":
    main()
