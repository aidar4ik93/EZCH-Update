#!/usr/bin/env python3
"""Verify public Google Drive APKs and atomically publish a complete catalog.

No Google login or secret is shipped in the Android application. Discovery uses
Drive v3 with pagination; HTML folder scraping is deliberately unsupported.
"""
import argparse
from datetime import datetime
import urllib.parse
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

def parse_files(entries, folder_id):
    files, seen = [], set()
    for item in entries:
        file_id, parents, name, mime = (item.get(key) for key in ("id", "parents", "name", "mimeType"))
        if not isinstance(file_id, str) or not ID.fullmatch(file_id) or file_id in seen or parents != [folder_id]:
            raise SyncError("Invalid/duplicate Drive ID or file outside the source folder")
        seen.add(file_id)
        if mime == "application/vnd.google-apps.folder":
            raise SyncError("Nested folders require a complete recursive scan; existing catalog preserved")
        if not isinstance(name, str) or not name:
            raise SyncError("Google Drive file has no name")
        if not name.lower().endswith(".apk"):
            continue
        try:
            size = int(item["size"])
            modified = int(datetime.fromisoformat(item["modifiedTime"].replace("Z", "+00:00")).timestamp() * 1000)
        except (KeyError, ValueError, TypeError) as error:
            raise SyncError("Incomplete Drive metadata") from error
        if not 0 < size <= common.MAX_APK_BYTES:
            raise SyncError("Google Drive APK has invalid size/modification metadata")
        files.append(DriveFile(file_id, name, size, modified))
    return sorted(files, key=lambda item: item.id)

class DriveApi:
    def __init__(self, folder_id=FOLDER_ID, api_key=None, access_token=None, reader=None):
        if not ID.fullmatch(folder_id):
            raise SyncError("Invalid source folder ID")
        self.folder_id = folder_id
        self.api_key = api_key or os.environ.get("GOOGLE_DRIVE_API_KEY")
        self.access_token = access_token or os.environ.get("GOOGLE_DRIVE_ACCESS_TOKEN")
        self.reader = reader

    def _page(self, params):
        if self.reader:
            return self.reader(params)
        if not self.api_key and not self.access_token:
            raise SyncError("Configure server-side GOOGLE_DRIVE_API_KEY (public folder) or GOOGLE_DRIVE_ACCESS_TOKEN; catalog preserved")
        if self.api_key and not self.access_token:
            params = {**params, "key": self.api_key}
        headers = {"User-Agent": "EZCH-Catalog-Sync/3"}
        if self.access_token:
            headers["Authorization"] = "Bearer " + self.access_token
        request = urllib.request.Request("https://www.googleapis.com/drive/v3/files?" + urllib.parse.urlencode(params), headers=headers)
        # Never forward Authorization to a redirect host.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args): return None
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024: raise SyncError("Drive response exceeds limit")
            return json.loads(raw)
        except urllib.error.HTTPError as error:
            raise SyncError("Drive API HTTP " + str(error.code) + "; catalog preserved") from None

    def list_files(self):
        entries, token, tokens = [], None, set()
        while True:
            params = {"q": "'" + self.folder_id + "' in parents and trashed = false", "pageSize": 1000,
                "fields": "nextPageToken,incompleteSearch,files(id,name,mimeType,size,modifiedTime,parents)",
                "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"}
            if token: params["pageToken"] = token
            page = self._page(params)
            if page.get("incompleteSearch") or not isinstance(page.get("files"), list):
                raise SyncError("Incomplete Drive API search; catalog preserved")
            entries.extend(page["files"])
            if len(entries) > 10000: raise SyncError("Folder exceeds supported size")
            token = page.get("nextPageToken")
            if not token: break
            if not isinstance(token, str) or token in tokens: raise SyncError("Invalid Drive pagination")
            tokens.add(token)
        return parse_files(entries, self.folder_id)

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
        return [app for app in apps if app["packageName"] != "com.example.ezchupdate" or
                (app.get("versionCode", 0) >= 26 and app.get("apkUrl", "").startswith("https://github.com/aidar4ik93/EZCH-Update/releases/download/"))]
    return apps

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
                    icon_spec = importlib.util.spec_from_file_location("catalog_icons", Path(__file__).with_name("catalog-icons.py"))
                    icons = importlib.util.module_from_spec(icon_spec); icon_spec.loader.exec_module(icons)
                    icon_path = Path(output).parent / "catalog-icons" / (sha + ".png")
                    try:
                        icon_hash = icons.extract(path, inspector.aapt2, icon_path)
                        app.update(iconUrl="https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/catalog-icons/" + icon_path.name, iconSha256=icon_hash)
                    except (icons.IconError, OSError) as error:
                        print("Icon unavailable for " + item.name + ": " + str(error), file=sys.stderr)
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
    # Once the TV edition is published, the old companion must not reappear
    # merely because an archived APK remains in the source folder.
    apps = active_project_catalog(apps)
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
        sync(DriveApi(args.folder_id), args.cache_dir, args.output, args.sdk, args.keep_project_releases)
    except (SyncError, OSError, ValueError) as error:
        print("Google Drive synchronization failed: " + str(error), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
