"""Extract existing raster launcher icons from verified APKs; requires Pillow and aapt2."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-directory", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parent.parent / "apps.json")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent.parent / "app/src/main/assets/app-icons")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    apps = json.loads(args.catalog.read_text(encoding="utf-8-sig"))["apps"]
    for app in apps:
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", app["packageName"]):
            raise ValueError("Invalid Android package name")
        apk = (args.apk_directory / app["apkPath"].lstrip("/")).resolve()
        if not apk.is_relative_to(args.apk_directory.resolve()):
            raise ValueError("APK path leaves the specified directory")
        if app.get("sha256"):
            with apk.open("rb") as source:
                checksum = hashlib.file_digest(source, "sha256").hexdigest()
            if checksum != app["sha256"].lower():
                raise ValueError(f"APK checksum differs: {app['name']}")
        resources = subprocess.check_output([str(args.aapt2), "dump", "resources", str(apk)], encoding="utf-8")
        candidates = []
        resource_name = ""
        for line in resources.splitlines():
            header = re.match(r"\s*resource\s+\S+\s+(\S+)", line)
            if header:
                resource_name = header[1]
                continue
            raster = re.search(r"\(file\) (res/\S+\.(?:png|webp))", line)
            if raster and re.search(r"/(?:ic_launcher|app_icon|launcher_icon|icon)$", resource_name):
                candidates.append(raster[1])
        with zipfile.ZipFile(apk) as archive:
            images = []
            for name in candidates:
                image = Image.open(io.BytesIO(archive.read(name)))
                image.load()
                images.append((image.width * image.height, image, name))
            if not images:
                print(f"No raster launcher icon: {app['name']}")
                continue
            _, icon, name = max(images, key=lambda entry: entry[0])
            icon.convert("RGBA").save(args.output / (app["packageName"] + ".png"), optimize=True)
            print(f"Extracted {app['packageName']}: {name} ({icon.width} x {icon.height})")


if __name__ == "__main__":
    main()
