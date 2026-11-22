#!/usr/bin/env python3
"""Talk's calm new-message sound (2026-11-22, hen: "generate also calm message audio").

Two notes, A5 then E6 0.12 s later: each a sine plus 0.15 of its octave, a 10 ms attack and an exp(-t/0.35 s) decay;
0.9 s long, the last 0.15 s faded out, normalized to 0.6 peak. 44.1 kHz mono 16-bit. Standard library only:

    python3 tools/ringtone/make_message.py message_soft.wav
    ffmpeg -y -i message_soft.wav -c:a libopus -b:a 64k androidApp/src/main/res/raw/message_soft.ogg
"""
import math
import struct
import sys
import wave

RATE = 44100
LENGTH = 0.9
FADE = 0.15

# (start s, frequency Hz, peak)
NOTES = [(0.00, 880.0, 0.50), (0.12, 1318.51, 0.38)]


def main(path: str) -> None:
    n = int(RATE * LENGTH)
    out = [0.0] * n
    for start, f, peak in NOTES:
        for i in range(int(start * RATE), n):
            t = i / RATE - start
            env = min(1.0, t / 0.010) * math.exp(-t / 0.35)
            out[i] += peak * env * (math.sin(2 * math.pi * f * t) + 0.15 * math.sin(4 * math.pi * f * t))
    fade_from = int((LENGTH - FADE) * RATE)
    for i in range(fade_from, n):
        out[i] *= (n - i) / (n - fade_from)
    scale = 0.6 / max(abs(s) for s in out)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in out))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "message_soft.wav")
