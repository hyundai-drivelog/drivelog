"""Generate DRIVELOG's original, deterministic PCM cues. No external samples."""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
OUTPUT.mkdir(parents=True, exist_ok=True)
# (offset seconds, frequency Hz, length seconds, gain): soft bell-like partials.
CUES = {
    "sound_start": (0.32, [(0, 440, .16, .20), (.10, 659.25, .22, .18)]),
    "sound_saved": (0.44, [(0, 392, .27, .14), (.07, 523.25, .30, .13), (.14, 659.25, .30, .12)]),
    "sound_confirm": (0.18, [(0, 587.33, .18, .20)]),
    "sound_error": (0.30, [(0, 392, .16, .18), (.12, 329.63, .18, .16)]),
}

def render(duration, notes):
    samples = []
    for frame in range(round(duration * RATE)):
        time = frame / RATE
        value = 0.0
        for start, frequency, length, gain in notes:
            t = time - start
            if 0 <= t < length:
                attack = min(t / .012, 1)
                release = min((length - t) / .045, 1)
                envelope = attack * release * math.exp(-5 * t / length)
                value += gain * envelope * (math.sin(2 * math.pi * frequency * t) +
                    .16 * math.sin(2 * math.pi * frequency * 2 * t))
        assert abs(value) < .9, "Unexpected clipping"
        samples.append(value)
    return samples

def write(path, samples):
    with wave.open(str(path), "wb") as output:
        output.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        output.writeframes(b"".join(struct.pack("<h", round(s * 32767)) for s in samples))

for name, (duration, notes) in CUES.items():
    samples = render(duration, notes)
    write(OUTPUT / (name + ".wav"), samples)
    print(f"{name}: {duration:.2f}s, peak={max(map(abs, samples)):.3f}")
