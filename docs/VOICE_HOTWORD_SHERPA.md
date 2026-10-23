# Offline hotword detection with sherpa-onnx

## Goal
Replace Android `SpeechRecognizer` in `WakeWordService` with local inference while keeping the existing `AssistantRunner.process` command path and the user's on/off preference.

## Official references
- Android integration: https://k2-fsa.github.io/sherpa/onnx/android/index.html
- Keyword spotting documentation: https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/kws/index.rst
- Model catalog/releases: https://github.com/k2-fsa/sherpa-onnx/releases

## Implementation plan
1. Integrate the official sherpa-onnx Android Java API and matching native libraries for the app's supported ABIs.
2. Select and verify a Russian offline model and its license, download it to app-private storage on first use, and validate its files before initialization.
3. Capture microphone audio with `AudioRecord`; do not use Android `SpeechRecognizer` or show the Google speech UI.
4. Detect `Джарвис`, then recognize the following command locally and pass the text to `AssistantRunner.process`.
5. Keep listening explicitly enabled by the user and retain a visible microphone foreground-service notification.
6. Test build and behavior on a physical Android 11 device, including screen-off operation, false triggers, noise, permission revocation, missing/corrupt model, stop action, and battery use.

## Important constraint
A general Russian speech-to-text model is not automatically a dedicated keyword-spotting model. Validate the exact `Джарвис` detection behavior before replacing the existing implementation. Android may restrict starting microphone foreground services from a boot receiver while the app is backgrounded; do not bypass those restrictions.
