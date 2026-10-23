# Offline «Джарвис» recognition (Sherpa-ONNX)

## What changed
- Replaced Android `SpeechRecognizer` in `WakeWordService` with direct `AudioRecord` microphone capture and local Sherpa-ONNX inference.
- Uses the Russian streaming T-One CTC model and its 8 kHz audio rate.
- The native Sherpa Android AAR is fetched by the Gradle `downloadSherpaAar` task before Android build tasks.
- Model archive is downloaded on the first run and extracted into the app-private files directory. Later recognition does not need network access.
- Recognition results go through the existing `WakeWord.commandAfter` matcher and `AssistantRunner`.

## First run
The first launch of the wake-word service requires an internet connection to download the Russian model archive (approximately 138 MB before extraction). Keep the app open until model setup completes. The ongoing notification remains visible while the microphone service is active. If setup fails, the notification shows an error; turn the wake-word switch off and on after fixing connectivity/storage.

## Important limitations
- This is local **streaming speech recognition plus keyword matching**, not a dedicated acoustic keyword-spotting model. It can use more CPU/battery and may occasionally miss or mishear the wake word. Test false activations and latency on the target phone.
- Android requires microphone permission and a visible foreground-service notification. The app can restart the service when opened; Android 12+ / Android 14+ restrictions can prevent starting a microphone foreground service directly after boot while the app is in the background. Therefore automatic wake-word listening immediately after reboot is not guaranteed until the user opens the app.
- This change removes the system Google/Android speech-recognition UI from the always-listening service. It does not make other app features (for example, online AI responses) work offline.
- A successful CI build only confirms compilation; actual microphone performance and battery usage still need testing on the Redmi device.

## Upstream references
- Sherpa-ONNX Android releases: https://github.com/k2-fsa/sherpa-onnx/releases
- Russian streaming T-One model: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/t-one-ctc-models.html
