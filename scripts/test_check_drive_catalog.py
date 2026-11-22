import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('source_check',Path(__file__).with_name('check-drive-catalog.py'))
check=importlib.util.module_from_spec(spec);sys.modules[spec.name]=check;spec.loader.exec_module(check)
drive=check.drive

class SourceCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.catalog=self.root/'apps.json';self.state=self.root/'catalog-source.json'
        self.items=[drive.DriveFile('abcdefghijk','Приложение.apk',123,456)]
        self.catalog.write_text(json.dumps({'apps':[{'packageName':'example.app'}]}))
        drive.write_source_state(self.items,self.catalog,self.state)

    def test_unchanged_source_skips_all_apk_downloads(self):
        self.assertFalse(check.needs_sync(self.items,self.catalog,self.state))

    def test_add_remove_rename_replace_and_same_size_updates_require_verification(self):
        variants=[[],self.items+[drive.DriveFile('newabcdefgh','Other.apk',7,9)],
                  [drive.DriveFile('abcdefghijk','Renamed.apk',123,456)],
                  [drive.DriveFile('abcdefghijk','Приложение.apk',124,456)],
                  [drive.DriveFile('abcdefghijk','Приложение.apk',123,457)]]
        for items in variants:
            with self.subTest(items=items):self.assertTrue(check.needs_sync(items,self.catalog,self.state))

    def test_modified_catalog_or_missing_snapshot_is_never_skipped(self):
        self.catalog.write_text('{}');self.assertTrue(check.needs_sync(self.items,self.catalog,self.state))
        self.state.unlink();self.assertTrue(check.needs_sync(self.items,self.catalog,self.state))

    def test_private_other_folder_or_old_format_is_not_trusted(self):
        for change in [{'folderId':'other-folder'},{'format':2}]:
            state=json.loads(self.state.read_text(encoding="utf-8"));state.update(change);self.state.write_text(json.dumps(state))
            self.assertTrue(check.needs_sync(self.items,self.catalog,self.state))

    def test_missing_or_corrupted_original_icon_triggers_repair(self):
        import hashlib
        icon=self.root/'catalog-icons'/('a'*64+'.png');icon.parent.mkdir();icon.write_bytes(b'original png')
        app=dict(packageName='example.app',sha256='a'*64,iconUrl='https://example.com/icon.png',iconSha256=hashlib.sha256(icon.read_bytes()).hexdigest())
        self.catalog.write_text(json.dumps({'apps':[app]}));drive.write_source_state(self.items,self.catalog,self.state)
        self.assertFalse(check.needs_sync(self.items,self.catalog,self.state))
        icon.write_bytes(b'bad');self.assertTrue(check.needs_sync(self.items,self.catalog,self.state))

    def test_snapshot_uses_stable_unicode_order_and_exact_catalog_digest(self):
        state=json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(state['fingerprint'],drive.listing_fingerprint(self.items))
        self.assertEqual(state['files'],[['abcdefghijk',self.items[0].name,123,456]])
        self.assertEqual(ord(self.items[0].name[0]),0x041f)

if __name__=='__main__':unittest.main()
