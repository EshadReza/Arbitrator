#!/usr/bin/env python3

# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
"""
Prepares zero-download standalone distribution packages for Linux, Windows, and macOS.

Each target gets dist/<target>/admin (server + Docker/MySQL setup) and
dist/<target>/client (participant client) with that OS's one-click script.

Usage:
  python3 scripts/prepare-zero-download-bundles.py [--target {all,linux,windows,mac}]
      [--fetch-jres] [--jre-linux DIR] [--jre-windows DIR] [--jre-mac DIR ...]
      [--with-images]

Java runtimes are OS- and CPU-specific. Linux and Windows bundles carry one
JRE in jre/; macOS bundles carry jre-x64/ (Intel) and jre-aarch64/ (Apple
Silicon), and the launcher picks the matching one.

--fetch-jres downloads the latest Eclipse Temurin 17 JRE for every runtime that
is not given explicitly, verifies its SHA-256, and caches it in dist/.jre-cache/.
--jre-mac may be given twice (Intel and Apple Silicon); the CPU type is read
from the JRE's release file.

--with-images saves the mysql:8.0 and judge Docker images from this machine
into each admin/images/, so the launchers load them instead of downloading
(about 0.5 GB compressed, exported once to dist/.image-cache/ and hard-linked
into each OS folder). Build the judge
image first with scripts/docker/build-sandbox-image.sh.

The run ends with a check of every assembled bundle and exits non-zero if
anything required is missing.
"""

import argparse
import gzip
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Dict, List, Optional

ROOT = Path(__file__).resolve().parent.parent

DIST_DIR = ROOT / "dist"
JRE_CACHE = DIST_DIR / ".jre-cache"
IMAGE_CACHE = DIST_DIR / ".image-cache"
SERVER_PROPERTIES_SRC = ROOT / "arbitrator-client" / "src" / "main" / "resources" / "server.properties"
DOCKER_SRC = ROOT / "scripts" / "docker"
INIT_DB_SRC = ROOT / "scripts" / "init-db.sql"
TEMPLATES_DIR = ROOT / "scripts" / "bundle-templates"

# Bundle JRE slot -> (folder inside admin/ and client/, Adoptium os, Adoptium arch, release-file OS_ARCH)
JRE_SLOTS = {
    "linux": [("jre", "linux", "x64", "x86_64")],
    "windows": [("jre", "windows", "x64", "x86_64")],
    "mac": [("jre-x64", "mac", "x64", "x86_64"), ("jre-aarch64", "mac", "aarch64", "aarch64")],
}
JAVA_EXECUTABLE = {"linux": "bin/java", "mac": "bin/java", "windows": "bin/java.exe"}

CLIENT_JARS = {
    "linux": ["arbitrator-client-linux.jar"],
    "windows": ["arbitrator-client-win.jar"],
    "mac": ["arbitrator-client-mac.jar", "arbitrator-client-mac-aarch64.jar"],
}
JAVAFX_PLATFORM = {"linux": "linux", "windows": "win", "mac": "mac and mac-aarch64"}

# (image, file name in admin/images/) loaded by every start-server launcher.
BUNDLED_IMAGES = [
    ("mysql:8.0", "mysql-8.0.tar.gz"),
    (os.environ.get("ARBITRATOR_DOCKER_IMAGE", "arbitrator-judge:latest"), "arbitrator-judge.tar.gz"),
]

LAUNCHER_SUFFIXES = (".sh", ".bat", ".command")


def make_executable(path: Path):
    if path.exists():
        mode = os.stat(path).st_mode
        os.chmod(path, mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


# ---------------------------------------------------------------------------
# Java runtimes
# ---------------------------------------------------------------------------

def jre_home(target_os: str, jre: Path) -> Optional[Path]:
    """The JRE home under jre (a macOS Contents/Home or a single top-level folder is accepted)."""
    candidates = [jre, jre / "Contents" / "Home"]
    children = [c for c in jre.iterdir() if c.is_dir()] if jre.is_dir() else []
    if len(children) == 1:
        candidates += [children[0], children[0] / "Contents" / "Home"]
    for c in candidates:
        if (c / JAVA_EXECUTABLE[target_os]).is_file():
            return c
    return None


def release_arch(home: Path) -> Optional[str]:
    release = home / "release"
    if not release.is_file():
        return None
    for line in release.read_text(errors="replace").splitlines():
        if line.startswith("OS_ARCH="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def resolve_given_jre(target_os: str, path: str) -> Path:
    home = jre_home(target_os, Path(path).resolve())
    if home is None:
        print(f"[ERROR] {path} is not a {target_os} JRE: no {JAVA_EXECUTABLE[target_os]} found")
        sys.exit(1)
    return home


def http_open(url: str, timeout: int):
    # api.adoptium.net rejects Python's default User-Agent with 403.
    request = urllib.request.Request(url, headers={"User-Agent": "arbitrator-bundle-builder/1.0"})
    return urllib.request.urlopen(request, timeout=timeout)


def adoptium_package(adopt_os: str, arch: str) -> dict:
    url = ("https://api.adoptium.net/v3/assets/latest/17/hotspot"
           f"?architecture={arch}&image_type=jre&os={adopt_os}&vendor=eclipse")
    for attempt in range(1, 6):
        try:
            with http_open(url, 60) as resp:
                releases = json.load(resp)
            return releases[0]["binary"]["package"]
        except Exception as e:  # network errors on flaky connections
            print(f"[WARN] Adoptium API attempt {attempt}/5 failed: {e}")
            time.sleep(5 * attempt)
    print("[ERROR] Could not reach api.adoptium.net to find the JRE download.")
    sys.exit(1)


def download(url: str, dest: Path, sha256: str):
    for attempt in range(1, 6):
        partial = dest.with_name(dest.name + ".partial")
        try:
            digest = hashlib.sha256()
            with http_open(url, 120) as resp, open(partial, "wb") as out:
                while chunk := resp.read(1024 * 1024):
                    digest.update(chunk)
                    out.write(chunk)
            if digest.hexdigest() != sha256:
                raise IOError(f"SHA-256 mismatch for {dest.name}")
            partial.replace(dest)
            return
        except Exception as e:
            partial.unlink(missing_ok=True)
            print(f"[WARN] Download attempt {attempt}/5 failed: {e}")
            time.sleep(5 * attempt)
    print(f"[ERROR] Could not download {url}")
    sys.exit(1)


def fetch_jre(target_os: str, adopt_os: str, arch: str) -> Path:
    """Download (once) and extract the latest Temurin 17 JRE; returns its home."""
    pkg = adoptium_package(adopt_os, arch)
    JRE_CACHE.mkdir(parents=True, exist_ok=True)
    archive = JRE_CACHE / pkg["name"]
    extracted = JRE_CACHE / (pkg["name"] + ".d")
    if not archive.exists():
        print(f"[INFO] Downloading {pkg['name']} ({pkg['size'] / 1e6:.0f} MB)...")
        download(pkg["link"], archive, pkg["checksum"])
    if not extracted.exists():
        tmp = extracted.with_name(extracted.name + ".tmp")
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir()
        if archive.name.endswith(".zip"):
            with zipfile.ZipFile(archive) as z:
                z.extractall(tmp)
        else:
            with tarfile.open(archive) as t:
                t.extractall(tmp, filter="tar")
        tmp.rename(extracted)
    home = jre_home(target_os, extracted)
    if home is None:
        print(f"[ERROR] {archive.name} did not contain {JAVA_EXECUTABLE[target_os]}")
        sys.exit(1)
    print(f"[OK] JRE {pkg['name']} (SHA-256 verified)")
    return home


def copy_jre(src_jre: Path, target_jre: Path):
    if target_jre.exists():
        shutil.rmtree(target_jre)
    shutil.copytree(src_jre, target_jre, symlinks=True)
    # zip archives carry no Unix modes; keep the copies runnable on Unix hosts.
    for sub in ("bin", "lib"):
        for f in (target_jre / sub).glob("*"):
            if f.is_file() and (sub == "bin" or f.name == "jspawnhelper"):
                make_executable(f)
    print(f"[OK] JRE -> {target_jre}")


# ---------------------------------------------------------------------------
# Docker images
# ---------------------------------------------------------------------------

def docker_image_id(image: str) -> Optional[str]:
    result = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", image],
                            capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def export_images(images_dir: Path):
    images_dir.mkdir(parents=True, exist_ok=True)
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


def link_images(src_dir: Path, dest_dir: Path):
    """Share the exported image files between OS folders without extra disk space."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    for f in src_dir.iterdir():
        target = dest_dir / f.name
        if target.exists() and os.path.samefile(f, target):
            continue
        target.unlink(missing_ok=True)
        try:
            os.link(f, target)
        except OSError:
            shutil.copy2(f, target)
    print(f"[OK] Docker images -> {dest_dir}")


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------

def find_server_jar() -> Optional[Path]:
    target_dir = ROOT / "arbitrator-server" / "target"
    candidate = target_dir / "arbitrator-server-0.1.0-SNAPSHOT.jar"
    if candidate.exists():
        return candidate
    for f in target_dir.glob("arbitrator-server*.jar"):
        if not f.name.endswith(".original") and not f.name.endswith("-sources.jar"):
            return f
    return None


def copy_script(src: Path, dest: Path):
    if src.suffix in (".bat", ".txt") and "windows" in src.parts:
        # cmd.exe mis-parses labels and goto in LF-only batch files.
        text = src.read_text(encoding="utf-8").replace("\r\n", "\n")
        dest.write_bytes(text.replace("\n", "\r\n").encode("ascii"))
    else:
        shutil.copy2(src, dest)
        if src.suffix in (".sh", ".command"):
            make_executable(dest)


def install_templates(os_templates: Path, role: str, dest_dir: Path):
    templates = sorted((os_templates / role).glob("*"))
    names = {t.name for t in templates}
    # Remove launchers from earlier template versions (e.g. a separate setup script).
    for old in dest_dir.iterdir():
        if old.is_file() and old.suffix in LAUNCHER_SUFFIXES and old.name not in names:
            old.unlink()
            print(f"[CLEAN] Removed obsolete {old.name}")
    for f in templates:
        copy_script(f, dest_dir / f.name)
        print(f"[OK] {role.capitalize()} script -> {dest_dir / f.name}")


def assemble_bundle(target_os: str, server_jar: Path, jres: Dict[str, Path], images_src: Optional[Path]):
    os_dir = DIST_DIR / target_os
    admin_dir = os_dir / "admin"
    client_dir = os_dir / "client"
    admin_dir.mkdir(parents=True, exist_ok=True)
    client_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n--- Assembling distribution for: {target_os.upper()} ---")

    for old_jar in admin_dir.glob("arbitrator-server*.jar"):
        if old_jar.name != server_jar.name:
            old_jar.unlink()
    shutil.copy2(server_jar, admin_dir / server_jar.name)
    print(f"[OK] Server JAR -> {admin_dir / server_jar.name}")

    dest_docker = admin_dir / "docker"
    shutil.rmtree(dest_docker, ignore_errors=True)
    shutil.copytree(DOCKER_SRC, dest_docker)
    make_executable(dest_docker / "build-sandbox-image.sh")
    shutil.copy2(INIT_DB_SRC, admin_dir / "init-db.sql")
    print(f"[OK] Docker setup + init-db.sql -> {admin_dir}")

    client_target = ROOT / "arbitrator-client" / "target"
    for old_jar in client_dir.glob("arbitrator-client*.jar"):
        if old_jar.name not in CLIENT_JARS[target_os]:
            old_jar.unlink()
    for name in CLIENT_JARS[target_os]:
        if (client_target / name).exists():
            shutil.copy2(client_target / name, client_dir / name)
            print(f"[OK] Client JAR -> {client_dir / name}")
        else:
            (client_dir / name).unlink(missing_ok=True)
            print(f"[WARN] {name} is not built; it is missing from this bundle")

    dest_props = client_dir / "server.properties"
    if not dest_props.exists():
        shutil.copy2(SERVER_PROPERTIES_SRC, dest_props)
        print(f"[OK] server.properties -> {dest_props}")

    os_templates = TEMPLATES_DIR / target_os
    install_templates(os_templates, "admin", admin_dir)
    install_templates(os_templates, "client", client_dir)
    # The quick start goes next to each launcher too: admin/ and client/ are
    # copied to different PCs on their own.
    for d in (os_dir, admin_dir, client_dir):
        copy_script(os_templates / "README.txt", d / "README.txt")

    slot_dirs = {slot for slot, *_ in JRE_SLOTS[target_os]}
    for d in (admin_dir, client_dir):
        for old in d.glob("jre*"):
            if old.is_dir() and old.name not in slot_dirs:
                shutil.rmtree(old)
                print(f"[CLEAN] Removed obsolete {old}")
    for slot, *_ in JRE_SLOTS[target_os]:
        if slot in jres:
            copy_jre(jres[slot], admin_dir / slot)
            copy_jre(jres[slot], client_dir / slot)
        elif (admin_dir / slot).is_dir() and (client_dir / slot).is_dir():
            print(f"[OK] Kept existing {slot}/")

    if images_src is not None:
        link_images(images_src, admin_dir / "images")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_bundle(target_os: str, server_jar: Path, want_images: bool) -> List[str]:
    """Everything a lab PC needs, checked after assembly; returns the problems."""
    problems = []
    os_dir = DIST_DIR / target_os
    admin_dir = os_dir / "admin"
    client_dir = os_dir / "client"

    def need(path: Path, why: str):
        if not path.exists():
            problems.append(f"missing {path.relative_to(DIST_DIR)} ({why})")

    def same_as_build(bundled: Path, built: Path):
        if not built.exists():
            problems.append(f"{built.relative_to(ROOT)} is not built "
                            f"(build with -Djavafx.platform={JAVAFX_PLATFORM[target_os]})")
        elif bundled.exists() and sha256_of(bundled) != sha256_of(built):
            problems.append(f"{bundled.relative_to(DIST_DIR)} differs from the current build {built.name}")

    need(admin_dir / server_jar.name, "server")
    same_as_build(admin_dir / server_jar.name, server_jar)
    for name in CLIENT_JARS[target_os]:
        same_as_build(client_dir / name, ROOT / "arbitrator-client" / "target" / name)
    need(admin_dir / "docker" / "Dockerfile", "judge image build")
    for name in CLIENT_JARS[target_os]:
        need(client_dir / name, f"build with -Djavafx.platform={JAVAFX_PLATFORM[target_os]}")
    need(client_dir / "server.properties", "server address")
    for d in (os_dir, admin_dir, client_dir):
        need(d / "README.txt", "quick start")
    for role, d in (("admin", admin_dir), ("client", client_dir)):
        for t in (TEMPLATES_DIR / target_os / role).glob("*"):
            need(d / t.name, "launcher")
        for slot, _, _, arch in JRE_SLOTS[target_os]:
            java = d / slot / JAVA_EXECUTABLE[target_os]
            if not java.is_file():
                problems.append(f"missing {java.relative_to(DIST_DIR)} (no Java runtime; use --fetch-jres)")
                continue
            actual = release_arch(d / slot)
            if actual and actual != arch:
                problems.append(f"{(d / slot).relative_to(DIST_DIR)} is a {actual} JRE, expected {arch}")
    for bat in os_dir.rglob("*.bat"):
        data = bat.read_bytes()
        if b"\n" in data.replace(b"\r\n", b""):
            problems.append(f"{bat.relative_to(DIST_DIR)} has LF-only line endings")
        if any(b > 127 for b in data):
            problems.append(f"{bat.relative_to(DIST_DIR)} contains non-ASCII bytes")
    if want_images:
        for _, file_name in BUNDLED_IMAGES:
            need(admin_dir / "images" / file_name, "offline Docker image")
    return problems


def main():
    parser = argparse.ArgumentParser(description="Assemble Arbitrator distribution packages.")
    parser.add_argument("--target", type=str, default="all", choices=["all", "linux", "windows", "mac"],
                        help="Target OS distribution to build (default: all)")
    parser.add_argument("--platform", type=str, dest="legacy_platform",
                        help="Legacy alias for --target")
    parser.add_argument("--jre-linux", type=str, help="Path to an extracted Linux x64 JRE 17")
    parser.add_argument("--jre-windows", type=str, help="Path to an extracted Windows x64 JRE 17")
    parser.add_argument("--jre-mac", type=str, action="append", default=[],
                        help="Path to an extracted macOS JRE 17 (repeat for Intel and Apple Silicon)")
    parser.add_argument("--jre", type=str, help="Path to an extracted JRE (single --target only)")
    parser.add_argument("--fetch-jres", action="store_true",
                        help="Download any JRE not given explicitly (Eclipse Temurin 17, SHA-256 verified)")
    parser.add_argument("--with-images", action="store_true",
                        help="Bundle the mysql:8.0 and judge Docker images into each admin folder")
    args = parser.parse_args()

    target = args.legacy_platform or args.target
    if target == "win":
        target = "windows"
    if target not in ("all", "linux", "windows", "mac"):
        parser.error(f"unknown target '{target}'")
    targets = ["linux", "windows", "mac"] if target == "all" else [target]

    if args.jre:
        if len(targets) != 1:
            parser.error("--jre needs a single --target; use --jre-linux/--jre-windows/--jre-mac instead")
        if targets[0] == "mac":
            args.jre_mac.append(args.jre)
        else:
            setattr(args, f"jre_{targets[0]}", args.jre)

    server_jar = find_server_jar()
    if not server_jar:
        print("[ERROR] Server JAR not found at arbitrator-server/target/.")
        print("Please build first by running:  mvn clean package -DskipTests -Djavafx.platform=linux")
        sys.exit(1)

    print("====================================================")
    print(f"  Assembling Arbitrator Distributions: {', '.join(targets)}")
    print("====================================================")

    # Resolve every JRE before touching dist/, so a bad path fails early.
    jres: Dict[str, Dict[str, Path]] = {t: {} for t in targets}
    for t in targets:
        given = []
        if t == "mac":
            given = [resolve_given_jre("mac", p) for p in args.jre_mac]
        elif getattr(args, f"jre_{t}"):
            given = [resolve_given_jre(t, getattr(args, f"jre_{t}"))]
        for home in given:
            arch = release_arch(home)
            slots = [s for s in JRE_SLOTS[t] if arch is None or s[3] == arch]
            if not slots:
                print(f"[ERROR] {home} is a {arch} JRE; {t} bundles need "
                      f"{', '.join(s[3] for s in JRE_SLOTS[t])}")
                sys.exit(1)
            jres[t][slots[0][0]] = home
        if args.fetch_jres:
            for slot, adopt_os, arch, _ in JRE_SLOTS[t]:
                if slot not in jres[t]:
                    jres[t][slot] = fetch_jre(t, adopt_os, arch)

    images_src = None
    if args.with_images:
        images_src = IMAGE_CACHE
        export_images(images_src)

    for t in targets:
        assemble_bundle(t, server_jar, jres[t], images_src)

    print("\n====================================================")
    print("  Bundle check")
    print("====================================================")
    failed = False
    for t in targets:
        problems = verify_bundle(t, server_jar, args.with_images)
        if problems:
            failed = True
            print(f"[FAIL] {t}:")
            for p in problems:
                print(f"         - {p}")
        else:
            print(f"[PASS] {t}: launchers, server, client, Java runtime"
                  f"{', Docker images' if args.with_images else ''} present")
    if failed:
        sys.exit(1)
    print("\nDistributions assembled under dist/<os>/{admin,client}")


if __name__ == "__main__":
    main()
