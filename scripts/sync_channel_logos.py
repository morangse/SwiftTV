#!/usr/bin/env python3
"""
Sync and optimize channel logos from Elykia093/HA-CMCC-IPTV into offline WebP assets,
preserving existing 24 fanmingming/live logos and generating a comprehensive lookup index.
"""
import concurrent.futures
import hashlib
import io
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets" / "channel-logos"
ELYKIA_M3U = ROOT / "output" / "cctv-expand-20261117" / "elykia.m3u"
RAW_BASE = "https://raw.githubusercontent.com/Elykia093/HA-CMCC-IPTV/main/public/logos/"
JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/Elykia093/HA-CMCC-IPTV@main/public/logos/"

EXISTING_CORE = {
    "cctv-1": "cctv-1.webp",
    "cctv-2": "cctv-2.webp",
    "cctv-3": "cctv-3.webp",
    "cctv-4": "cctv-4.webp",
    "cctv-5": "cctv-5.webp",
    "cctv-5+": "cctv-5plus.webp",
    "cctv-5plus": "cctv-5plus.webp",
    "cctv-6": "cctv-6.webp",
    "cctv-7": "cctv-7.webp",
    "cctv-8": "cctv-8.webp",
    "cctv-9": "cctv-9.webp",
    "cctv-10": "cctv-10.webp",
    "cctv-11": "cctv-11.webp",
    "cctv-12": "cctv-12.webp",
    "cctv-13": "cctv-13.webp",
    "cctv-14": "cctv-14.webp",
    "cctv-15": "cctv-15.webp",
    "cctv-16": "cctv-16.webp",
    "cctv-17": "cctv-17.webp",
    "henan": "henan.webp",
    "hunan": "hunan.webp",
    "zhejiang": "zhejiang.webp",
    "dragon": "dragon.webp",
    "jiangsu": "jiangsu.webp",
    "beijing": "beijing.webp",
}

# Map Elykia stems that correspond directly to the existing 24 core WebP files
STEM_TO_EXISTING = {
    **{f"cctv{i}": f"cctv-{i}.webp" for i in range(1, 18)},
    "cctv5+": "cctv-5plus.webp",
    "河南卫视": "henan.webp",
    "湖南卫视": "hunan.webp",
    "浙江卫视": "zhejiang.webp",
    "东方卫视": "dragon.webp",
    "江苏卫视": "jiangsu.webp",
    "北京卫视": "beijing.webp",
}


def norm(s: str) -> str:
    s = s.strip().lower().replace("＋", "+")
    s = re.sub(r"[\s\-_()（）\[\]·•]", "", s)
    return s


def alias_keys(stem: str) -> set[str]:
    keys = {norm(stem)}
    base = norm(stem)
    # Strip trailing generic suffixes for broader matching (e.g. "河南都市频道" <-> "河南都市")
    for suffix in ("频道", "电视台", "广播电视台", "教育台"):
        if base.endswith(suffix) and len(base) > len(suffix) + 1:
            keys.add(base[: -len(suffix)])
    # CCTV Pay channels e.g. "CCTV风云足球" <-> "风云足球", "CCTV央视台球" <-> "央视台球"
    if base.startswith("cctv") and not re.match(r"^cctv\d", base) and base != "cctv4k":
        sub = base[4:]
        if sub:
            keys.add(sub)
            if sub.startswith("央视") and len(sub) > 2:
                keys.add(sub[2:])
    # CETV aliases
    if re.match(r"^cetv[1-4]$", base):
        num = base[-1]
        keys.update({f"中国教育{num}", f"中国教育{num}套", f"中国教育电视台{num}", f"cetv-{num}"})
    # CGTN aliases
    if base == "cgtn记录":
        keys.update({"cgtn纪录", "cgtn纪录频道", "cgtndocumentary"})
    if base == "cgtn":
        keys.update({"cgtn英语", "cgtnnews"})
    return {k for k in keys if k}


def fetch_png(filename: str) -> bytes:
    quoted = urllib.parse.quote(filename)
    for base in (RAW_BASE, JSDELIVR_BASE):
        for attempt in range(2):
            try:
                req = urllib.request.Request(base + quoted, headers={"User-Agent": "SwiftTV-LogoSync/0.3"})
                with urllib.request.urlopen(req, timeout=15) as r:
                    data = r.read()
                    if len(data) > 64:
                        return data
            except Exception:
                time.sleep(0.4 * (attempt + 1))
    raise RuntimeError(f"Failed to download logo: {filename}")


def convert_to_webp(png_bytes: bytes, dest: Path):
    im = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    alpha = im.getchannel("A")
    bbox = alpha.getbbox()
    if bbox:
        im = im.crop(bbox)
    max_w, max_h = 180, 64
    w, h = im.size
    scale = min(max_w / max(w, 1), max_h / max(h, 1), 1.0)
    if scale < 1.0:
        im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.Resampling.LANCZOS)
    im.save(dest, format="WEBP", lossless=True, method=6)
    # If a photographic badge exceeds 14 KB lossless, re-encode at quality=90 alpha WebP
    if dest.stat().st_size > 14336:
        im.save(dest, format="WEBP", lossless=False, quality=90, alpha_quality=95, method=6)


def main():
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    text = ELYKIA_M3U.read_text(encoding="utf-8")
    logo_urls = sorted({m.group(1) for m in re.finditer(r'tvg-logo="([^"]+)"', text)})
    filenames = sorted({urllib.parse.unquote(u.rsplit("/", 1)[-1]) for u in logo_urls})
    print(f"Found {len(filenames)} distinct logos in Elykia M3U")

    index = dict(EXISTING_CORE)
    to_fetch = []
    for fname in filenames:
        stem = fname.rsplit(".", 1)[0]
        n_stem = norm(stem)
        if n_stem in STEM_TO_EXISTING:
            target_webp = STEM_TO_EXISTING[n_stem]
            for k in alias_keys(stem):
                index.setdefault(k, target_webp)
        else:
            digest = hashlib.sha256(stem.encode("utf-8")).hexdigest()[:10]
            target_webp = f"st-{digest}.webp"
            to_fetch.append((fname, stem, target_webp))

    def process(item):
        fname, stem, target_webp = item
        dest = ASSETS_DIR / target_webp
        if not dest.exists() or dest.stat().st_size == 0:
            raw = fetch_png(fname)
            convert_to_webp(raw, dest)
        return stem, target_webp, dest.stat().st_size

    fetched_items = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(process, item) for item in to_fetch]
        for fut in concurrent.futures.as_completed(futures):
            fetched_items.append(fut.result())
    fetched_count = len(fetched_items)
    fetched_items.sort(key=lambda x: x[0])

    # Pass 1: Register every exact normalized stem deterministically first so 4K/suffix variants never steal base keys
    for stem, target_webp, _ in fetched_items:
        index[norm(stem)] = target_webp

    # Pass 2: Register secondary aliases and 4K->base fallbacks without overwriting exact station keys
    for stem, target_webp, _ in fetched_items:
        for k in alias_keys(stem):
            index.setdefault(k, target_webp)
        if norm(stem).endswith("4k"):
            index.setdefault(norm(stem)[:-2], target_webp)

    # Sort index deterministically
    sorted_index = dict(sorted(index.items()))
    (ASSETS_DIR / "logos.json").write_text(
        json.dumps(sorted_index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    webp_files = list(ASSETS_DIR.glob("*.webp"))
    total_bytes = sum(f.stat().st_size for f in webp_files)
    print(json.dumps({
        "totalWebpFiles": len(webp_files),
        "newlySynced": fetched_count,
        "indexKeys": len(sorted_index),
        "totalWebpBytes": total_bytes,
        "totalWebpKiB": round(total_bytes / 1024, 1)
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
