#!/usr/bin/env python3
"""Build apps.json from a public Yandex Disk folder using Android SDK tools.

Only the SDK's aapt2 and apksigner read APKs; APK code is never executed.
The output is replaced only after a complete, verified, stable folder scan.
"""

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_PUBLIC_URL = "https://disk.yandex.ru/d/-LpAmZavtLNwTA"
API_ROOT = "https://cloud-api.yandex.net/v1/disk/public/resources"
MAX_APK_BYTES = 1024 * 1024 * 1024
QUARANTINED_HASHES = frozenset({
    "ae3aa779708fc2d32c298871af6cc139ca4864c18bf8275a22d1726da323a8bc",
    "8525943680e10080489107c6a52df13dc459c832c3ee8f550ad0ada669bac74c",
    "3d84816985f927cc9aecae2015206423d431e402a4a8cc2603e210a21d5620e1",
})
QUARANTINED_VERSIONS = frozenset({
    ("com.spocky.projengmenu", 92),
    ("jp.snowlife01.android.appkiller2", 43),
    ("com.play.pandafref", 1001),
})
HEX256 = re.compile(r"[0-9a-f]{64}\Z")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z")


class SyncError(Exception):
    """An incomplete or unverified input must not replace the catalog."""


@dataclass(frozen=True)
class RemoteApk:
    path: str
    name: str
    sha256: str
    size_bytes: int
    md5: str | None = None


def require_https(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise SyncError("An HTTPS URL without credentials is required")
    return url


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class YandexApi:
    def __init__(self, public_url=DEFAULT_PUBLIC_URL):
        self.public_url = require_https(public_url)
        self.opener = urllib.request.build_opener(HttpsRedirectHandler())

    def _json(self, endpoint, **params):
        url = endpoint + "?" + urllib.parse.urlencode({"public_key": self.public_url, **params})
        for attempt in range(4):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "EZCH-Catalog-Sync/1", "Accept": "application/json"})
                with self.opener.open(request, timeout=45) as response:
                    raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise SyncError("Yandex metadata response is too large")
                result = json.loads(raw)
                if not isinstance(result, dict) or result.get("error"):
                    raise SyncError("Yandex returned an invalid resource response")
                return result
            except urllib.error.HTTPError as error:
                if error.code not in (429, 500, 502, 503, 504) or attempt == 3:
                    raise SyncError(f"Yandex API failed: HTTP {error.code}") from error
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
                if attempt == 3:
                    raise SyncError(f"Yandex API request failed: {type(error).__name__}") from error
            time.sleep(2 ** attempt)
        raise SyncError("Yandex API request failed")

    def list_page(self, path, offset, limit):
        return self._json(API_ROOT, path=path, offset=offset, limit=limit, sort="name")

    def download(self, resource, destination):
        # Resolve a fresh signed download link on each request; never publish it.
        href = self._json(API_ROOT + "/download", path=resource.path).get("href")
        if not isinstance(href, str):
            raise SyncError(f"Missing download link: {resource.path}")
        request = urllib.request.Request(require_https(href), headers={"User-Agent": "EZCH-Catalog-Sync/1"})
        try:
            with self.opener.open(request, timeout=120) as response, Path(destination).open("wb") as target:
                written = 0
                while chunk := response.read(1024 * 1024):
                    written += len(chunk)
                    if written > resource.size_bytes:
                        raise SyncError(f"Download exceeds its declared size: {resource.path}")
                    target.write(chunk)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise SyncError(f"APK download failed: {resource.path} ({type(error).__name__})") from error


def public_path(value):
    if not isinstance(value, str) or not value.startswith("/") or "\\" in value:
        raise SyncError("Invalid public resource path")
    if "\x00" in value or any(part in (".", "..") for part in value.split("/")):
        raise SyncError("Invalid public resource path")
    return value


def collect_apks(api, page_size=100):
    if not 1 <= page_size <= 1000:
        raise SyncError("Page size must be between 1 and 1000")
    pending, visited, paths, apks = ["/"], set(), set(), []
    while pending:
        directory = pending.pop()
        if directory in visited:
            raise SyncError("Repeated directory in Yandex response")
        visited.add(directory)
        offset, expected_total = 0, None
        while True:
            page = api.list_page(directory, offset, page_size)
            embedded = page.get("_embedded") if isinstance(page, dict) else None
            if not isinstance(embedded, dict) or not isinstance(embedded.get("items"), list):
                raise SyncError(f"Incomplete directory listing: {directory}")
            if (page.get("path", directory) != directory or
                    embedded.get("path", directory) != directory):
                raise SyncError("Yandex returned a different directory than requested")
            total = embedded.get("total")
            if type(total) is not int or total < 0 or embedded.get("offset") != offset:
                raise SyncError(f"Invalid pagination metadata: {directory}")
            if expected_total is not None and total != expected_total:
                raise SyncError("Folder changed during pagination; retry on the next run")
            expected_total = total
            items = embedded["items"]
            if len(items) > page_size or offset + len(items) > total or (not items and offset < total):
                raise SyncError(f"Truncated directory listing: {directory}")
            for item in items:
                if not isinstance(item, dict):
                    raise SyncError("Invalid resource metadata")
                path = public_path(item.get("path"))
                if str(PurePosixPath(path).parent) != directory or path in paths:
                    raise SyncError("Duplicate resource or resource outside its listed directory")
                paths.add(path)
                if item.get("type") == "dir":
                    pending.append(path)
                elif item.get("type") == "file":
                    name = item.get("name")
                    if not isinstance(name, str) or not name:
                        raise SyncError("A file has no name")
                    if not name.lower().endswith(".apk"):
                        continue
                    sha256, size, md5 = item.get("sha256"), item.get("size"), item.get("md5")
                    if not isinstance(sha256, str) or not HEX256.fullmatch(sha256.lower()) or type(size) is not int or not 0 < size <= MAX_APK_BYTES:
                        raise SyncError(f"APK has no valid SHA-256/size: {path}")
                    sha256 = sha256.lower()
                    if md5 is not None:
                        if not isinstance(md5, str) or not re.fullmatch(r"[0-9a-fA-F]{32}", md5):
                            raise SyncError(f"APK has an invalid MD5: {path}")
                        md5 = md5.lower()
                    apks.append(RemoteApk(path, name, sha256, size, md5))
                else:
                    raise SyncError("Unknown Yandex resource type")
            offset += len(items)
            if offset == total:
                break
    return sorted(apks, key=lambda resource: resource.path)


def verify_file(path, resource):
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size != resource.size_bytes:
        raise SyncError(f"APK size mismatch: {resource.path}")
    sha256, md5 = hashlib.sha256(), hashlib.md5()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            sha256.update(chunk)
            md5.update(chunk)
    if sha256.hexdigest() != resource.sha256 or (resource.md5 and md5.hexdigest() != resource.md5):
        raise SyncError(f"APK checksum mismatch: {resource.path}")


class SdkInspector:
    def __init__(self, api, cache_dir, aapt2, apksigner_jar, java, seed_dirs=()):
        self.api, self.cache_dir = api, Path(cache_dir)
        self.aapt2, self.apksigner_jar, self.java = str(aapt2), str(apksigner_jar), str(java)
        self.seed_dirs = [Path(path) for path in seed_dirs]
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _run(self, command):
        try:
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise SyncError(f"Android SDK inspection failed: {type(error).__name__}") from error
        if result.returncode != 0:
            raise SyncError("Android SDK rejected an APK: " + (result.stderr or result.stdout)[-1200:])
        return result.stdout

    def _metadata(self, path):
        badging = self._run([self.aapt2, "dump", "badging", str(path)])
        package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
        attributes = dict(re.findall(r"(\w+)='([^']*)'", package_line))
        package, code = attributes.get("name", ""), attributes.get("versionCode", "")
        if not PACKAGE.fullmatch(package) or not code.isdecimal() or int(code) <= 0:
            raise SyncError("APK has no valid package/versionCode")
        major = attributes.get("versionCodeMajor", "0")
        if not major.isdecimal():
            raise SyncError("APK has an invalid versionCodeMajor")
        version_code = (int(major) << 32) | int(code)
        labels = re.findall(r"^application-label:'(.*)'$", badging, flags=re.MULTILINE)
        if not labels or not labels[0].strip():
            raise SyncError(f"APK has no application label: {package}")
        signature = self._run([self.java, "-jar", self.apksigner_jar, "verify", "--verbose", "--print-certs", str(path)])
        signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", signature, flags=re.MULTILINE)
        if len(signers) != 1:
            # The client schema pins one current signer; do not silently discard co-signers.
            raise SyncError(f"APK must have exactly one verified current signer: {package}")
        return {"name": labels[0], "packageName": package, "versionCode": version_code,
                "versionName": attributes.get("versionName") or str(version_code), "signerSha256": signers[0].lower()}

    def inspect(self, resource):
        candidates = sorted(self.cache_dir.glob(resource.sha256 + "-*.apk"))
        for root in self.seed_dirs:
            if root.is_dir():
                # Seeds are optional local files and are trusted only after full checksum verification.
                candidates.extend(path for path in root.rglob("*.apk") if path.is_file() and path.stat().st_size == resource.size_bytes)
            elif root.is_file():
                candidates.append(root)
        for path in candidates:
            try:
                verify_file(path, resource)
            except SyncError:
                continue
            metadata = self._metadata(path)
            destination = self.cache_dir / (resource.sha256 + "-" + metadata["signerSha256"] + ".apk")
            if path.resolve() != destination.resolve():
                self._copy_verified(path, destination, resource)
            print(f"Verified cached APK: {resource.path}", file=sys.stderr)
            return metadata
        fd, temporary = tempfile.mkstemp(prefix="download-", suffix=".apk", dir=self.cache_dir)
        os.close(fd)
        temporary = Path(temporary)
        try:
            self.api.download(resource, temporary)
            verify_file(temporary, resource)
            metadata = self._metadata(temporary)
            destination = self.cache_dir / (resource.sha256 + "-" + metadata["signerSha256"] + ".apk")
            os.replace(temporary, destination)
            print(f"Verified new APK: {resource.path}", file=sys.stderr)
            return metadata
        finally:
            temporary.unlink(missing_ok=True)

    @staticmethod
    def _copy_verified(source, destination, resource):
        fd, temporary = tempfile.mkstemp(prefix="seed-", suffix=".apk", dir=destination.parent)
        os.close(fd)
        try:
            shutil.copyfile(source, temporary)
            verify_file(temporary, resource)
            os.replace(temporary, destination)
        finally:
            Path(temporary).unlink(missing_ok=True)


def build_catalog(resources, inspector, public_url=DEFAULT_PUBLIC_URL):
    require_https(public_url)
    packages = {}
    for resource in resources:
        if resource.sha256.lower() in QUARANTINED_HASHES:
            print(f"Quarantined hash excluded: {resource.path}", file=sys.stderr)
            continue
        metadata = inspector.inspect(resource)
        package, code, signer = metadata.get("packageName", ""), metadata.get("versionCode"), metadata.get("signerSha256", "")
        if not PACKAGE.fullmatch(package) or type(code) is not int or code <= 0 or not HEX256.fullmatch(signer):
            raise SyncError("APK inspection returned invalid package/version/signer")
        if not isinstance(metadata.get("name"), str) or not metadata["name"].strip() or not isinstance(metadata.get("versionName"), str) or not metadata["versionName"]:
            raise SyncError("APK inspection returned an invalid label/versionName")
        if (package, code) in QUARANTINED_VERSIONS:
            print(f"Quarantined package/version excluded: {resource.path}", file=sys.stderr)
            continue
        app = {"name": metadata["name"], "packageName": package, "versionCode": code,
               "versionName": metadata["versionName"], "apkUrl": public_url, "apkPath": resource.path,
               "sha256": resource.sha256, "sizeBytes": resource.size_bytes, "signerSha256": signer}
        previous = packages.get(package)
        # A deterministic path/hash tie-break keeps same-version duplicates stable.
        if previous is None or (-code, resource.path, resource.sha256) < (-previous["versionCode"], previous["apkPath"], previous["sha256"]):
            packages[package] = app
    return sorted(packages.values(), key=lambda app: (app["name"].casefold(), app["packageName"]))


def write_catalog(output, apps):
    output = Path(output)
    encoded = (json.dumps({"apps": apps}, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if output.exists() and output.read_bytes() == encoded:
        return False
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".apps-", suffix=".json", dir=output.parent)
    try:
        with os.fdopen(fd, "wb") as target:
            target.write(encoded)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return True


def preserve_project_releases(apps, output):
    path = Path(output)
    if not path.exists():
        return apps
    previous = json.loads(path.read_text(encoding="utf-8-sig"))["apps"]
    releases = {}
    for item in previous:
        if item.get("packageName") not in {"com.example.ezchupdate", "com.example.homeezch.usb"}:
            continue
        if not item.get("apkUrl", "").startswith("https://github.com/aidar4ik93/EZCH-Update/releases/download/"):
            continue
        if (not HEX256.fullmatch(item.get("sha256", "")) or
                not HEX256.fullmatch(item.get("signerSha256", "")) or
                type(item.get("sizeBytes")) is not int or item["sizeBytes"] <= 0 or
                type(item.get("versionCode")) is not int or item["versionCode"] <= 0):
            raise SyncError("Project release metadata is incomplete; existing catalog preserved")
        releases[item["packageName"]] = item
    return list(releases.values()) + [item for item in apps if item["packageName"] not in releases]


def sync_catalog(api, inspector, output, public_url=DEFAULT_PUBLIC_URL, page_size=100, keep_project_releases=False):
    resources = collect_apks(api, page_size)
    apps = build_catalog(resources, inspector, public_url)
    if collect_apks(api, page_size) != resources:
        raise SyncError("Folder changed during APK verification; catalog preserved")
    if keep_project_releases:
        apps = preserve_project_releases(apps, output)
    changed = write_catalog(output, apps)
    print(f"Catalog {'updated' if changed else 'unchanged'}: {len(apps)} entries from {len(resources)} APKs")
    return apps


def sdk_tools(sdk_root=None):
    root = Path(sdk_root or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    directories = list((root / "build-tools").glob("*")) if root.is_dir() else []
    directories.sort(key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name)), reverse=True)
    extension = ".exe" if os.name == "nt" else ""
    for directory in directories:
        aapt2, jar = directory / ("aapt2" + extension), directory / "lib" / "apksigner.jar"
        if aapt2.is_file() and jar.is_file():
            java_home = os.environ.get("JAVA_HOME")
            java = str(Path(java_home) / "bin" / ("java" + extension)) if java_home else shutil.which("java")
            if java and Path(java).is_file():
                return aapt2, jar, java
    raise SyncError("Android SDK build-tools (aapt2, apksigner) and Java are required")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-url", default=DEFAULT_PUBLIC_URL)
    parser.add_argument("--output", type=Path, default=Path("apps.json"))
    parser.add_argument("--cache-dir", type=Path, default=Path(".local/yandex-catalog-cache"))
    parser.add_argument("--sdk", help="Android SDK root (or ANDROID_HOME/ANDROID_SDK_ROOT)")
    parser.add_argument("--seed-dir", type=Path, action="append", default=[], help="Optional local APK directory/file; checksum and signer are reverified")
    parser.add_argument("--page-size", type=int, default=100)
    parser.add_argument("--keep-project-releases", action="store_true", help="Keep the published GitHub updater and launcher entries")
    args = parser.parse_args()
    try:
        api = YandexApi(args.public_url)
        inspector = SdkInspector(api, args.cache_dir, *sdk_tools(args.sdk), args.seed_dir)
        sync_catalog(api, inspector, args.output, args.public_url, args.page_size, args.keep_project_releases)
        return 0
    except (SyncError, OSError) as error:
        print(f"Catalog sync failed; existing output preserved: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
