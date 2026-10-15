import importlib.util
from pathlib import Path
import unittest
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
if __name__ == "__main__": unittest.main()
