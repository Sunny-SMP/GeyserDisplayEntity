"""Zip pack/ into packs/GeyserDisplayEntityPack.mcpack, with fixed timestamps so unchanged content zips identically.

Run tools/block_renderer/generate.py first: it writes the block renderer's textures and generated pack files.
"""
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
pack = root / "pack"
out = root / "packs/GeyserDisplayEntityPack.mcpack"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for path in sorted(p for p in pack.rglob("*") if p.is_file()):
        info = zipfile.ZipInfo(path.relative_to(pack).as_posix(), date_time=(2024, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, path.read_bytes())
print(f"{out} ({out.stat().st_size // 1024} KiB)")
