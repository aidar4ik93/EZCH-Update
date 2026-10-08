#!/usr/bin/env python3
"""Verify public Google Drive APKs and atomically publish a complete catalog.

No Google login or secret is shipped in the Android application. Public-folder
HTML is a compatibility fallback: unknown or paginated responses fail closed.
"""
import argparse
import ast
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile
import urllib.request
import os

spec = importlib.util.spec_from_file_location("catalog_common", Path(__file__).with_name("sync-yandex-catalog.py"))
common = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = common
spec.loader.exec_module(common)
SyncError = common.SyncError
FOLDER_ID = "1lC9hjcz7phWG-fQADjO54aH9Wg2RPrrB"
FOLDER_URL = "https://drive.google.com/drive/folders/" + FOLDER_ID
ID = re.compile(r"[A-Za-z0-9_-]{10,}\Z")

@dataclass(frozen=True)
class DriveFile:
    id: str
    name: str
    size: int
    modified: int

def download_url(file_id):
    if not ID.fullmatch(file_id):
        raise SyncError("Invalid Google Drive file ID")
    return "https://drive.usercontent.google.com/download?id=" + file_id + "&export=download&confirm=t"

def parse_folder(source, folder_id):
    match = re.search(r"window\['_DRIVE_ivd'\]\s*=\s*('(?:[^'\\]|\\.)*');", source)
    if not match:
        raise SyncError("Google Drive folder is private or its public listing format changed; catalog preserved")
    try:
        payload = json.loads(ast.literal_eval(match[1].replace(r"\/", "/")))
    except (ValueError, SyntaxError, TypeError) as error:
        raise SyncError("Invalid Google Drive listing") from error
    if not isinstance(payload, list) or len(payload) < 2 or not isinstance(payload[0], list):
        raise SyncError("Invalid Google Drive folder response")
    if payload[1] is not None or len(payload[0]) >= 50:
        raise SyncError("Google Drive listing needs pagination; catalog preserved rather than truncated")
    files, seen = [], set()
    for item in payload[0]:
        if not isinstance(item, list) or len(item) < 14:
            raise SyncError("Incomplete Google Drive file metadata")
        file_id, parents, name, mime = item[:4]
        if not isinstance(file_id, str) or not ID.fullmatch(file_id) or file_id in seen or parents != [folder_id]:
            raise SyncError("Invalid/duplicate Drive ID or file outside the source folder")
        seen.add(file_id)
        if mime == "application/vnd.google-apps.folder":
            raise SyncError("Nested folders require a complete recursive scan; existing catalog preserved")
        if not isinstance(name, str) or not name:
            raise SyncError("Google Drive file has no name")
        if not name.lower().endswith(".apk"):
            continue
        size, modified = item[13], item[10]
        if type(size) is not int or not 0 < size <= common.MAX_APK_BYTES or type(modified) is not int:
            raise SyncError("Google Drive APK has invalid size/modification metadata")
        files.append(DriveFile(file_id, name, size, modified))
    if not files:
        raise SyncError("No public APKs found; existing catalog preserved")
    return sorted(files, key=lambda item: item.id)

class PublicDrive:
    def __init__(self, folder_id=FOLDER_ID):
        if not ID.fullmatch(folder_id):
            raise SyncError("Invalid source folder ID")
        self.folder_id = folder_id

    def list_files(self):
        request = urllib.request.Request("https://drive.google.com/drive/folders/" + self.folder_id,
            headers={"User-Agent": "EZCH-Catalog-Sync/2", "Cache-Control": "no-cache"})
        opener = urllib.request.build_opener(common.HttpsRedirectHandler())
        with opener.open(request, timeout=45) as response:
            raw = response.read(16 * 1024 * 1024 + 1)
        if len(raw) > 16 * 1024 * 1024:
            raise SyncError("Google Drive listing is too large")
        return parse_folder(raw.decode("utf-8"), self.folder_id)

    def download(self, item, cache):
        # Download bytes afresh: same-name/same-size replacements must not reuse old hashes.
        fd, path = tempfile.mkstemp(prefix="drive-", suffix=".apk", dir=cache)
        os.close(fd)
        path = Path(path)
        try:
            opener = urllib.request.build_opener(common.HttpsRedirectHandler())
            request = urllib.request.Request(download_url(item.id), headers={"User-Agent": "EZCH-Catalog-Sync/2"})
            digest, size = hashlib.sha256(), 0
            with opener.open(request, timeout=90) as response, path.open("wb") as output:
                if "text/html" in response.headers.get("Content-Type", "").lower():
                    raise SyncError("Google Drive did not return APK bytes; check public access/download quota: " + item.name)
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > item.size:
                        raise SyncError("Google Drive APK exceeds listed size: " + item.name)
                    digest.update(chunk)
                    output.write(chunk)
            if size != item.size:
                raise SyncError("Google Drive APK download is incomplete: " + item.name)
            with path.open("rb") as source:
                if source.read(4) != b"PK\x03\x04":
                    raise SyncError("Google Drive returned a page instead of APK: " + item.name)
            return item, path, digest.hexdigest()
        except BaseException:
            path.unlink(missing_ok=True)
            raise

def sync(api, cache, output, sdk, keep_project_releases=True):
    cache = Path(cache); cache.mkdir(parents=True, exist_ok=True)
    items = api.list_files()
    inspector = common.SdkInspector(api, cache, *common.sdk_tools(sdk))
    apps, packages = [], {}
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(api.download, item, cache) for item in items]
        try:
            for future in futures:
                item, path, sha = future.result()
                try:
                    if sha in common.QUARANTINED_HASHES:
                        print("Quarantined APK excluded: " + item.name)
                        continue
                    metadata = inspector._metadata(path)
                    if (metadata["packageName"], metadata["versionCode"]) in common.QUARANTINED_VERSIONS:
                        print("Quarantined APK version excluded: " + item.name)
                        continue
                    app = {**metadata, "apkUrl": download_url(item.id), "apkPath": "/" + item.name,
                        "sha256": sha, "sizeBytes": item.size}
                    previous = packages.get(app["packageName"])
                    if previous is None or (-app["versionCode"], app["apkUrl"]) < (-previous["versionCode"], previous["apkUrl"]):
                        packages[app["packageName"]] = app
                    print("Verified Google Drive APK: " + item.name, flush=True)
                finally:
                    path.unlink(missing_ok=True)
        finally:
            # Also clean completed downloads if an earlier verification failed.
            for future in futures:
                try: future.result()[1].unlink(missing_ok=True)
                except Exception: pass
    if api.list_files() != items:
        raise SyncError("Google Drive folder changed during verification; catalog preserved")
    apps = sorted(packages.values(), key=lambda app: (app["name"].casefold(), app["packageName"]))
    if keep_project_releases:
        apps = common.preserve_project_releases(apps, output)
    changed = common.write_catalog(output, apps)
    print(f"Google Drive catalog {'updated' if changed else 'unchanged'}: {len(apps)} entries from {len(items)} APKs")
    return apps

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder-id", default=FOLDER_ID)
    parser.add_argument("--output", type=Path, default=Path("apps.json"))
    parser.add_argument("--cache-dir", type=Path, default=Path(".local/google-drive-apks"))
    parser.add_argument("--sdk")
    parser.add_argument("--keep-project-releases", action="store_true")
    args = parser.parse_args()
    try:
        sync(PublicDrive(args.folder_id), args.cache_dir, args.output, args.sdk, args.keep_project_releases)
    except (SyncError, OSError, ValueError) as error:
        print("Google Drive synchronization failed: " + str(error), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
