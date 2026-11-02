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
import shutil

spec = importlib.util.spec_from_file_location("catalog_common", Path(__file__).with_name("sync-yandex-catalog.py"))
common = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = common
spec.loader.exec_module(common)
icon_spec = importlib.util.spec_from_file_location("catalog_icons", Path(__file__).with_name("catalog-icons.py"))
icons = importlib.util.module_from_spec(icon_spec)
icon_spec.loader.exec_module(icons)
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

def active_project_catalog(apps):
    if any(app["packageName"] == "com.example.ezchupdate.tvtest" for app in apps):
        # Keep a deliberately published legacy release so existing installations
        # can update in place; do not revive an obsolete APK merely left in Drive.
        return [app for app in apps if app["packageName"] != "com.example.ezchupdate" or
                app.get("apkUrl", "").startswith("https://github.com/aidar4ik93/EZCH-Update/releases/download/")]
    return apps

ICON_BASE = "https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/catalog-icons/"


class IconBatch:
    """Stage icons until the whole scan succeeds; a failed scan never removes assets."""
    def __init__(self, output, staging):
        self.directory = Path(output).resolve().parent / "catalog-icons"
        self.staging = Path(staging)
        self.attempted = set()

    def attach(self, app, apk, aapt2):
        self.attempted.add(app["sha256"])
        app.pop("iconUrl", None)
        app.pop("iconSha256", None)
        name = app["sha256"] + ".png"
        destination = self.staging / name
        existing = self.directory / name
        try:
            if not destination.exists() and existing.exists():
                try:
                    icons.png_digest(existing)
                    shutil.copyfile(existing, destination)
                except (icons.IconError, OSError, ValueError, SyntaxError):
                    pass  # Re-render a damaged local asset from the verified APK.
            if destination.exists():
                digest = icons.png_digest(destination)
            else:
                digest = icons.extract(apk, aapt2, destination)
            app.update(iconUrl=ICON_BASE + name, iconSha256=digest)
        except (icons.IconError, OSError, ValueError, SyntaxError) as error:
            destination.unlink(missing_ok=True)
            print("Icon unavailable for " + app["name"] + ": " + str(error).replace("\n", " "), flush=True)

    def retained(self, app, inspector):
        """Retained project APKs must match the published bytes and signer too."""
        name = app["sha256"] + ".png"
        if (self.staging / name).exists() or (self.directory / name).exists():
            self.attach(app, None, inspector.aapt2)
            if app.get("iconUrl"): return
        path = self.staging / (app["sha256"] + ".apk")
        try:
            opener = urllib.request.build_opener(common.HttpsRedirectHandler())
            digest, size = hashlib.sha256(), 0
            with opener.open(app["apkUrl"], timeout=90) as response, path.open("wb") as target:
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > app["sizeBytes"]: raise icons.IconError("Retained APK exceeds published size")
                    digest.update(chunk); target.write(chunk)
            if size != app["sizeBytes"] or digest.hexdigest() != app["sha256"]:
                raise icons.IconError("Retained APK checksum/size mismatch")
            metadata = inspector._metadata(path)
            if any(metadata.get(key) != app.get(key) for key in ("packageName", "versionCode", "signerSha256")):
                raise icons.IconError("Retained APK metadata differs")
            self.attach(app, path, inspector.aapt2)
        except (OSError, ValueError, SyncError) as error:
            app.pop("iconUrl", None); app.pop("iconSha256", None)
            print("Icon unavailable for " + app["name"] + ": " + str(error).replace("\n", " "), flush=True)
        finally:
            path.unlink(missing_ok=True)

    def publish(self, apps, output):
        self.directory.mkdir(parents=True, exist_ok=True)
        wanted = {app["sha256"] + ".png" for app in apps if app.get("iconUrl")}
        for name in wanted:
            source = self.staging / name
            if source.exists(): source.replace(self.directory / name)
        # Referenced assets exist before the JSON can become visible.
        changed = common.write_catalog(output, apps)
        for path in self.directory.glob("*.png"):
            if path.name not in wanted: path.unlink()
        return changed


def sync(api, cache, output, sdk, keep_project_releases=True):
    Path(cache).mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="icons-", dir=cache) as staging:
        return _sync(api, cache, output, sdk, keep_project_releases, IconBatch(output, staging))


def _sync(api, cache, output, sdk, keep_project_releases, batch):
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
                    try:
                        metadata = inspector._metadata(path)
                    except SyncError as error:
                        # Reject this APK, not the entire folder. SDK/tool failures
                        # remain fatal so an environment outage cannot empty the catalog.
                        if str(error).startswith("Android SDK inspection failed:"):
                            raise
                        print("Unverified APK skipped: " + item.name + ": " +
                              str(error).replace("\n", " "), flush=True)
                        continue
                    if (metadata["packageName"], metadata["versionCode"]) in common.QUARANTINED_VERSIONS:
                        print("Quarantined APK version excluded: " + item.name)
                        continue
                    app = {**metadata, "apkUrl": download_url(item.id), "apkPath": "/" + item.name,
                        "sha256": sha, "sizeBytes": item.size}
                    batch.attach(app, path, inspector.aapt2)
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
    if not packages:
        raise SyncError("No verified APKs found; existing catalog preserved")
    apps = sorted(packages.values(), key=lambda app: (app["name"].casefold(), app["packageName"]))
    if keep_project_releases:
        apps = common.preserve_project_releases(apps, output)
    # Once the TV edition is published, the old companion must not reappear
    # merely because an archived APK remains in the source folder.
    # Preservation already resolves legacy/project versions and signer continuity.
    if not keep_project_releases:
        apps = active_project_catalog(apps)
    for app in apps:
        if app["sha256"] not in batch.attempted:
            batch.retained(app, inspector)
        elif (batch.staging / (app["sha256"] + ".png")).exists():
            batch.attach(app, None, inspector.aapt2)
        else:
            app.pop("iconUrl", None); app.pop("iconSha256", None)
    changed = batch.publish(apps, output)
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

