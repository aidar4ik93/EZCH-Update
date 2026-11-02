import importlib.util
from pathlib import Path
import unittest
import hashlib
import io
import tempfile
import zipfile
from unittest.mock import patch
from PIL import Image
spec = importlib.util.spec_from_file_location("icons", Path(__file__).with_name("catalog-icons.py"))
icons = importlib.util.module_from_spec(spec); spec.loader.exec_module(icons)

class IconParserTests(unittest.TestCase):
    def test_vector_group_path_and_alpha_preserved(self):
        xml = '''  E: vector (line=2)
    A: http://schemas.android.com/apk/res/android:viewportWidth(0x01010402)=108
    A: http://schemas.android.com/apk/res/android:viewportHeight(0x01010403)=108
      E: group (line=3)
        A: http://schemas.android.com/apk/res/android:scaleX(0x01010324)=0.159844
          E: path (line=4)
            A: http://schemas.android.com/apk/res/android:fillColor(0x01010404)=#800094f6
            A: http://schemas.android.com/apk/res/android:pathData(0x01010405)="M0,0H10V10Z" (Raw: "M0,0H10V10Z")
            A: http://schemas.android.com/apk/res/android:fillType(0x0101051e)=1'''
        svg = icons.vector_svg(icons.xml_tree(xml)).decode()
        self.assertIn("0.159844", svg); self.assertIn('fill="#0094f6"', svg)
        self.assertIn('fill-rule="evenodd"', svg); self.assertIn('d="M0,0H10V10Z"', svg)
    def test_unresolved_colors_fail_instead_of_wrong_icon(self):
        with self.assertRaises(icons.IconError): icons.color("@0x12345678")
    def test_unsupported_gradient_fails_explicitly(self):
        import xml.etree.ElementTree as ET
        root = ET.fromstring('<vector viewportWidth="10" viewportHeight="10"><path><gradient /></path></vector>')
        with self.assertRaises(icons.IconError): icons.vector_svg(root)

    def test_linear_gradient_and_clip_are_rendered(self):
        import xml.etree.ElementTree as ET
        root = ET.fromstring('''<vector viewportWidth="10" viewportHeight="10"><group>
          <clip-path pathData="M0,0H5V10H0Z"/>
          <path pathData="M0,0H10V10H0Z"><attr name="android:fillColor"><gradient startX="0" startY="0" endX="10" endY="0">
            <item offset="0" color="#ffff0000"/><item offset="1" color="#ff0000ff"/>
          </gradient></attr></path></group></vector>''')
        with Image.open(io.BytesIO(icons.svg_png(icons.vector_svg(root), 100))) as image:
            image = image.convert("RGBA")
            self.assertGreater(image.getpixel((10,50))[0], 200)
            self.assertEqual(image.getpixel((90,50))[3], 0)
class ApkExtractionTests(unittest.TestCase):
    def fixture(self, root, raster_format="PNG", adaptive=False):
        extension = {"PNG": "png", "WEBP": "webp", "JPEG": "jpg"}[raster_format]
        path = root / "example.apk"
        buffer = io.BytesIO()
        image = Image.new("RGB", (256, 256), (230, 20, 40))
        if adaptive:
            image = Image.new("RGBA", (256, 256))
            image.paste((230,20,40,255), (96,96,160,160))
        image.save(buffer, format=raster_format)
        resource = "res/icon." + extension
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(resource, buffer.getvalue())
            archive.writestr("res/adaptive.xml", b"compiled XML fixture")
        resources = "resource 0x7f010001 example:drawable/foreground\n  () (file) " + resource + " type=PNG\nresource 0x7f020001 example:color/background\n  () #ff112233\n"
        xml = '''E: adaptive-icon
  E: background
    A: android:drawable(0x01010199)=@0x7f020001
  E: foreground
    A: android:drawable(0x01010199)=@0x7f010001'''
        def aapt(command, **kwargs):
            if "badging" in command:
                return "application: label='Example' icon='" + ("res/adaptive.xml" if adaptive else resource) + "'"
            return xml if "xmltree" in command else resources
        return path, aapt

    def test_manifest_raster_formats_are_original_pixels_not_placeholder(self):
        for format in ("PNG", "WEBP", "JPEG"):
            with self.subTest(format=format), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); apk, aapt = self.fixture(root, format)
                output = root / "icon.png"
                with patch.object(icons.subprocess, "check_output", side_effect=aapt):
                    digest = icons.extract(apk, "aapt2", output)
                self.assertEqual(digest, hashlib.sha256(output.read_bytes()).hexdigest())
                with Image.open(output) as image:
                    self.assertEqual(image.size, (256, 256))
                    self.assertLess(sum(abs(a-b) for a,b in zip(image.getpixel((128,128))[:3], (230,20,40))), 12)

    def test_adaptive_resolves_color_background_and_foreground(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); apk, aapt = self.fixture(root, adaptive=True)
            with patch.object(icons.subprocess, "check_output", side_effect=aapt):
                icons.extract(apk, "aapt2", root / "icon.png")
            with Image.open(root / "icon.png") as image:
                self.assertEqual(image.getpixel((0, 0)), (17, 34, 51, 255))
                self.assertEqual(image.getpixel((128, 128)), (230, 20, 40, 255))

    def test_vector_is_rendered_to_real_pixels(self):
        tree = icons.xml_tree('''E: vector
  A: android:viewportWidth(0x01010402)=10
  A: android:viewportHeight(0x01010403)=10
  E: path
    A: android:fillColor(0x01010404)=#ff0094f6
    A: android:pathData(0x01010405)="M0,0H10V10H0Z"''')
        with Image.open(io.BytesIO(icons.svg_png(icons.vector_svg(tree), 256))) as image:
            self.assertEqual(image.size, (256, 256))
            self.assertEqual(image.convert("RGBA").getpixel((128,128)), (0,148,246,255))

    def test_invalid_apk_becomes_icon_error_without_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); apk = root / "broken.apk"; apk.write_bytes(b"not a ZIP")
            with patch.object(icons.subprocess, "check_output", return_value="application: label='Broken' icon='res/icon.png'"), self.assertRaises(icons.IconError):
                icons.extract(apk, "aapt2", root / "icon.png")
            self.assertFalse((root / "icon.png").exists())

    def test_high_density_variant_belongs_to_the_manifest_resource(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); apk, original_aapt = self.fixture(root)
            data = io.BytesIO(); Image.new("RGBA", (512,512), (0,180,60,255)).save(data,format="PNG")
            with zipfile.ZipFile(apk, "a") as archive: archive.writestr("res/high.png", data.getvalue())
            def aapt(command, **kwargs):
                if "resources" in command:
                    return "resource 0x7f010001 example:mipmap/icon\n  (mdpi) (file) res/icon.png type=PNG\n  (xxxhdpi) (file) res/high.png type=PNG"
                return original_aapt(command, **kwargs)
            with patch.object(icons.subprocess,"check_output",side_effect=aapt): icons.extract(apk,"aapt2",root/"out.png")
            with Image.open(root/"out.png") as image: self.assertEqual(image.getpixel((128,128)), (0,180,60,255))

    def test_oversized_or_invalid_png_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "icon.png"
            Image.new("RGBA", (513, 1)).save(path)
            with self.assertRaises(icons.IconError): icons.png_digest(path)

if __name__ == "__main__": unittest.main()
