# Android Auto

Talk in the car (1.3.0; 2026-11-22, hen), in two parts.

## Notifications (any car)

Text messages and talk notifications are conversation notifications (`MessagingStyle`, the sender as a `Person`,
groups with their name), with **Reply** (a `RemoteInput`, `SEMANTIC_ACTION_REPLY`, no UI: the car dictates it) and
**Mark as read** (`SEMANTIC_ACTION_MARK_AS_READ`). Android Auto reads them aloud and sends dictated replies as texts.
`res/xml/automotive_app_desc.xml` declares `notification` (and `template`). A burst that plays while the car is
connected goes through the phone's media path ("Talk is ready"), which Android Auto plays on the car's speakers.

## The Car App (talk)

`TalkCarAppService` (Car App Library 1.7, category messaging, `minCarApiLevel` 7):

- **Home:** a grid of up to six conversations, Quick talk first (`TalkWidgetChoices`), with the talk icon and the name;
  "● Dana is talking" while a burst from them plays, "Wait: someone is talking" when the floor there is taken.
- **A tap** starts talking there at once: a beep, the car's microphone (`CarAudioRecord`, 16 kHz, with the head unit's
  own processing; `CarTalkCapture` raises it to 48 kHz for Opus), "Talking to Family · 0:03" with **Send** and
  **Cancel**. The floor as on the phone; taken: "Wait", nothing sent.
- **It ends by itself** when the speech does (`VoiceEnd` on Silero VAD's speech probability every 32 ms): sent after
  1.5 s without speech following at least 200 ms of it (pauses inside a sentence don't end it), with a second beep;
  cancelled with "Nothing heard" if nobody spoke in the first 4 s; sent at 60 s. The threshold adapts to the noise
  measured in the first 300 ms after the beep (0.5, or the noise's own speech probability plus 0.3, at most 0.85).
- **The detector:** Silero VAD v5 (`assets/silero_vad.onnx`, MIT, 2.3 MB) on ONNX Runtime (`SileroSpeechDetector`),
  robust to road, engine and fan noise; `EnergySpeechDetector` only if the model can't load. ONNX Runtime adds about
  36 MB to the installed app (arm64); a slimmed build with only Silero's operators is a follow-up. Tested on speech
  mixed with car-like noise at 0, 5 and 10 dB SNR (`VoiceEndTest`, traces made by `tools/vad/make_traces.py`).

## Trying it

A sideloaded Talk shows in Android Auto only with Android Auto's developer settings › **Unknown sources** on (tap
the version in Android Auto's settings ten times to unlock developer settings). The Desktop Head Unit (Android SDK
extras) runs it on a computer.
