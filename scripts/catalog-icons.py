"""Read the APK manifest icon, including adaptive/vector layers; never guess by filename.

Pillow handles raster images. Vector paths are converted to SVG and rendered by
librsvg (rsvg-convert); unsupported complex drawables fail explicitly.
"""
import hashlib
import io
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from PIL import Image

class IconError(ValueError): pass

def xml_tree(source):
    stack, root = [], None
    for line in source.splitlines():
        element = re.match(r"(\s*)E: ([\w-]+)", line)
        if element:
            depth = len(element[1]); node = ET.Element(element[2])
            while stack and stack[-1][0] >= depth: stack.pop()
            if stack: stack[-1][1].append(node)
            else: root = node
            stack.append((depth, node)); continue
        attribute = re.match(r'\s*A: .*?:([\w]+)\(0x[0-9a-f]+\)=(.*)', line)
        if attribute and stack:
            value = attribute[2]
            if value.startswith('"'): value = value.split('"', 2)[1]
            else: value = value.split(" (Raw:")[0]
            stack[-1][1].set(attribute[1], value)
    if root is None: raise IconError("No drawable XML")
    return root

def color(value):
    if not re.fullmatch(r"#[0-9a-fA-F]{8}", value): raise IconError("Unsupported color reference")
    return "#" + value[3:], int(value[1:3], 16) / 255

def vector_svg(root):
    width, height = float(root.get("viewportWidth", "0")), float(root.get("viewportHeight", "0"))
    if width <= 0 or height <= 0: raise IconError("Invalid vector viewport")
    svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "viewBox": f"0 0 {width} {height}"})
    def append(source, target):
        for item in source:
            if item.tag == "group":
                px, py = item.get("pivotX", "0"), item.get("pivotY", "0")
                transform = f'translate({item.get("translateX", "0")} {item.get("translateY", "0")}) translate({px} {py}) rotate({item.get("rotation", "0")}) scale({item.get("scaleX", "1")} {item.get("scaleY", "1")}) translate({-float(px)} {-float(py)})'
                group = ET.SubElement(target, "g", {"transform": transform}); append(item, group)
            elif item.tag == "path" and len(item) == 0:
                attrs = {"d": item.get("pathData", ""), "fill-rule": "evenodd" if item.get("fillType") == "1" else "nonzero"}
                fill, alpha = color(item.get("fillColor", "#00000000"))
                attrs.update({"fill": fill, "fill-opacity": str(alpha * float(item.get("fillAlpha", "1")))})
                if "strokeColor" in item.attrib:
                    stroke, alpha = color(item.get("strokeColor"))
                    attrs.update({"stroke": stroke, "stroke-opacity": str(alpha * float(item.get("strokeAlpha", "1"))), "stroke-width": item.get("strokeWidth", "0")})
                if any(key in item.attrib for key in ("trimPathStart", "trimPathEnd", "trimPathOffset")): raise IconError("Unsupported trimmed path")
                ET.SubElement(target, "path", attrs)
            else: raise IconError("Unsupported vector element: " + item.tag)
    append(root, svg)
    return ET.tostring(svg)

def extract(apk, aapt2, destination):
    def run(*args): return subprocess.check_output([str(aapt2), "dump", *args, str(apk)], encoding="utf-8", timeout=60)
    badging = run("badging")
    icon = re.search(r"^application:.* icon='([^']+)'", badging, re.MULTILINE)
    if not icon: raise IconError("APK has no manifest icon")
    resources = run("resources")
    table, current = {}, None
    for line in resources.splitlines():
        match = re.match(r"\s*resource (0x[0-9a-f]+) ", line)
        if match: current = match[1]; table[current] = []
        file = re.search(r"\(file\) (res/\S+) type=", line)
        if file and current: table[current].append(file[1])
    with zipfile.ZipFile(apk) as archive:
        def render(reference, size=256, depth=0):
            if depth > 8: raise IconError("Drawable recursion")
            if reference.startswith("@"):
                candidates = table.get(reference[1:], [])
                if not candidates: raise IconError("Unknown drawable resource")
                reference = candidates[-1]
            if reference.endswith((".png", ".webp")):
                image = Image.open(io.BytesIO(archive.read(reference))).convert("RGBA")
                image.thumbnail((size, size), Image.Resampling.LANCZOS)
                canvas = Image.new("RGBA", (size, size)); canvas.alpha_composite(image, ((size-image.width)//2, (size-image.height)//2)); return canvas
            tree = xml_tree(subprocess.check_output([str(aapt2), "dump", "xmltree", str(apk), "--file", reference], encoding="utf-8", timeout=60))
            if tree.tag == "color":
                rgb, alpha = color(tree.get("color", "")); return Image.new("RGBA", (size, size), (*bytes.fromhex(rgb[1:]), int(alpha*255)))
            if tree.tag == "adaptive-icon":
                layers = {node.tag: node.get("drawable") for node in tree}
                background, foreground = render(layers["background"], 384, depth+1), render(layers["foreground"], 384, depth+1)
                background.alpha_composite(foreground)
                # Android's adaptive icon viewport includes 18 dp overscan on each edge.
                return background.crop((64, 64, 320, 320)).resize((size, size), Image.Resampling.LANCZOS)
            if tree.tag != "vector": raise IconError("Unsupported drawable: " + tree.tag)
            renderer = shutil.which("rsvg-convert")
            if not renderer: raise IconError("Install librsvg2-bin to render vector icons")
            result = subprocess.run([renderer, "-w", str(size), "-h", str(size)], input=vector_svg(tree), capture_output=True, timeout=30, check=True)
            return Image.open(io.BytesIO(result.stdout)).convert("RGBA")
        image = render(icon[1])
    buffer = io.BytesIO(); image.save(buffer, format="PNG", optimize=True)
    data = buffer.getvalue(); digest = hashlib.sha256(data).hexdigest()
    destination = Path(destination); destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temp:
        temp.write(data); temporary = Path(temp.name)
    temporary.replace(destination)
    return digest
