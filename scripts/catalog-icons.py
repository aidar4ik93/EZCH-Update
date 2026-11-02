"""Read the APK manifest icon, including adaptive/vector layers; never guess by filename.

Pillow handles raster images. Vector paths are converted to SVG and rendered by
librsvg (rsvg-convert); unsupported complex drawables fail explicitly.
"""
import hashlib
import io
import json
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
        attribute = re.match(r'\s*A: (?:.*?:)?([\w]+)(?:\(0x[0-9a-f]+\))?=(.*)', line)
        if attribute and stack:
            value = attribute[2]
            if value.startswith('"'): value = json.JSONDecoder().raw_decode(value)[0]
            else: value = value.split(" (Raw:")[0]
            stack[-1][1].set(attribute[1], value)
    if root is None: raise IconError("No drawable XML")
    return root

def color(value):
    if re.fullmatch(r"0x[0-9a-fA-F]{8}", value): value = "#" + value[2:]
    if re.fullmatch(r"#[0-9a-fA-F]{6}", value): value = "#ff" + value[1:]
    if not re.fullmatch(r"#[0-9a-fA-F]{8}", value): raise IconError("Unsupported color reference: " + value)
    return "#" + value[3:], int(value[1:3], 16) / 255

def vector_svg(root):
    width, height = float(root.get("viewportWidth", "0")), float(root.get("viewportHeight", "0"))
    if width <= 0 or height <= 0: raise IconError("Invalid vector viewport")
    svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "viewBox": f"0 0 {width} {height}", "opacity": root.get("alpha", "1")})
    clip_count = 0
    gradient_count = 0
    def paint(item, key, default):
        nonlocal gradient_count
        child = next((node for node in item if node.tag == "attr" and node.get("name") == "android:" + key), None)
        if child is None: return color(item.get(key, default))
        gradient = child.find("gradient")
        if gradient is None: raise IconError("Unsupported vector paint")
        kind = gradient.get("type", "0")
        if kind not in ("0", "1"): raise IconError("Unsupported sweep gradient")
        gradient_count += 1
        name = "gradient" + str(gradient_count)
        attrs = {"id": name, "gradientUnits": "userSpaceOnUse"}
        if kind == "0":
            attrs.update({key: gradient.get(source, "0") for key, source in (("x1","startX"),("y1","startY"),("x2","endX"),("y2","endY"))})
        else:
            attrs.update(cx=gradient.get("centerX","0"), cy=gradient.get("centerY","0"), r=gradient.get("gradientRadius","0"))
        attrs["spreadMethod"] = {"0":"pad", "1":"repeat", "2":"reflect"}.get(gradient.get("tileMode","0"), "pad")
        target = ET.SubElement(ET.SubElement(svg, "defs"), "linearGradient" if kind == "0" else "radialGradient", attrs)
        stops = [(node.get("offset"), node.get("color")) for node in gradient if node.tag == "item"]
        if not stops:
            stops = [("0", gradient.get("startColor")), ("1", gradient.get("endColor"))]
            if "centerColor" in gradient.attrib: stops.insert(1, ("0.5", gradient.get("centerColor")))
        for offset, value in stops:
            rgb, alpha = color(value or "")
            ET.SubElement(target,"stop",{"offset":offset or "0","stop-color":rgb,"stop-opacity":str(alpha)})
        return "url(#" + name + ")", 1
    def append(source, target):
        nonlocal clip_count
        for item in source:
            if item.tag == "group":
                px, py = item.get("pivotX", "0"), item.get("pivotY", "0")
                transform = f'translate({item.get("translateX", "0")} {item.get("translateY", "0")}) translate({px} {py}) rotate({item.get("rotation", "0")}) scale({item.get("scaleX", "1")} {item.get("scaleY", "1")}) translate({-float(px)} {-float(py)})'
                group = ET.SubElement(target, "g", {"transform": transform}); append(item, group)
            elif item.tag == "clip-path":
                clip_count += 1
                clip_id = "clip" + str(clip_count)
                clip = ET.SubElement(svg, "clipPath", {"id": clip_id})
                ET.SubElement(clip, "path", {"d": item.get("pathData", ""), "clip-rule": "evenodd" if item.get("fillType") == "1" else "nonzero"})
                target = ET.SubElement(target, "g", {"clip-path": "url(#" + clip_id + ")"})
            elif item.tag == "path" and all(node.tag == "attr" for node in item):
                attrs = {"d": item.get("pathData", ""), "fill-rule": "evenodd" if item.get("fillType") == "1" else "nonzero"}
                fill, alpha = paint(item, "fillColor", "#00000000")
                attrs.update({"fill": fill, "fill-opacity": str(alpha * float(item.get("fillAlpha", "1")))})
                if "strokeColor" in item.attrib:
                    stroke, alpha = paint(item, "strokeColor", "#00000000")
                    attrs.update({"stroke": stroke, "stroke-opacity": str(alpha * float(item.get("strokeAlpha", "1"))), "stroke-width": item.get("strokeWidth", "0")})
                if any(key in item.attrib for key in ("trimPathStart", "trimPathEnd", "trimPathOffset")): raise IconError("Unsupported trimmed path")
                ET.SubElement(target, "path", attrs)
            else: raise IconError("Unsupported vector element: " + item.tag)
    append(root, svg)
    return ET.tostring(svg)


def svg_png(svg, size):
    renderer = shutil.which("rsvg-convert")
    if renderer:
        return subprocess.run([renderer, "-w", str(size), "-h", str(size)], input=svg,
                              capture_output=True, timeout=30, check=True).stdout
    # Optional Windows renderer for local verification; CI uses librsvg.
    try:
        import resvg_py
    except ImportError as error:
        raise IconError("Install librsvg2-bin (Windows: resvg-py) to render vector icons") from error
    return resvg_py.svg_to_bytes(svg_string=svg.decode("utf-8"), width=size, height=size)

def png_digest(path):
    data = Path(path).read_bytes()
    if len(data) > 1024 * 1024: raise IconError("PNG exceeds 1 MiB")
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG" or not all(0 < n <= 512 for n in image.size):
            raise IconError("Icon must be PNG, at most 512 pixels")
        image.verify()
    return hashlib.sha256(data).hexdigest()


def extract(apk, aapt2, destination):
    try:
        return _extract(apk, aapt2, destination)
    except IconError:
        raise
    except (OSError, ValueError, KeyError, IndexError, SyntaxError, zipfile.BadZipFile,
            subprocess.SubprocessError) as error:
        raise IconError(str(error)) from error


def _extract(apk, aapt2, destination):
    def run(*args): return subprocess.check_output([str(aapt2), "dump", *args, str(apk)], encoding="utf-8", timeout=60)
    badging = run("badging")
    icon = re.search(r"^application:.* icon='([^']+)'", badging, re.MULTILINE)
    if not icon: raise IconError("APK has no manifest icon")
    resources = run("resources")
    # Stable public android.R.color IDs, independent of theme/firmware.
    table = {"0x0106000b": ["#ffffffff"], "0x0106000c": ["#ff000000"], "0x0106000d": ["#00000000"]}
    current = None
    for line in resources.splitlines():
        match = re.match(r"\s*resource (0x[0-9a-f]+) ", line)
        if match: current = match[1]; table[current] = []
        file = re.search(r"\(file\) (res/\S+)", line)
        if file and current: table[current].append(file[1])
        scalar = re.match(r"\s+\([^)]*\) (#[0-9a-fA-F]{8}|@0x[0-9a-fA-F]+)\s*$", line)
        if scalar and current: table[current].append(scalar[1])
    with zipfile.ZipFile(apk) as archive:
        def render(reference, size=256, depth=0):
            if depth > 8: raise IconError("Drawable recursion")
            while reference.startswith("@"):
                depth += 1
                if depth > 12: raise IconError("Drawable reference recursion")
                candidates = table.get(reference[1:], [])
                if not candidates: raise IconError("Unknown drawable resource")
                reference = candidates[-1]
            if reference.startswith("#"):
                rgb, alpha = color(reference)
                return Image.new("RGBA", (size, size), (*bytes.fromhex(rgb[1:]), round(alpha*255)))
            if archive.getinfo(reference).file_size > 16 * 1024 * 1024:
                raise IconError("Drawable resource exceeds 16 MiB")
            if reference.lower().endswith((".png", ".webp", ".jpg", ".jpeg")):
                image = Image.open(io.BytesIO(archive.read(reference)))
                if image.width * image.height > 4096 * 4096: raise IconError("Raster icon too large")
                image = image.convert("RGBA")
                ratio = min(size / image.width, size / image.height)
                image = image.resize((max(1, round(image.width*ratio)), max(1, round(image.height*ratio))), Image.Resampling.LANCZOS)
                canvas = Image.new("RGBA", (size, size)); canvas.alpha_composite(image, ((size-image.width)//2, (size-image.height)//2)); return canvas
            tree = xml_tree(subprocess.check_output([str(aapt2), "dump", "xmltree", str(apk), "--file", reference], encoding="utf-8", timeout=60))
            for node in tree.iter():
                for key, value in list(node.attrib.items()):
                    if key.lower().endswith("color") and value.startswith("@"):
                        for _ in range(12):
                            values = table.get(value[1:], [])
                            if not values: raise IconError("Unknown color resource: " + value)
                            value = values[-1]
                            if not value.startswith("@"): break
                        if value.endswith(".xml"):
                            state = xml_tree(subprocess.check_output([str(aapt2), "dump", "xmltree", str(apk), "--file", value], encoding="utf-8", timeout=60))
                            defaults = [item for item in state if item.tag == "item" and not any(k.startswith("state_") for k in item.attrib)]
                            if state.tag == "gradient":
                                attribute = ET.SubElement(node, "attr", {"name":"android:" + key})
                                attribute.append(state)
                                del node.attrib[key]
                                continue
                            if state.tag != "selector" or not defaults: raise IconError("Unsupported complex color")
                            value = defaults[0].get("color", "")
                        node.set(key, value)
            if tree.tag == "bitmap":
                return render(tree.get("src", ""), size, depth+1)
            if tree.tag == "shape":
                if tree.get("shape", "0") not in ("0", "rectangle"): raise IconError("Unsupported shape")
                if any(node.tag not in ("solid", "gradient") for node in tree): raise IconError("Unsupported shape attributes")
                solid = tree.find("solid")
                if solid is not None: return render(solid.get("color", ""), size, depth+1)
                gradient = tree.find("gradient")
                if gradient is None or gradient.get("type", "0") not in ("0", "linear"): raise IconError("Unsupported shape gradient")
                angle = int(gradient.get("angle", "0")) % 360
                endpoints = {0:(0,50,100,50),45:(0,100,100,0),90:(50,100,50,0),135:(100,100,0,0),180:(100,50,0,50),225:(100,0,0,100),270:(50,0,50,100),315:(0,0,100,100)}
                if angle not in endpoints: raise IconError("Unsupported gradient angle")
                x1,y1,x2,y2 = endpoints[angle]
                svg = ET.Element("svg", {"xmlns":"http://www.w3.org/2000/svg", "viewBox":"0 0 100 100"})
                g = ET.SubElement(ET.SubElement(svg,"defs"), "linearGradient", {"id":"g", "x1":f"{x1}%", "y1":f"{y1}%", "x2":f"{x2}%", "y2":f"{y2}%"})
                stops = [("startColor","0%"),("centerColor","50%"),("endColor","100%")]
                for key, offset in stops:
                    if key not in gradient.attrib: continue
                    rgb, alpha = color(gradient.get(key))
                    ET.SubElement(g,"stop",{"offset":offset,"stop-color":rgb,"stop-opacity":str(alpha)})
                ET.SubElement(svg,"rect",{"width":"100","height":"100","fill":"url(#g)"})
                return Image.open(io.BytesIO(svg_png(ET.tostring(svg), size))).convert("RGBA")
            if tree.tag == "color":
                rgb, alpha = color(tree.get("color", "")); return Image.new("RGBA", (size, size), (*bytes.fromhex(rgb[1:]), int(alpha*255)))
            if tree.tag == "adaptive-icon":
                layers = {node.tag: node.get("drawable") for node in tree}
                background, foreground = render(layers["background"], 384, depth+1), render(layers["foreground"], 384, depth+1)
                background.alpha_composite(foreground)
                # Android's adaptive icon viewport includes 18 dp overscan on each edge.
                return background.crop((64, 64, 320, 320)).resize((size, size), Image.Resampling.LANCZOS)
            if tree.tag != "vector": raise IconError("Unsupported drawable: " + tree.tag)
            return Image.open(io.BytesIO(svg_png(vector_svg(tree), size))).convert("RGBA")
        reference = icon[1]
        # aapt badging can name the low-density variant. Stay within that exact
        # manifest resource, selecting its highest-density/adaptive variant.
        for variants in table.values():
            if reference in variants:
                reference = variants[-1]
                break
        image = render(reference)
    buffer = io.BytesIO(); image.save(buffer, format="PNG", optimize=True)
    data = buffer.getvalue(); digest = hashlib.sha256(data).hexdigest()
    if len(data) > 1024 * 1024: raise IconError("PNG exceeds 1 MiB")
    destination = Path(destination); destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temp:
        temp.write(data); temporary = Path(temp.name)
    temporary.replace(destination)
    return digest
