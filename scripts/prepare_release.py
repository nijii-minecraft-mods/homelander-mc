"""Validate the built mod and create stable-name GitHub release assets."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / "build" / "libs"
OUTPUT = ROOT / "release-assets"

production_jars = [
    path for path in LIBS.glob("homelander-powers-*.jar")
    if not path.name.endswith(("-sources.jar", "-dev.jar"))
]
if len(production_jars) != 1:
    raise SystemExit(f"Expected one production JAR, found: {production_jars}")

production = production_jars[0]
sources = LIBS / f"{production.stem}-sources.jar"
if not sources.is_file():
    raise SystemExit(f"Missing sources JAR: {sources}")

with zipfile.ZipFile(production) as jar:
    if jar.testzip() is not None:
        raise SystemExit("Production JAR is corrupt")
    manifest = json.loads(jar.read("fabric.mod.json"))
    if manifest["id"] != "homelander" or manifest["depends"]["minecraft"] != "1.21.1":
        raise SystemExit("Unexpected mod ID or Minecraft version in JAR")

OUTPUT.mkdir(exist_ok=True)
shutil.copyfile(production, OUTPUT / "homelander-powers.jar")
shutil.copyfile(sources, OUTPUT / "homelander-powers-sources.jar")
shutil.copyfile(ROOT / "README.md", OUTPUT / "README.md")

assets = sorted(path for path in OUTPUT.iterdir() if path.is_file())
checksums = "".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
    for path in assets
)
(OUTPUT / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
print(f"Release assets ready: {manifest['version']} -> homelander-powers.jar")
