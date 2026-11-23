# Message sound: soft tone M2

hen's pick (2026-11-24): a soft, low, single note (A3, 220 Hz, with a faint octave), about 0.9 s,
with a short diffuse room fade and no repeating echo. Synthesized from scratch (no third-party audio),
so there is no license to track. `soft-tone-m2.py` regenerates it (numpy + ffmpeg).
Files: `message-soft-tone-m2.wav` (16-bit PCM, 48 kHz, mono, uncompressed: use this one in the app, hen asked for lossless), plus `.ogg` and `.mp3` for listening.

## Final pick: `message-soft-tone-e.wav`

hen then asked for a higher pitch and picked E (E4, 329.6 Hz). It's the same tone with
a smooth raised-cosine start and release, so there is no click at either end (the first M2 cut its
dry tone off at 0.45 s, which clicked). 16-bit PCM, 48 kHz, mono, 1.05 s, true peak -8 dBFS.
**Use this file as the message sound.**
