"""Validate the authored MP4 metadata, subtitle timing and production assets."""
from pathlib import Path
import hashlib
import json
import re
import struct

root = Path(__file__).resolve().parent.parent / "publicacao-play-store" / "video-youtube"
probe = json.loads((root / "_producao" / "validacao-video.json").read_text(encoding="utf-8-sig"))
video = next(item for item in probe["streams"] if item["codec_type"] == "video")
audio = next(item for item in probe["streams"] if item["codec_type"] == "audio")
duration = float(probe["format"]["duration"])
assert (video["codec_name"], video["width"], video["height"], video["pix_fmt"]) == (
    "h264", 1920, 1080, "yuv420p")
assert video["r_frame_rate"] == "30/1"
assert (audio["codec_name"], audio["sample_rate"], audio["channels"]) == ("aac", "48000", 2)
assert 90 < duration < 105
assert abs(float(video["duration"]) - float(audio["duration"])) < 0.1
assert not (root / "_producao" / "validacao-decodificacao.txt").read_text().strip()

def seconds(timestamp):
    hour, minute, second, milli = map(int, re.split(r"[:,]", timestamp))
    return 3600 * hour + 60 * minute + second + milli / 1000

captions = (root / "legendas-pt-BR.srt").read_text(encoding="utf-8-sig").strip().split("\n\n")
previous = 0.0
for index, block in enumerate(captions, 1):
    lines = block.splitlines()
    assert lines[0] == str(index)
    start, end = map(seconds, lines[1].split(" --> "))
    assert previous <= start < end <= duration
    assert 1 <= len(lines[2:]) <= 2
    assert all(len(line) <= 43 for line in lines[2:])
    previous = end
assert len(captions) >= 15
timeline = json.loads((root / "roteiro-e-tempos.json").read_text(encoding="utf-8-sig"))
assert len(timeline["cenas"]) == 8
assert abs(timeline["duracao_prevista"] - duration) < 0.1
assert timeline["cenas"][0]["inicio"] == 0
for scene, next_scene in zip(timeline["cenas"], timeline["cenas"][1:]):
    assert abs(scene["inicio"] + scene["duracao"] - next_scene["inicio"]) < 0.001

thumbnail = root / "miniatura-youtube-1280x720.png"
assert struct.unpack(">II", thumbnail.read_bytes()[16:24]) == (1280, 720)
assert thumbnail.stat().st_size < 2 * 1024 * 1024
movie = root / "chat-de-rua-como-usar-1080p.mp4"
data = movie.read_bytes()
assert data.find(b"moov") < data.find(b"mdat"), "Expected MP4 faststart"
audio_log = (root / "_producao" / "validacao-audio.txt").read_text(encoding="utf-8-sig")
levels, _ = json.JSONDecoder().raw_decode(audio_log[audio_log.rfind("{"):])
assert -18 <= float(levels["input_i"]) <= -14
assert float(levels["input_tp"]) <= 0
report = {
    "status": "aprovado",
    "duracao_segundos": duration,
    "resolucao": "1920x1080",
    "fps": 30,
    "video": "H.264 / yuv420p / faststart",
    "audio": "AAC / 48 kHz / stereo",
    "volume_integrado_lufs": float(levels["input_i"]),
    "pico_db_tpu": float(levels["input_tp"]),
    "cenas": 8,
    "trechos_legenda": len(captions),
    "decodificacao_completa": "sem erros",
    "bytes": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
}
(root / "validacao-final.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
