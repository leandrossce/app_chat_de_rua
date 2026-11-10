"""Generate small deterministic fixtures with Python's standard library and FFmpeg."""
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

ROOT = Path(__file__).parent / "assets" / "stickers"
ROOT.mkdir(parents=True, exist_ok=True)
FFMPEG = shutil.which("ffmpeg")
if not FFMPEG:
    raise SystemExit("FFmpeg is required to regenerate the committed fixtures")


def chunk(tag, payload):
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload))


def png(path, width, height, pixel):
    rows = b"".join(b"\0" + b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def ffmpeg(*args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *map(str, args)], check=True)


png(ROOT / "transparent.png", 128, 96, lambda x, y: (255, 80, 0, 255 if x > 16 and y > 16 else 0))
source = "testsrc2=size=160x96:rate=8:duration=1"
ffmpeg("-f", "lavfi", "-i", source, "-c:v", "libx264", "-pix_fmt", "yuv420p", ROOT / "short.mp4")
ffmpeg("-display_rotation:v:0", "90", "-i", ROOT / "short.mp4", "-c", "copy", ROOT / "rotated.mp4")
ffmpeg("-f", "lavfi", "-i", source, ROOT / "animated.gif")
ffmpeg("-f", "lavfi", "-i", source, "-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0", ROOT / "animated.webp")
ffmpeg("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=2:duration=7", "-c:v", "libx264", ROOT / "long.mp4")
ffmpeg("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=2:duration=7", ROOT / "long.gif")
ffmpeg("-f", "lavfi", "-i", "sine=frequency=400:duration=0.3", "-c:a", "aac", ROOT / "audio.m4a")
ffmpeg("-f", "lavfi", "-i", "sine=frequency=400:duration=0.3", "-c:a", "libmp3lame", ROOT / "audio.mp3")


def le24(value):
    return value.to_bytes(3, "little")


def webp_chunk(tag, payload):
    return tag + struct.pack("<I", len(payload)) + payload + (b"\0" if len(payload) % 2 else b"")


def frame(color, x, flags):
    # A partial rectangle, then a transparent disposal and a no-blend replacement.
    png(ROOT / "frame.png", 32, 32, lambda px, py: color)
    ffmpeg("-i", ROOT / "frame.png", "-c:v", "libwebp", "-lossless", "1", ROOT / "frame.webp")
    encoded = (ROOT / "frame.webp").read_bytes()
    offset = 12
    chunks = b""
    while offset < len(encoded):
        size = struct.unpack_from("<I", encoded, offset + 4)[0]
        end = offset + 8 + size + size % 2
        if encoded[offset:offset + 4] in (b"ALPH", b"VP8 ", b"VP8L"):
            chunks += encoded[offset:end]
        offset = end
    header = le24(x // 2) + le24(0) + le24(31) + le24(31) + le24(250) + bytes([flags])
    return webp_chunk(b"ANMF", header + chunks)


chunks = webp_chunk(b"VP8X", bytes([18, 0, 0, 0]) + le24(63) + le24(31))
chunks += webp_chunk(b"ANIM", b"\0" * 6)
chunks += frame((255, 0, 0, 255), 0, 3)
chunks += frame((0, 255, 0, 255), 32, 2)
(ROOT / "partial.webp").write_bytes(b"RIFF" + struct.pack("<I", len(chunks) + 4) + b"WEBP" + chunks)
(ROOT / "frame.png").unlink()
(ROOT / "frame.webp").unlink()
print("Created sticker fixtures in", ROOT)
