"""Original background track for the demo: soft pad, plucked arpeggio and a light shaker.

100 BPM, Cmaj7 - G6 - Am7 - Fmaj7, two bars each, 92 seconds, 48 kHz stereo 16-bit WAV.
"""
import sys
import wave

import numpy as np

SR = 48_000
BPM = 100
BEAT = 60 / BPM
LENGTH = 92.0
out = sys.argv[1]

n = int(SR * LENGTH)
t = np.arange(n) / SR
left = np.zeros(n)
right = np.zeros(n)


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


CHORDS = [
    [48, 55, 59, 64, 67],   # Cmaj7 (C G B E G)
    [43, 50, 55, 59, 64],   # G6    (G D G B E)
    [45, 52, 55, 60, 64],   # Am7   (A E G C E)
    [41, 48, 52, 57, 64],   # Fmaj7 (F C E A E)
]
BAR = 4 * BEAT
CHORD_LEN = 2 * BAR

# Pad: detuned sines with slow attack and release per chord.
pos = 0.0
i = 0
while pos < LENGTH:
    chord = CHORDS[i % len(CHORDS)]
    s, e = int(pos * SR), min(n, int((pos + CHORD_LEN + 0.6) * SR))
    seg = t[s:e] - pos
    env = np.minimum(1, seg / 1.2) * np.minimum(1, np.maximum(0, (CHORD_LEN + 0.6 - seg) / 0.9))
    for k, m in enumerate(chord):
        f = hz(m)
        amp = 0.05 if k else 0.07
        left[s:e] += amp * env * np.sin(2 * np.pi * f * 0.998 * seg)
        right[s:e] += amp * env * np.sin(2 * np.pi * f * 1.002 * seg + 0.3)
    pos += CHORD_LEN
    i += 1

# Pluck: eighth-note arpeggio an octave up, from bar 3 on.
eighth = BEAT / 2
step = 0
pos = 2 * BAR
pattern = [1, 2, 3, 4, 3, 2, 3, 4]
while pos < LENGTH - 2:
    chord = CHORDS[int(pos // CHORD_LEN) % len(CHORDS)]
    m = chord[pattern[step % len(pattern)]] + 12
    f = hz(m)
    s = int(pos * SR)
    e = min(n, s + int(0.9 * SR))
    seg = t[s:e] - pos
    env = np.exp(-seg * 5.5) * np.minimum(1, seg / 0.004)
    tone = np.sin(2 * np.pi * f * seg) + 0.35 * np.sin(4 * np.pi * f * seg) * np.exp(-seg * 9)
    pan = 0.5 + 0.3 * np.sin(step * 0.7)
    left[s:e] += 0.045 * env * tone * (1 - pan) * 2
    right[s:e] += 0.045 * env * tone * pan * 2
    pos += eighth
    step += 1

# Shaker on the off-beats from bar 5, plus a soft low thump on beats 1 and 3.
rng = np.random.default_rng(7)
pos = 4 * BAR
b = 0
while pos < LENGTH - 2:
    s = int((pos + BEAT / 2) * SR)
    e = min(n, s + int(0.08 * SR))
    seg = t[s:e] - (pos + BEAT / 2)
    noise = rng.standard_normal(e - s)
    noise = np.diff(noise, prepend=0)  # brighter
    shaker = 0.018 * noise * np.exp(-seg * 60)
    left[s:e] += shaker
    right[s:e] += shaker
    if b % 2 == 0:
        s2 = int(pos * SR)
        e2 = min(n, s2 + int(0.35 * SR))
        seg2 = t[s2:e2] - pos
        thump = 0.10 * np.sin(2 * np.pi * (55 + 40 * np.exp(-seg2 * 30)) * seg2) * np.exp(-seg2 * 9)
        left[s2:e2] += thump
        right[s2:e2] += thump
    pos += BEAT
    b += 1

# Fades, normalise.
fade_in = np.minimum(1, t / 2.0)
fade_out = np.minimum(1, np.maximum(0, (LENGTH - t) / 3.0))
mix = np.stack([left, right], axis=1) * (fade_in * fade_out)[:, None]
mix /= np.max(np.abs(mix)) / 0.89
pcm = (mix * 32767).astype(np.int16)
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print(out, LENGTH)
