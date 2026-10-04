#!/usr/bin/env python3

# Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
# All rights reserved.
"""Packs the labjudge tree into one self-extracting text file.

Usage:  python3 scripts/make-bundle.py

The bundle is a handoff artifact (email it, paste it, hand it to someone
without git). It is regenerated, never edited, and git-ignored — git already
does the job of tracking the tree.
"""
import os

# Derived from this script's location, so it works on any machine.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Lives inside the repo folder so every project doc sits in one place.
# It is git-ignored and self-excluded below (bundling itself would double
# the file on every regeneration).
OUT = os.path.join(ROOT, "ARBITRATOR_BUNDLE.txt")
SKIP_DIRS = {"target", ".git", ".settings", "bin", ".metadata"}
# Mirror .gitignore: anything git won't track must never enter the bundle.
# application-local.yml holds each developer's real DB password (AGENTS.md Rule 6).
SKIP_FILES = {
    ".DS_Store",
    "application-local.yml",
    ".env",
    ".classpath",
    ".project",
    "ARBITRATOR_BUNDLE.txt",   # never bundle the bundle
}

HEADER = """\
================================================================================
 LABJUDGE — COMPLETE PROJECT BUNDLE (Bundle 1: runnable vertical slice)
 Team: Eshad, Mahir, Zahin | Spring Boot 3.2 + JavaFX 21 + MySQL 8 | Linux only
================================================================================

 HOW TO UNPACK (any machine with python3):

   1. Save this file as ARBITRATOR_BUNDLE.txt in an empty folder
   2. Run:
        python3 - <<'EOF'
        import os, re
        with open('ARBITRATOR_BUNDLE.txt', encoding='utf-8') as f:
            text = f.read()
        parts = re.split(r'^=== FILE: (.+?) ===$', text, flags=re.M)[1:]
        for path, body in zip(parts[::2], parts[1::2]):
            path = path.strip()
            os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
            with open(path, 'w', encoding='utf-8') as out:
                out.write(body.lstrip('\\n'))
            print('wrote', path)
        os.chmod('scripts/sandbox-run.sh', 0o755)
        EOF
   3. Then follow README.md for setup and AGENTS.md for the
      development and collaboration rules.

 Everything below this line is project files, delimited by === FILE: === marks.
================================================================================
"""


def main():
    entries = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name in SKIP_FILES:
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, ROOT)
            with open(full, encoding="utf-8") as f:
                entries.append((rel, f.read()))

    with open(OUT, "w", encoding="utf-8") as out:
        out.write(HEADER)
        for rel, body in entries:
            # No blank line before the marker: every file ends with \n, so the
            # marker sits flush and the extracted body is byte-identical.
            out.write(f"=== FILE: {rel} ===\n")
            out.write(body)
            if not body.endswith("\n"):
                out.write("\n")

    total = sum(len(b) for _, b in entries)
    print(f"{len(entries)} files, {total} bytes -> {OUT}")


if __name__ == "__main__":
    main()
