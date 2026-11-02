import importlib.util
import json
from pathlib import Path
import sys
import unittest
import tempfile
import hashlib
from types import SimpleNamespace
from PIL import Image
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("drive_sync", Path(__file__).with_name("sync-google-drive-catalog.py"))
drive = importlib.util.module_from_spec(spec); sys.modules[spec.name] = drive; spec.loader.exec_module(drive)

class PublicDriveTests(unittest.TestCase):
    def listing(self, entries, token=None):
        value = json.dumps([entries, token], ensure_ascii=False)
        return "window['_DRIVE_ivd'] = " + repr(value) + ";"

    def item(self, name="Example.apk", file_id="1234567890abc", parent=drive.FOLDER_ID, size=123):
        return [file_id, [parent], name, "application/vnd.android.package-archive", 0, None, 0, 0, 0, 1, 2, None, None, size]

    def test_public_listing_preserves_name_size_and_modified(self):
        items = drive.parse_folder(self.listing([self.item("Файловый менеджер.apk")]), drive.FOLDER_ID)
        self.assertEqual(items, [drive.DriveFile("1234567890abc", "Файловый менеджер.apk", 123, 2)])

    def test_private_or_changed_listing_is_rejected(self):
        for source in ["Login required", "window['_DRIVE_ivd'] = 'invalid';", self.listing([])]:
            with self.subTest(source=source), self.assertRaises(drive.SyncError): drive.parse_folder(source, drive.FOLDER_ID)

    def test_partial_pages_never_replace_complete_catalog(self):
        with self.assertRaises(drive.SyncError): drive.parse_folder(self.listing([self.item()], "next-page"), drive.FOLDER_ID)

    def test_duplicate_ids_and_foreign_parents_are_rejected(self):
        for entries in [[self.item(), self.item()], [self.item(parent="different-folder")], [self.item(size=-1)]]:
            with self.subTest(entries=entries), self.assertRaises(drive.SyncError): drive.parse_folder(self.listing(entries), drive.FOLDER_ID)

    def test_non_apk_files_are_ignored(self):
        files = drive.parse_folder(self.listing([self.item(), self.item("Readme.txt", "another12345")]), drive.FOLDER_ID)
        self.assertEqual(len(files), 1)

    def test_download_url_uses_individual_file_and_public_confirmation(self):
        self.assertEqual(drive.download_url("1234567890abc"), "https://drive.usercontent.google.com/download?id=1234567890abc&export=download&confirm=t")
        with self.assertRaises(drive.SyncError): drive.download_url("bad&id=other")

    def test_full_tv_release_replaces_archived_companion_only(self):
        apps = [{"packageName": name} for name in ["com.example.ezchupdate", "com.example.ezchupdate.tvtest", "com.example.homeezch.usb", "example.player"]]
        self.assertEqual([app["packageName"] for app in drive.active_project_catalog(apps)],
            ["com.example.ezchupdate.tvtest", "com.example.homeezch.usb", "example.player"])
        self.assertEqual(drive.active_project_catalog(apps[:1]), apps[:1])

    def test_explicit_legacy_release_remains_available_beside_tv_edition(self):
        legacy = {"packageName": "com.example.ezchupdate",
                  "apkUrl": "https://github.com/aidar4ik93/EZCH-Update/releases/download/v1.8.2/EZCH_Update_1.8.2_Legacy.apk"}
        tv = {"packageName": "com.example.ezchupdate.tvtest"}
        self.assertEqual(drive.active_project_catalog([legacy, tv]), [legacy, tv])

class SyncIsolationTests(unittest.TestCase):
    def run_sync(self, root, errors=None, download_error=False, changed=False):
        errors = errors or {}
        items = [drive.DriveFile("good12345678", "Good.apk", 4, 1),
                 drive.DriveFile("bad123456789", "Bad.apk", 4, 1)]
        class Api:
            calls = 0
            def list_files(self):
                self.calls += 1
                return items[:1] if changed and self.calls > 1 else items
            def download(self, item, cache):
                if download_error and item.name == "Bad.apk":
                    raise OSError("Download unavailable")
                path = Path(cache) / item.name
                path.write_bytes(b"test")
                return item, path, "a" * 64
        def metadata(path):
            if path.name in errors:
                raise drive.SyncError(errors[path.name])
            return dict(name=path.stem, packageName="example." + path.stem.lower(),
                        versionCode=1, versionName="1", signerSha256="b" * 64)
        with patch.object(drive.common, "sdk_tools", return_value=("a", "b", "c")), \
             patch.object(drive.common.SdkInspector, "_metadata", side_effect=metadata):
            return drive.sync(Api(), root / "cache", root / "apps.json", None, False)

    def test_invalid_apk_is_skipped_and_corrected_file_is_retried(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apps = self.run_sync(root, {"Bad.apk": "Android SDK rejected an APK: DOES NOT VERIFY"})
            self.assertEqual([app["name"] for app in apps], ["Good"])
            self.assertEqual(list((root / "cache").iterdir()), [])
            self.assertEqual(len(self.run_sync(root)), 2)

    def test_infrastructure_errors_and_changed_folder_preserve_catalog(self):
        for options in [dict(download_error=True), dict(changed=True),
                        dict(errors={"Bad.apk": "Android SDK inspection failed: TimeoutExpired"}),
                        dict(errors={"Good.apk": "Invalid APK", "Bad.apk": "Invalid APK"})]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                output = root / "apps.json"
                output.write_text("previous catalog")
                icons_dir = root / "catalog-icons"; icons_dir.mkdir()
                old_icon = icons_dir / ("f" * 64 + ".png"); old_icon.write_bytes(b"published icon")
                with self.assertRaises((drive.SyncError, OSError)):
                    self.run_sync(root, **options)
                self.assertEqual(output.read_text(), "previous catalog")
                self.assertEqual(old_icon.read_bytes(), b"published icon")
                self.assertEqual(len(list(icons_dir.iterdir())), 1)
                self.assertEqual(list((root / "cache").iterdir()), [])

    def test_missing_icon_keeps_app_and_never_emits_partial_fields(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(drive.icons, "extract", side_effect=drive.icons.IconError("no manifest icon")):
            apps = self.run_sync(Path(temp))
            self.assertEqual(len(apps), 2)
            self.assertTrue(all("iconUrl" not in app and "iconSha256" not in app for app in apps))

    def test_success_publishes_png_digest_and_removes_obsolete_images(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); directory = root / "catalog-icons"; directory.mkdir()
            stale = directory / ("f"*64 + ".png"); stale.write_bytes(b"old")
            def render(apk, aapt2, destination):
                Image.new("RGBA", (256,256), (12,34,56,255)).save(destination)
                return drive.icons.png_digest(destination)
            with patch.object(drive.icons, "extract", side_effect=render):
                apps = self.run_sync(root)
            self.assertFalse(stale.exists())
            self.assertEqual(len(list(directory.glob("*.png"))), 1)
            for app in apps:
                self.assertEqual(app["iconUrl"], drive.ICON_BASE + app["sha256"] + ".png")
                self.assertRegex(app["iconSha256"], r"^[a-f0-9]{64}$")
                self.assertEqual(app["iconSha256"], hashlib.sha256((directory/(app["sha256"]+".png")).read_bytes()).hexdigest())

class RetainedIconTests(unittest.TestCase):
    def test_retained_apk_checksum_is_required_before_render(self):
        import io
        for valid in (True, False):
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); staging = root / "staging"; staging.mkdir()
                data = b"verified retained APK bytes"
                app = dict(name="Legacy", packageName="example.legacy", versionCode=3, signerSha256="b"*64,
                           sha256=hashlib.sha256(data).hexdigest(), sizeBytes=len(data), apkUrl="https://example.com/release.apk")
                inspector = SimpleNamespace(aapt2="aapt2", _metadata=lambda path: dict(app))
                opener = SimpleNamespace(open=lambda *a, **kw: io.BytesIO(data if valid else b"wrong"))
                with patch.object(drive.urllib.request,"build_opener",return_value=opener), patch.object(drive.icons,"extract",side_effect=drive.icons.IconError("fixture render not supported")) as render:
                    drive.IconBatch(root/"apps.json",staging).retained(app,inspector)
                self.assertEqual(render.call_count, 1 if valid else 0)
                self.assertNotIn("iconUrl", app)
                self.assertNotIn("iconSha256", app)
                self.assertEqual(list(staging.iterdir()), [])

if __name__ == "__main__": unittest.main()

