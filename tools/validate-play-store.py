"""Validate publishing exports using only the Python standard library."""
from pathlib import Path
import csv
import hashlib
import json
import struct

root = Path(__file__).resolve().parent.parent / "publicacao-play-store"
checks = []
expected = {"telefone": (1080, 1920), "tablet-7": (1080, 1920), "tablet-10": (1440, 2560)}

def png_info(path):
    with path.open("rb") as stream:
        header = stream.read(33)
    assert header[:8] == b"\x89PNG\r\n\x1a\n", f"Invalid PNG: {path}"
    width, height, depth, colour = struct.unpack(">IIBB", header[16:26])
    return width, height, depth, colour

for profile, size in expected.items():
    files = sorted((root / "prints" / profile).glob("*.png"))
    assert len(files) == 5, f"Expected five screenshots: {profile}"
    assert {file.name for file in files} == {
        "01-inicio.png", "02-senha-da-sala.png", "03-conversa.png",
        "04-figurinhas.png", "05-selecao-de-idioma.png"}, f"Wrong screens: {profile}"
    for file in files:
        width, height, depth, colour = png_info(file)
        assert (width, height) == size, f"Wrong dimensions: {file}"
        assert depth == 8 and colour == 2, f"Screenshot must be RGB without alpha: {file}"
        assert min(width, height) >= 1080 and max(width, height) <= 3840
        assert max(width, height) <= 2 * min(width, height)
        assert width * 16 == height * 9
        checks.append(f"OK PNG RGB 9:16 {width}x{height}: {file.relative_to(root)}")

assert png_info(root / "graficos" / "icone-512x512.png") == (512, 512, 8, 6)
assert (root / "graficos" / "icone-512x512.png").stat().st_size <= 1024 * 1024
assert png_info(root / "graficos" / "destaque-1024x500.png") == (1024, 500, 8, 2)
checks.append("OK icon RGBA 512x512 and feature graphic RGB 1024x500")

for name, limit in [("01-nome.txt", 30), ("02-descricao-curta.txt", 80),
                    ("03-descricao-completa.txt", 4000), ("04-notas-da-versao.txt", 500)]:
    content = (root / "textos" / name).read_text(encoding="utf-8-sig").strip()
    assert 0 < len(content) <= limit, f"Character limit exceeded: {name}"
    checks.append(f"OK text {len(content)}/{limit}: {name}")

with (root / "textos" / "09-textos-alternativos.csv").open(encoding="utf-8-sig", newline="") as stream:
    for row in csv.DictReader(stream, delimiter=";"):
        assert 0 < len(row["texto_alternativo"]) <= 140, row
checks.append("OK accessibility descriptions <= 140 characters")

hashes = []
for file in sorted((root / "pacote").iterdir()):
    if file.suffix in (".aab", ".apk"):
        hashes.append(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}")
assert len(hashes) == 2
(root / "validacao" / "SHA256SUMS.txt").write_text("\n".join(hashes) + "\n", encoding="utf-8")
manifest = json.loads((root / "validacao" / "manifesto.json").read_text(encoding="utf-8-sig"))
assert (manifest["versionCode"], manifest["minSdk"], manifest["targetSdk"]) == (1, 24, 36)
assert manifest["idiomas_interface"] == ["pt-BR", "en-US", "de-DE", "zh-CN", "es-ES"]
checks.append("OK package checksums and SDK/version inventory")
report = "\n".join(checks) + f"\n\n{len(checks)} checks passed.\n"
(root / "validacao" / "requisitos-dos-arquivos.txt").write_text(report, encoding="utf-8")
print(report)
