#!/usr/bin/env python3
"""Talk's calm new-message sound (2026-11-23, hen: "the message sound is too alarming, make it calm").

A soft two-note marimba-like "ding-dong", falling: E5 then C5 0.16 s later. Each note a sine with a quiet 4th partial
(a marimba's), a 25 ms attack and an exp(-t/0.22 s) decay; 0.55 s long, the last 0.12 s faded out, normalized to 0.35
peak (quiet). 44.1 kHz mono 16-bit. Original, made here. Standard library only:

    python3 tools/ringtone/make_message_calm.py message_calm.wav
    ffmpeg -y -i message_calm.wav -c:a libopus -b:a 64k androidApp/src/main/res/raw/message_calm.ogg
"""
import math
import struct
import sys
import wave

RATE = 44100
LENGTH = 0.55
FADE = 0.12

# (start s, frequency Hz, peak)
NOTES = [(0.00, 659.25, 0.55), (0.16, 523.25, 0.50)]


def main(path: str) -> None:
    n = int(RATE * LENGTH)
    out = [0.0] * n
    for start, f, peak in NOTES:
        for i in range(int(start * RATE), n):
            t = i / RATE - start
            env = min(1.0, t / 0.025) * math.exp(-t / 0.22)
            out[i] += peak * env * (math.sin(2 * math.pi * f * t) + 0.06 * math.sin(2 * math.pi * 4 * f * t) * math.exp(-t / 0.05))
    for i in range(n):
        left = (n - i) / RATE
        if left < FADE:
            out[i] *= left / FADE
    top = max(abs(x) for x in out) or 1.0
    scale = 0.35 / top
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(x * scale * 32767)) for x in out))


if __name__ == "__main__":
    main(sys.argv[1])
