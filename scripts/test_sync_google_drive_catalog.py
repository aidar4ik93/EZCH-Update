import importlib.util
import json
from pathlib import Path
import sys
import unittest

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

if __name__ == "__main__": unittest.main()
