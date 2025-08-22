# SLM — MediaPipe GenAI Sample (Android)

SLM is an Android sample project demonstrating how to integrate MediaPipe GenAI Tasks with a Jetpack Compose UI.  
This repository focuses on using MediaPipe's Android task libraries (LLM inference / text tasks) from Kotlin, with a UI for survey/chat-style flows and LLM-driven follow-ups or validation. The project does **not** include native (JNI/NDK) code or on-device speech/ASR components.

**License:** MIT  
**Language:** Kotlin (Jetpack Compose)

---

## Table of Contents

- [Overview](#overview)
- [Key features](#key-features)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Project layout](#project-layout)
- [Model & asset placement](#model--asset-placement)
- [Configuration notes](#configuration-notes)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License & contact](#license--contact)

---

## Overview

SLM demonstrates a Kotlin + Jetpack Compose Android app that calls MediaPipe GenAI task APIs (for example, LLM inference and text tasks) to perform evaluation and follow-up generation in a survey/chat UI. The project is intended as a starting point for apps that want LLM-powered logic on Android using MediaPipe’s task libraries.

> Important: This repository uses the MediaPipe Android task artifacts — it does **not** bundle or run native inference engines via JNI/NDK, nor does it provide speech-to-text (ASR) implementations.

---

## Key features

- Jetpack Compose UI for chat / survey interactions.
- Use of MediaPipe GenAI / Text task libraries from Kotlin for LLM-style inference and validation.
- Example ViewModel orchestration showing how to call and manage MediaPipe LLM sessions.
- Lightweight example assets and XML configuration (e.g., `data_extraction_rules.xml`) to illustrate app behavior.

---

## Prerequisites

- Android Studio (recent stable version).
- Android SDK matching the `compileSdk` used in `app/build.gradle.kts`.
- Java 11+ (per AGP requirements).
- Internet access to resolve MediaPipe artifacts via Maven (Google Maven).
- Gradle wrapper included — build with `./gradlew`.

---

## Quick start

```bash
git clone https://github.com/ishizuki-tech/SLM.git
cd SLM

# Build debug APK
./gradlew clean assembleDebug --stacktrace
```

Open the project in Android Studio to run on an emulator or device. Because this project references MediaPipe artifacts, ensure your Gradle/Maven configuration can access Google’s Maven repository.

---

## Project layout

```
SLM/
├─ app/                      # Android app module (Compose + ViewModels)
│  ├─ src/main/kotlin/...    # Kotlin source (UI, InferenceModel.kt, etc.)
│  ├─ src/main/res/...       # resources (xml rules, drawables)
│  ├─ src/main/assets/...    # optional runtime assets
│  └─ build.gradle.kts
├─ gradle/
├─ build.gradle.kts
├─ gradle.properties
├─ settings.gradle.kts
├─ README.md
└─ LICENSE
```

Key files:
- `app/build.gradle.kts` — app dependencies (including MediaPipe task libs).
- `app/src/main/kotlin/slm_chat/InferenceModel.kt` — main orchestration for MediaPipe LLM tasks.
- `app/src/main/res/xml/data_extraction_rules.xml` — example extraction/backup config.

---

## Model & asset placement

- This repo does not ship large ML models. If you need model assets for MediaPipe tasks, follow MediaPipe documentation and place required files in `app/src/main/assets/` or configure the app to load them from external storage at runtime. Avoid committing large model files to Git.

---

## Configuration notes

- **Do not use inline comments in `gradle.properties`** (e.g., `android.enableJetifier=true # comment`) — Gradle will fail to parse such lines. Use standalone comment lines if needed.
- Ensure Google Maven is available in the repositories block so MediaPipe artifacts resolve.

---

## Troubleshooting

- **MediaPipe artifacts not resolving**: check `settings.gradle.kts` / `build.gradle.kts` for `maven { url 'https://maven.google.com' }` or equivalent.
- **Compose API errors**: verify Kotlin and Compose compiler versions match the Compose API used.
- **Build parse errors in gradle.properties**: remove trailing inline comments from property lines.

---

## Contributing

- Fork, create a branch, open a PR.
- Avoid committing large model binaries; instead provide scripts or instructions to download required assets.

---

## References

- MediaPipe GenAI LLM Inference documentation: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference


## License & contact

MIT License — see `LICENSE`. For issues and feature requests, open an issue in this repository.
