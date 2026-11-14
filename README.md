# SleepGateAI Android prototype

Private local workspace; nothing has been pushed to GitHub. Project code is licensed under BSL 1.1; see `LICENSE`.

## Direction
- Deterministic on-device DSP and frequency-band features; no LLM and no SleepFM model/data.
- Reuse RMAA's general band-processing approach, then calibrate sleep-specific bands and thresholds from SleepGate recordings. CNC/music thresholds are not sleep thresholds.
- The current prototype is audio-only. Sensor acquisition, Parquet logging, and Supabase synchronization are not implemented yet.
- Process microphone data on-device; raw audio is not uploaded by default. Upload only consented derived features/session data.

## Storage and Supabase
The manifest includes `INTERNET` for the planned consumer sync path. Keep session files in app-private storage and sync only after consent, over TLS, using authenticated users and strict Supabase RLS policies. Never embed a Supabase service-role key in the APK; use only a client publishable/anon key. No Supabase URL, key, schema, or upload code is configured yet.

## Build/test
The project targets SDK 35 / min SDK 28. Docker can provide the Android build SDK. This Docker host has no `/dev/kvm`, so a containerized Android emulator will need slow software acceleration and may not boot reliably.

This is a personal wellness prototype, not a sleep-stage diagnostic or proven treatment.
