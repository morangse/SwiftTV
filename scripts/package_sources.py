import pathlib
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
version = "0.2.0"
for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines():
    if line.startswith("swifttv.versionName="):
        version = line.split("=", 1)[1].strip()
        break

target = root / "output" / "delivery" / f"SwiftTV-{version}-source.zip"
target.parent.mkdir(parents=True, exist_ok=True)
excluded = {".git", ".gradle", ".kotlin", ".signing", ".idea", "build", "output", "__pycache__"}
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for item in sorted(root.rglob("*")):
        relative = item.relative_to(root)
        if not item.is_file() or any(part in excluded for part in relative.parts):
            continue
        if item.name == "local.properties" or item.suffix in (".p12", ".jks", ".keystore"):
            continue
        archive.write(item, "SwiftTV/" + relative.as_posix())
print(target)
