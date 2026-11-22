#!/usr/bin/env python3
"""Cheap metadata check; the expensive APK verifier runs only after changes or for an audit."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("drive_catalog_check", Path(__file__).with_name("sync-google-drive-catalog.py"))
drive = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = drive
spec.loader.exec_module(drive)


def needs_sync(items, catalog, state, folder_id=drive.FOLDER_ID):
    try:
        previous = json.loads(Path(state).read_text(encoding="utf-8"))
        current = Path(catalog).read_bytes()
        if previous.get("format") != 1 or previous.get("folderId") != folder_id:
            return True
        if previous.get("fingerprint") != drive.listing_fingerprint(items):
            return True
        if previous.get("catalogSha256") != hashlib.sha256(current).hexdigest():
            return True
        apps = json.loads(current)["apps"]
        if not apps or len({app["packageName"] for app in apps}) != len(apps):
            return True
        for app in apps:
            if bool(app.get("iconUrl")) != bool(app.get("iconSha256")):
                return True
            if app.get("iconUrl"):
                png = Path(catalog).parent / "catalog-icons" / (app["sha256"] + ".png")
                if not png.is_file() or hashlib.sha256(png.read_bytes()).hexdigest() != app["iconSha256"]:
                    return True
        return False
    except (OSError, ValueError, KeyError, TypeError):
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path("apps.json"))
    parser.add_argument("--state", type=Path, default=Path("catalog-source.json"))
    parser.add_argument("--folder-id", default=drive.FOLDER_ID)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    # A partial/private folder fails the job; it can never turn into an empty catalog.
    items = drive.PublicDrive(args.folder_id).list_files()
    changed = args.force or needs_sync(items, args.catalog, args.state, args.folder_id)
    fingerprint = drive.listing_fingerprint(items)
    output = f"changed={str(changed).lower()}\nfingerprint={fingerprint}\n"
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(output)
    print(f"Folder contains {len(items)} APKs; " + ("verification required" if changed else "unchanged, no APK downloads"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
