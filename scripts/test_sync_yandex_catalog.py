"""Offline regression checks for the public Yandex APK catalogue synchronizer."""

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("sync-yandex-catalog.py")
SPEC = importlib.util.spec_from_file_location("sync_yandex_catalog", MODULE_PATH)
sync = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sync
SPEC.loader.exec_module(sync)

SIGNER = "1" * 64

class ProjectReleaseTests(unittest.TestCase):
    def test_release_survives_folder_sync(self):
        release = {"name": "EZCH Update", "packageName": "com.example.ezchupdate", "versionCode": 19,
                   "versionName": "1.5.1", "sha256": "2" * 64, "signerSha256": SIGNER, "sizeBytes": 200,
                   "apkUrl": "https://github.com/aidar4ik93/EZCH-Update/releases/download/v1.5.1/test.apk"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "apps.json"
            path.write_text(json.dumps({"apps": [release]}), encoding="utf-8")
            result = sync.preserve_project_releases([dict(release, versionCode=15)], path)
            self.assertEqual([release], result)

    def test_unverified_release_does_not_replace_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "apps.json"
            path.write_text(json.dumps({"apps": [{"packageName": "com.example.ezchupdate",
                "apkUrl": "https://github.com/aidar4ik93/EZCH-Update/releases/download/v1.5.1/test.apk"}]}), encoding="utf-8")
            with self.assertRaises(sync.SyncError):
                sync.preserve_project_releases([], path)
QUARANTINED = (
    ("ae3aa779708fc2d32c298871af6cc139ca4864c18bf8275a22d1726da323a8bc", "com.spocky.projengmenu", 92),
    ("8525943680e10080489107c6a52df13dc459c832c3ee8f550ad0ada669bac74c", "jp.snowlife01.android.appkiller2", 43),
    ("3d84816985f927cc9aecae2015206423d431e402a4a8cc2603e210a21d5620e1", "com.play.pandafref", 1001),
)


def digest(content):
    return hashlib.sha256(content).hexdigest()


def remote(path, content=b"apk test fixture", sha256=None, md5=None):
    return sync.RemoteApk(
        path=path,
        name=path.rsplit("/", 1)[-1],
        sha256=sha256 or digest(content),
        size_bytes=len(content),
        md5=md5,
    )


def file_item(resource):
    item = {
        "type": "file",
        "path": resource.path,
        "name": resource.name,
        "sha256": resource.sha256,
        "size": resource.size_bytes,
    }
    if resource.md5 is not None:
        item["md5"] = resource.md5
    return item


def metadata(package="org.example.app", version=1, name="Fixture", version_name=None):
    return {
        "name": name,
        "packageName": package,
        "versionCode": version,
        "versionName": version_name or str(version),
        "signerSha256": SIGNER,
    }


class FakeApi:
    def __init__(self, folders=None):
        self.folders = folders or {"/": []}
        self.calls = []

    def list_page(self, path, offset, limit):
        path = path or "/"
        self.calls.append((path, offset, limit))
        items = self.folders[path]
        return {
            "path": path,
            "_embedded": {
                "items": items[offset:offset + limit],
                "offset": offset,
                "limit": limit,
                "total": len(items),
            },
        }


class FakeInspector:
    def __init__(self, by_hash=None):
        self.by_hash = by_hash or {}
        self.calls = []

    def inspect(self, resource):
        self.calls.append(resource.path)
        return dict(self.by_hash[resource.sha256])


class FakeDownloadApi:
    def __init__(self, content):
        self.content = content
        self.downloads = []

    def download(self, resource, destination):
        self.downloads.append(resource.path)
        Path(destination).write_bytes(self.content)


class RecordingSdkInspector(sync.SdkInspector):
    """Exercise cache/checksum code while replacing the two SDK subprocesses."""

    def __init__(self, api, cache_dir):
        super().__init__(api, cache_dir, "fake-aapt2", "fake-apksigner.jar", "fake-java")
        self.commands = []
        self.reject_signature = False

    def _run(self, command):
        self.commands.append(command)
        if command[0] == "fake-aapt2":
            return "package: name='org.example.fixture' versionCode='42' versionName='4.2'\napplication-label:'SDK fixture'\n"
        if self.reject_signature:
            raise sync.SyncError("Android SDK rejected an APK signature")
        return "Verifies\nSigner #1 certificate SHA-256 digest: " + SIGNER + "\n"


class CatalogSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "apps.json"

    def keep_previous_catalog(self):
        previous = b'{"apps":[{"name":"keep the previous complete catalogue"}]}\n'
        self.output.write_bytes(previous)
        return previous

    def read_apps(self):
        return json.loads(self.output.read_text(encoding="utf-8"))["apps"]

    def test_recursive_pagination_uses_every_page_and_only_apks(self):
        root_apk = remote("/root.apk", b"root")
        first = remote("/TV/first.apk", b"first")
        last = remote("/TV/sub/last.APK", b"last")
        api = FakeApi({
            "/": [
                {"type": "dir", "name": "TV", "path": "/TV"},
                {"type": "file", "name": "notes.txt", "path": "/notes.txt"},
                file_item(root_apk),
            ],
            "/TV": [
                file_item(first),
                {"type": "dir", "name": "sub", "path": "/TV/sub"},
                {"type": "file", "name": "cover.png", "path": "/TV/cover.png"},
            ],
            "/TV/sub": [file_item(last)],
        })

        resources = sync.collect_apks(api, page_size=2)

        self.assertEqual({r.path for r in resources}, {root_apk.path, first.path, last.path})
        self.assertIn(("/", 2, 2), api.calls)
        self.assertIn(("/TV", 2, 2), api.calls)
        self.assertIn(("/TV/sub", 0, 2), api.calls)

    def test_changed_new_and_deleted_files_replace_the_previous_snapshot(self):
        a1 = remote("/A.apk", b"old A")
        a2 = remote("/A.apk", b"updated A")
        b = remote("/B.apk", b"B removed later")
        c = remote("/new/C.apk", b"C newly added")
        inspector = FakeInspector({
            a1.sha256: metadata("org.example.a", 1, "A"),
            a2.sha256: metadata("org.example.a", 2, "A"),
            b.sha256: metadata("org.example.b", 1, "B"),
            c.sha256: metadata("org.example.c", 1, "C"),
        })
        api = FakeApi({"/": [file_item(a1), file_item(b)]})
        sync.sync_catalog(api, inspector, self.output)
        self.assertEqual({a["packageName"] for a in self.read_apps()}, {"org.example.a", "org.example.b"})

        api.folders = {
            "/": [file_item(a2), {"type": "dir", "name": "new", "path": "/new"}],
            "/new": [file_item(c)],
        }
        sync.sync_catalog(api, inspector, self.output)
        updated = {a["packageName"]: a for a in self.read_apps()}
        self.assertEqual(set(updated), {"org.example.a", "org.example.c"})
        self.assertEqual(updated["org.example.a"]["versionCode"], 2)
        self.assertEqual(updated["org.example.a"]["sha256"], a2.sha256)

        api.folders = {"/": [{"type": "dir", "name": "new", "path": "/new"}], "/new": [file_item(c)]}
        sync.sync_catalog(api, inspector, self.output)
        self.assertEqual([a["packageName"] for a in self.read_apps()], ["org.example.c"])

    def test_highest_manifest_version_wins_with_deterministic_path_tie_break(self):
        old = remote("/old.apk", b"old")
        preferred = remote("/A new.apk", b"preferred")
        other = remote("/Z new.apk", b"other")
        inspector = FakeInspector({
            old.sha256: metadata(version=9, version_name="99.0"),
            preferred.sha256: metadata(version=10, version_name="1.0"),
            other.sha256: metadata(version=10, version_name="1.0"),
        })
        forward = sync.build_catalog([old, other, preferred], inspector)
        reverse = sync.build_catalog([preferred, other, old], inspector)

        self.assertEqual(forward, reverse)
        self.assertEqual(len(forward), 1)
        self.assertEqual(forward[0]["versionCode"], 10)
        self.assertEqual(forward[0]["apkPath"], preferred.path)

    def test_catalog_uses_stable_public_folder_and_real_inspected_metadata(self):
        resource = remote("/TV/My APK.apk", b"fixture", md5=hashlib.md5(b"fixture").hexdigest())
        inspector = FakeInspector({resource.sha256: metadata("org.example.real", 42, "Real label", "4.2")})
        public_url = "https://disk.yandex.ru/d/example"

        apps = sync.build_catalog([resource], inspector, public_url=public_url)

        self.assertEqual(apps, [{
            **metadata("org.example.real", 42, "Real label", "4.2"),
            "apkUrl": public_url,
            "apkPath": resource.path,
            "sha256": resource.sha256,
            "sizeBytes": resource.size_bytes,
        }])

    def test_all_quarantined_hashes_are_rejected_before_apk_inspection(self):
        resources = [remote(f"/renamed-{i}.apk", sha256=sha.upper()) for i, (sha, _, _) in enumerate(QUARANTINED)]
        inspector = FakeInspector()

        self.assertEqual(sync.build_catalog(resources, inspector), [])
        self.assertEqual(inspector.calls, [])

    def test_all_quarantined_package_version_pairs_are_rejected_after_inspection(self):
        resources = [remote(f"/replacement-{i}.apk", f"different bytes {i}".encode()) for i in range(len(QUARANTINED))]
        inspector = FakeInspector({
            resource.sha256: metadata(package, version)
            for resource, (_, package, version) in zip(resources, QUARANTINED)
        })

        self.assertEqual(sync.build_catalog(resources, inspector), [])
        self.assertEqual(set(inspector.calls), {r.path for r in resources})

    def test_other_versions_of_quarantined_packages_remain_available(self):
        resources = [remote(f"/new-version-{i}.apk", f"safe new version {i}".encode()) for i in range(len(QUARANTINED))]
        inspector = FakeInspector({
            resource.sha256: metadata(package, version + 1)
            for resource, (_, package, version) in zip(resources, QUARANTINED)
        })

        self.assertEqual(len(sync.build_catalog(resources, inspector)), len(QUARANTINED))

    def test_verified_download_requires_matching_size_sha256_and_optional_md5(self):
        content = b"complete APK fixture"
        apk_file = self.root / "fixture.apk"
        apk_file.write_bytes(content)
        correct = remote("/fixture.apk", content, md5=hashlib.md5(content).hexdigest())
        sync.verify_file(apk_file, correct)

        invalid = (
            sync.RemoteApk(correct.path, correct.name, correct.sha256, correct.size_bytes + 1, correct.md5),
            sync.RemoteApk(correct.path, correct.name, "0" * 64, correct.size_bytes, correct.md5),
            sync.RemoteApk(correct.path, correct.name, correct.sha256, correct.size_bytes, "0" * 32),
        )
        for resource in invalid:
            with self.subTest(resource=resource), self.assertRaises(sync.SyncError):
                sync.verify_file(apk_file, resource)

    def test_download_checksum_failure_preserves_previous_catalog(self):
        previous = self.keep_previous_catalog()
        expected = remote("/corrupted.apk", b"correct bytes")
        corrupted = self.root / "corrupted.apk"
        corrupted.write_bytes(b"wrong bytes!!")

        class VerifyingInspector:
            def inspect(self, resource):
                sync.verify_file(corrupted, resource)
                return metadata()

        with self.assertRaises(sync.SyncError):
            sync.sync_catalog(FakeApi({"/": [file_item(expected)]}), VerifyingInspector(), self.output)
        self.assertEqual(self.output.read_bytes(), previous)

    def test_api_failure_preserves_previous_catalog_without_inspecting_any_apk(self):
        previous = self.keep_previous_catalog()

        class FailingApi:
            def list_page(self, path, offset, limit):
                raise sync.SyncError("Yandex API unavailable")

        inspector = FakeInspector()
        with self.assertRaises(sync.SyncError):
            sync.sync_catalog(FailingApi(), inspector, self.output)
        self.assertEqual(self.output.read_bytes(), previous)
        self.assertEqual(inspector.calls, [])

    def test_partial_inspection_failure_preserves_previous_complete_catalog(self):
        previous = self.keep_previous_catalog()
        good = remote("/A-good.apk", b"valid first APK")
        bad = remote("/Z-bad.apk", b"invalid later APK")

        class PartialInspector:
            def inspect(self, resource):
                if resource.path == bad.path:
                    raise sync.SyncError("APK signature cannot be verified")
                return metadata("org.example.good")

        with self.assertRaises(sync.SyncError):
            sync.sync_catalog(FakeApi({"/": [file_item(good), file_item(bad)]}), PartialInspector(), self.output)
        self.assertEqual(self.output.read_bytes(), previous)

    def test_remote_change_during_inspection_preserves_previous_catalog(self):
        previous = self.keep_previous_catalog()
        original = remote("/A.apk", b"initial APK")
        api = FakeApi({"/": [file_item(original)]})

        class MutatingInspector:
            def inspect(self, resource):
                api.folders["/"] = []
                return metadata()

        with self.assertRaises(sync.SyncError):
            sync.sync_catalog(api, MutatingInspector(), self.output)
        self.assertEqual(self.output.read_bytes(), previous)

    def test_identical_snapshot_produces_identical_bytes(self):
        a = remote("/A.apk", b"A")
        b = remote("/B.apk", b"B")
        inspector = FakeInspector({a.sha256: metadata("org.example.a"), b.sha256: metadata("org.example.b")})
        api = FakeApi({"/": [file_item(b), file_item(a)]})
        sync.sync_catalog(api, inspector, self.output)
        first = self.output.read_bytes()
        api.folders["/"].reverse()
        sync.sync_catalog(api, inspector, self.output)
        self.assertEqual(self.output.read_bytes(), first)

    def test_cached_apk_is_rehashed_and_sdk_manifest_and_signature_are_rechecked(self):
        content = b"cacheable signed APK fixture"
        resource = remote("/fixture.apk", content)
        api = FakeDownloadApi(content)
        inspector = RecordingSdkInspector(api, self.root / "cache")
        first = inspector.inspect(resource)
        cached = inspector.cache_dir / f"{resource.sha256}-{SIGNER}.apk"

        self.assertEqual(cached.read_bytes(), content)
        self.assertEqual(inspector.inspect(resource), first)
        self.assertEqual(api.downloads, [resource.path])
        self.assertEqual(len(inspector.commands), 4)
        self.assertEqual(inspector.commands[2][0], "fake-aapt2")
        self.assertIn("verify", inspector.commands[3])

        # A previously accepted cached file does not bypass later SDK rejection.
        inspector.reject_signature = True
        with self.assertRaises(sync.SyncError):
            inspector.inspect(resource)
        self.assertEqual(api.downloads, [resource.path])

    def test_tampered_cache_is_ignored_and_replaced_by_verified_download(self):
        content = b"valid APK bytes"
        resource = remote("/fixture.apk", content)
        cache = self.root / "cache"
        cache.mkdir()
        cached = cache / f"{resource.sha256}-{SIGNER}.apk"
        cached.write_bytes(b"X" * len(content))
        api = FakeDownloadApi(content)
        inspector = RecordingSdkInspector(api, cache)

        result = inspector.inspect(resource)

        self.assertEqual(result["packageName"], "org.example.fixture")
        self.assertEqual(api.downloads, [resource.path])
        self.assertEqual(cached.read_bytes(), content)
        self.assertEqual(len(inspector.commands), 2)
        self.assertEqual(list(cache.glob("download-*.apk")), [])

    def test_corrupted_download_never_reaches_sdk_or_persistent_cache(self):
        content = b"expected APK fixture"
        resource = remote("/fixture.apk", content)
        api = FakeDownloadApi(b"Z" * len(content))
        inspector = RecordingSdkInspector(api, self.root / "cache")

        with self.assertRaises(sync.SyncError):
            inspector.inspect(resource)

        self.assertEqual(inspector.commands, [])
        self.assertEqual(list(inspector.cache_dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
