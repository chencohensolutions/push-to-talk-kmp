# Kept-call fixture

`go run .` here (with `a.ivf`, `b.ivf`, `a.ogg`, `b.ogg` made by ffmpeg, below) writes the track blobs and
`manifest.json` that `KeptCallFileTest` reads from `sharedLogic/src/androidHostTest/resources/kept-call/`: two people,
VP8 and Opus, encrypted the way LiveKit's frame cryptor does it, with Go's own PBKDF2 and AES-GCM, so the app's
`LiveKitFrames` is checked against an implementation that isn't its own.

```
ffmpeg -f lavfi -i testsrc=size=160x120:rate=15 -t 2 -c:v libvpx -g 15 -b:v 100k -f ivf a.ivf
ffmpeg -f lavfi -i testsrc2=size=176x144:rate=15 -t 2 -c:v libvpx -g 15 -b:v 100k -f ivf b.ivf
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 2 -ac 1 -c:a libopus -frame_duration 20 -b:a 24k a.ogg
ffmpeg -f lavfi -i sine=frequency=660:sample_rate=48000 -t 2 -ac 1 -c:a libopus -frame_duration 20 -b:a 24k b.ogg
```

The test also writes `build/kept-call.mkv`; `ffmpeg -v error -i sharedLogic/build/kept-call.mkv -map 0 -f null -`
decodes every track of it.
