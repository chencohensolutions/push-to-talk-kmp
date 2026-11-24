# Face style models

How the LiteRT models for Your picture were made (docs/design.md, "Pictures"). Run with Python 3.11 and
TensorFlow 2.18, onnx, onnxruntime, opencv and ai-edge-litert.

- `convert_dctnet.py 384`: DCT-Net (menyifang/DCT-Net, Apache-2.0) frozen graphs `cartoon_h.pb` from ModelScope
  (`damo/cv_unet_person-image-cartoon-3d_compound-models`, `-sd-design_`, `-handdrawn_`) to `style_soft`,
  `style_painted`, `style_handdrawn`.tflite: fp16, input 384×384×3 BGR 0–255 (no batch), output uint8 BGR.
- `convert_ink.py`: Informative Drawings (carolineec/informative-drawings, MIT) `contour.onnx` rebuilt op by op in
  TensorFlow (checked against onnxruntime: max difference 7e-5) to `style_ink.tflite`: fp16, input 1×384×384×3 RGB 0–1,
  output 1×384×384×1 lines (0 ink, 1 paper).

The app downloads them as `face_soft.tflite`, `face_painted.tflite`, `face_handdrawn.tflite` and `face_ink.tflite` from
the release `face-models-1`; their sizes are in `FaceStyle`. The selfie segmenter (MediaPipe, Apache-2.0) is bundled in
the app's assets as `selfie_segmenter.tflite`.
