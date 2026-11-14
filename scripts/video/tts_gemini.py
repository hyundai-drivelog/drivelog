"""Makes the demo narration with Gemini TTS (natural Korean voice).

Reads id|text lines (UTF-8) and writes <out_dir>/<id>.wav (24 kHz mono 16-bit) for each.
Needs GEMINI_API_KEY in the environment. Only the narration text is sent to the API.
The free tier allows 10 requests a day per model; --model picks another model with its own quota.

    python scripts/video/tts_gemini.py scripts/video/narration.txt .local-qa/video-2026-11-13/audio
    python scripts/video/tts_gemini.py narration.txt audio --only n3 n7     # remake some lines
    python scripts/video/tts_gemini.py narration.txt audio --voice Autonoe

Voices: Zephyr (bright, default), Autonoe (bright), Leda (youthful), Aoede (breezy),
Achird (friendly), Puck (upbeat, male), Charon (informative, male).
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import wave

MODEL = "gemini-3.1-flash-tts-preview"   # 3.8-flash-tts reads Korean at about half this pace
STYLE = "Say in a bright, warm and friendly Korean narrator voice, at a lively pace"
URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def synth(text, voice, model, key):
    body = {
        "contents": [{"parts": [{"text": f"{STYLE}: {text}"}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}},
        },
    }
    req = urllib.request.Request(
        URL.format(model=model), data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": key})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.load(r)
            part = data["candidates"][0]["content"]["parts"][0]["inlineData"]
            return base64.b64decode(part["data"])
        except (urllib.error.HTTPError, urllib.error.URLError, KeyError, IndexError) as e:
            detail = e.read().decode(errors="replace")[:300] if isinstance(e, urllib.error.HTTPError) else repr(e)
            if attempt == 5:
                sys.exit(f"TTS failed: {detail}")
            # The free tier allows only a few requests a minute.
            time.sleep(35 if getattr(e, "code", 0) == 429 else 5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lines")
    ap.add_argument("out_dir")
    ap.add_argument("--voice", default="Zephyr")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--pause", type=float, default=21, help="seconds between requests (free-tier rate limit)")
    ap.add_argument("--only", nargs="*", help="ids to remake; default all")
    a = ap.parse_args()
    key = os.environ.get("GEMINI_API_KEY") or sys.exit("GEMINI_API_KEY is not set")
    os.makedirs(a.out_dir, exist_ok=True)
    with open(a.lines, encoding="utf-8") as f:
        items = [l.split("|", 1) for l in f.read().splitlines() if "|" in l]
    for nid, text in items:
        nid, text = nid.strip(), text.strip()
        if a.only and nid not in a.only:
            continue
        pcm = synth(text, a.voice, a.model, key)
        path = os.path.join(a.out_dir, nid + ".wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(pcm)
        print(f"{nid} {len(pcm) / 48000:.2f}s", flush=True)
        time.sleep(a.pause)


if __name__ == "__main__":
    main()
