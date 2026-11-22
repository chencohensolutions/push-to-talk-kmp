#!/usr/bin/env python3
"""Talk's soft chime for incoming calls (2026-11-22, hen: "a soothing ring sound").

A rising pentatonic chime, E5 G5 A5 C6 with C5 under the last, in a 4-second loop: each note a sine plus 0.18 of
its octave, a 12 ms attack and an exp(-t/1.1 s) decay; the last 0.3 s faded out, normalized to 0.7 peak. 44.1 kHz
mono 16-bit. Standard library only:

    python3 tools/ringtone/make_ring.py ring_soft.wav
    ffmpeg -y -i ring_soft.wav -c:a libopus -b:a 64k androidApp/src/main/res/raw/ring_soft.ogg
"""
import math
import struct
import sys
import wave

RATE = 44100
LENGTH = 4.0
FADE = 0.3


def hz(note: int) -> float:
    """MIDI note number to frequency."""
    return 440.0 * 2 ** ((note - 69) / 12)


# (start s, MIDI note, peak)
NOTES = [(0.00, 76, 0.55), (0.28, 79, 0.50), (0.56, 81, 0.48), (0.84, 84, 0.42), (0.84, 72, 0.30)]


def main(path: str) -> None:
    n = int(RATE * LENGTH)
    out = [0.0] * n
    for start, note, peak in NOTES:
        f = hz(note)
        for i in range(int(start * RATE), n):
            t = i / RATE - start
            env = min(1.0, t / 0.012) * math.exp(-t / 1.1)
            out[i] += peak * env * (math.sin(2 * math.pi * f * t) + 0.18 * math.sin(4 * math.pi * f * t))
    fade_from = int((LENGTH - FADE) * RATE)
    for i in range(fade_from, n):
        out[i] *= (n - i) / (n - fade_from)
    scale = 0.7 / max(abs(s) for s in out)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in out))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ring_soft.wav")
