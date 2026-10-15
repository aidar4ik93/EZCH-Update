import importlib.util
from pathlib import Path
import sys
import unittest
import tempfile
from unittest import mock
import json

spec = importlib.util.spec_from_file_location("drive_sync", Path(__file__).with_name("sync-google-drive-catalog.py"))
drive = importlib.util.module_from_spec(spec); sys.modules[spec.name] = drive; spec.loader.exec_module(drive)

class DriveApiTests(unittest.TestCase):
    def item(self, **values):
        return {"id": "1234567890abc", "parents": [drive.FOLDER_ID], "name": "Зона.apk",
            "mimeType": "application/vnd.android.package-archive", "size": "123",
            "modifiedTime": "2026-10-13T00:00:00Z", **values}

    def test_api_pagination_collects_all_files_without_html(self):
        calls = []
        def reader(params):
            calls.append(params)
            if "pageToken" not in params: return {"files": [self.item()], "nextPageToken": "next"}
            return {"files": [self.item(id="another12345")]}
        files = drive.DriveApi(reader=reader).list_files()
        self.assertEqual(2, len(files)); self.assertEqual("next", calls[1]["pageToken"])
        self.assertEqual("Зона.apk", files[0].name)
        self.assertIn("trashed = false", calls[0]["q"])

    def test_incomplete_or_looping_search_fails_closed(self):
        for response in [{"files": [], "incompleteSearch": True}, {"files": [], "nextPageToken": "repeat"}, {}]:
            with self.subTest(response=response), self.assertRaises(drive.SyncError):
                drive.DriveApi(reader=lambda _: response).list_files()

    def test_foreign_parents_duplicates_and_bad_metadata_rejected(self):
        for entries in [[self.item(parents=["foreign"])], [self.item(), self.item()],
                        [self.item(size="-1")], [self.item(modifiedTime="bad")]]:
            with self.subTest(entries=entries), self.assertRaises(drive.SyncError): drive.parse_files(entries, drive.FOLDER_ID)

    def test_complete_empty_folder_and_non_apks_supported(self):
        self.assertEqual([], drive.DriveApi(reader=lambda _: {"files": []}).list_files())
        self.assertEqual([], drive.parse_files([self.item(name="Readme.txt")], drive.FOLDER_ID))

    def test_download_id_validation(self):
        self.assertIn("id=1234567890abc", drive.download_url("1234567890abc"))
        with self.assertRaises(drive.SyncError): drive.download_url("bad&id=other")

    def test_active_tv_release_excludes_archived_companion(self):
        apps = [{"packageName": name} for name in ["com.example.ezchupdate", "com.example.ezchupdate.tvtest", "example.player"]]
        self.assertEqual(apps[1:], drive.active_project_catalog(apps))

    def test_transaction_adds_updates_removes_and_deduplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "apps.json"
            output.write_text(json.dumps({"apps": [{"packageName": "deleted.app"}]}))
            items = [drive.DriveFile("1234567890abc", "one.apk", 3, 1), drive.DriveFile("another12345", "two.apk", 3, 1)]
            class Api:
                def list_files(self): return items
                def download(self, item, cache):
                    path = Path(cache) / item.name; path.write_bytes(b"APK")
                    return item, path, "a" * 64
            class Inspector:
                aapt2 = "/missing-aapt2"
                def __init__(self, *args): pass
                def _metadata(self, path): return {"packageName": "new.app", "name": "Новое", "versionCode": 2 if path.name == "two.apk" else 1, "versionName": "2", "signerSha256": "b" * 64}
            with mock.patch.object(drive.common, "sdk_tools", return_value=(1,2,3)), mock.patch.object(drive.common, "SdkInspector", Inspector):
                result = drive.sync(Api(), Path(directory) / "cache", output, None, False)
            self.assertEqual(["new.app"], [app["packageName"] for app in result])
            self.assertEqual(2, result[0]["versionCode"])
            self.assertEqual([], list((Path(directory)/"cache").glob("*.apk")))

    def test_folder_change_and_download_failure_preserve_last_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "apps.json"; original = b'{"apps":[]}'
            output.write_bytes(original)
            item = drive.DriveFile("1234567890abc", "one.apk", 3, 1)
            class Api:
                calls = 0
                def list_files(self): self.calls += 1; return [item] if self.calls == 1 else []
                def download(self, item, cache):
                    path = Path(cache) / item.name; path.write_bytes(b"APK"); return item, path, "a"*64
            class Inspector:
                aapt2 = "/missing-aapt2"
                def __init__(self, *args): pass
                def _metadata(self, path): return {"packageName": "new.app", "name": "New", "versionCode": 1, "versionName": "1", "signerSha256": "b"*64}
            with mock.patch.object(drive.common, "sdk_tools", return_value=(1,2,3)), mock.patch.object(drive.common, "SdkInspector", Inspector), self.assertRaises(drive.SyncError):
                drive.sync(Api(), Path(directory)/"cache", output, None, False)
            self.assertEqual(original, output.read_bytes())
            class FailedApi(Api):
                def download(self, *args): raise drive.SyncError("quota exceeded")
            with mock.patch.object(drive.common, "sdk_tools", return_value=(1,2,3)), mock.patch.object(drive.common, "SdkInspector", Inspector), self.assertRaises(drive.SyncError):
                drive.sync(FailedApi(), Path(directory)/"cache", output, None, False)
            self.assertEqual(original, output.read_bytes())

if __name__ == "__main__": unittest.main()
