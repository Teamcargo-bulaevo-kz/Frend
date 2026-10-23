# Sherpa-ONNX wake-word implementation findings

Branch: `voice-hotword-background`

## What the official Android example confirms

The official Sherpa-ONNX KWS Android example uses `AudioRecord` at 16 kHz mono and streams PCM frames into `KeywordSpotter`. This avoids Android `SpeechRecognizer` and its recognition UI/beeps.

Official example:
https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxKws

## Important language limitation

The KWS helper included in the official example only wires two pretrained keyword-spotting models: Chinese (WenetSpeech) and English (GigaSpeech). It is not valid to point either model at the Russian wake phrase «Джарвис» and assume reliable detection.

Sherpa-ONNX's streaming ASR example does include Russian models (including small Vosk-derived Zipformer models), so a possible first prototype is local Russian streaming ASR plus matching the recognized text for «Джарвис». This is not the same as a dedicated Russian KWS model and may use more CPU/battery or have worse false-trigger behavior.

Model/API reference:
https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxKws/app/src/main/java/com/k2fsa/sherpa/onnx/OnlineRecognizer.kt

## Runtime/build requirements

- The sherpa-onnx Android JNI libraries must be included for supported CPU ABIs; a Kotlin dependency alone is not enough.
- A compatible model and its token/config files must be provided. These should be downloaded on first run with progress/error handling rather than committing large binary models into this repository.
- The microphone capture loop must run in the existing foreground microphone service and release AudioRecord/native resources cleanly.
- The wake-word preference and command execution path should remain integrated with the existing `WakeWordService` and `AssistantRunner`.
- Validate on the target Xiaomi phone: build/install, Russian wake-word accuracy, false activations, latency, battery use, lock-screen/background behavior, and Android microphone permission rules.

## CI

The APK workflow now also watches pushes to `voice-hotword-background`, so changes on this test branch can be built without merging into `main`.

This document records the engineering constraints; it does not claim that offline recognition has already been implemented.
