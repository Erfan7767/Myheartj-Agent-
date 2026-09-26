# Irfan & Elena — Dual-Agent Voice Assistant (Android)

A complete, real Android application: two AI agents — **Irfan** (wise, calm, male voice) and
**Elena** (brilliant, warm, female voice) — with **live real-time voice conversation**, a
**real network connection to Google's official Gemini servers** via `OkHttpClient`, genuine
**two-agent collaboration**, **emotional intelligence**, and a real **self-extending tool
system (ToolForge)** that lets the agents create brand-new tools at runtime when none exists.

---

## 1. Quick start

1. Open the `IrfanElena/` folder in **Android Studio** (Hedgehog/Koala or newer, JDK 17).
   Android Studio supplies `local.properties` (SDK path) automatically.
2. Put your **free Gemini API key** in `local.properties`:
   ```properties
   GEMINI_API_KEY=AIza...your_key...
   ```
   Get one at **https://aistudio.google.com/apikey**.
   *(Alternatively: launch the app and enter the key once in Settings — it is saved in
   app-private storage. Build-time injection is the recommended path.)*
3. Run on a device/emulator with **Android 8.0+ (API 26+)**, microphone enabled and
   the Google speech recognition service present (any device with the Google app).
4. Talk — press the microphone button and speak. Or type.

CLI build:

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # debug-signed release APK, installs anywhere
./gradlew :app:testDebugUnitTest    # runs the JUnit verification suite
```

`local.properties` is **git-ignored** — no key ever reaches version control. The key is
injected into `BuildConfig` at build time by `app/build.gradle.kts`.

---

## 2. The two agents

| | Irfan | Elena |
|---|---|---|
| Identity | Primary agent — active the moment the app opens | Secondary agent — takes over fully the moment you ask for her |
| Voice | Deeper pitch (0.85), male-preferred voice if the TTS engine provides one | Brighter pitch (1.30), female-preferred voice if the TTS engine provides one |
| Style | Precise, thoughtful, quietly witty | Bright, engaging, emotionally perceptive |
| Capabilities | Identical: full tool access, ToolForge self-creation, artifacts, empathy | Identical |

**Switching:** name either agent in speech or text ("Elena…", "عرفان…") and control hands
over fully and immediately — the UI mode chips (`Auto / Irfan / Elena / Both`) force it too.

**Collaboration ("Both" / "…معاً" / "both of you…"):** a genuine joint execution — Irfan
produces his real contribution first, Elena receives it, verifies/corrects/extends it, and
adds hers. Each part is displayed under its author and **spoken in its author's own voice**.

**Single-agent execution:** when one agent is addressed, the other is completely silent —
zero interference.

---

## 3. Real backend connection (no mocks anywhere)

`network/GeminiClient.kt` performs a real HTTPS POST for every single reply:

- Endpoint (mandated primary):
  `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent`
- Real `OkHttpClient` (connect 15 s / read 120 s / write 30 s, connection-retry on).
- Real `generateContent` JSON body: `system_instruction`, `contents` (text parts,
  `function_call`, `function_response`), `tools.function_declarations`,
  `safety_settings`, `generation_config`.
- Real parsing of `candidates[0].content.parts` (text + function calls).
- Real key handling: `local.properties` → `BuildConfig` (build-time), private
  SharedPreferences (runtime fallback). Header: `x-goog-api-key`.
- Real error handling: IO/timeout retry, HTTP 429/401/403/5xx mapped to clear user
  messages, and an automatic **model fallback chain**
  (`gemini-3.5-flash → gemini-flash-latest → gemini-2.5-flash → 2.0 → 1.5-flash`) that
  keeps the app fully usable if a model is unavailable for your key.
- **Zero mock classes. Zero fake JSON.** Remove the key and the app honestly reports
  `MISSING_KEY`; cut the network and it honestly reports the network error with retry.

## 4. Real-time voice loop

```
Mic → SpeechRecognizer (continuous, auto-rearm, partial results on screen)
    → AgentRouter (who speaks? Irfan / Elena / both)
    → EmotionAnalyzer (mood → empathy guidance + voice modulation)
    → GeminiClient (real network, agentic tool loop ≤6 rounds)
    → reply split into sentences → TTS starts speaking line 1 immediately
    → each line synthesized with the speaking agent's voice/pitch/rate
    → orchestrator resumes the mic when speech ends
```

Perceived latency is minimized by sentence-level TTS pipelining: playback begins as soon
as the first spoken line exists — not when the whole reply arrives.

## 5. Emotional intelligence

`EmotionAnalyzer` detects joy / sadness / anger / fear / stress (Arabic + English lexicon,
normalized Arabic matching). The reading (1) injects explicit empathy guidance into the
system prompt, (2) modulates TTS pitch/rate so the *voice* itself responds to the mood,
and (3) drives the UI mood indicator.

## 6. ToolForge — the self-creating tool system

The agents are not limited to a fixed toolset. When no ready tool fits, they **must** call
`create_tool` and ToolForge does the rest:

1. The model returns a full tool definition (JSON DSL) via real function calling.
2. ToolForge **validates** it (name grammar, step whitelist, URL scheme, regex compilation,
   size caps).
3. **Persists** it to app-private storage (survives restarts).
4. **Registers** it in the live `ToolRegistry` — instantly callable, and it stays exposed
   to the model on every future turn.

Built-ins: `get_current_time`, `calculator`, `get_device_info`, `web_fetch` (live HTTP) +
meta tools `create_tool`, `list_tools`, `delete_tool`. A dispatch miss returns a hint that
steers the agents to forge a tool — the self-extension loop always closes.

Forged-tool DSL (executed for real by `DslProgramRunner`):

```json
{
  "name": "weather_lookup",
  "description": "Current weather for a city",
  "params": [{"name": "city", "description": "City name", "required": true}],
  "steps": [
    {"type": "http", "url": "https://wttr.in/{{city}}?format=j1", "method": "GET", "save_as": "raw"},
    {"type": "json_extract", "source": "raw", "path": "current_condition[0].temp_C", "save_as": "temp"},
    {"type": "template", "template": "{{city}}: {{temp}}°C", "save_as": "answer"}
  ],
  "returns": "{{answer}}"
}
```

Step types: `http`, `template`, `math`, `json_extract`, `regex_extract`,
`text_transform`, `constant`. Engineering decision: a validated declarative DSL executed by
a sandboxed runner is the safe way to run dynamic tools on stock Android (no arbitrary
runtime code loading), and it covers the realistic space of tools (API + transformation
pipelines) with real executors — no stubs.

## 7. Architecture

```
com.arenaai.duagents
├── MainActivity / DuAgentsApp / core.AppContainer      (manual DI — compile-time verified)
├── ui/        ChatScreen, Bubbles, Dialogs, ChatViewModel, theme
├── voice/     SpeechInputManager (recognition) · SpeechOutputManager (dual-voice TTS)
├── core/      AgentPersona (Irfan/Elena) · AgentRouter · EmotionAnalyzer
│              ConversationMemory · KeyVault
├── network/   GeminiClient (OkHttp → official endpoint, fallback chain, function calling)
├── tools/     ToolRegistry · BuiltInTools · ToolForge · DslProgramRunner · CalculatorEngine
└── orchestration/ ConversationOrchestrator (agentic loop, single + collaborative flows)
```

Key engineering decisions (documented inline in code as well):
- **Jetpack Compose + Material 3** — reactive chat UI with live status/typing states.
- **minSdk 26** — covers adaptive icons, `java.time`, modern TTS; **target/compile 34**.
- **org.json** for Gemini payloads — zero extra deps, exact control over the wire format.
- **Manual DI** — small graph, no annotation processors.
- **Compose UI owns theming**; XML theme only paints the launch background.
- **configChanges locked** — rotation never kills a live voice session.
- **Barge-in disabled (v1)** — the mic pauses while agents think/speak; reliability first.
- **Voice gender layering** — engine voice-name heuristics *plus* pitch/rate personas, so
  identity is audible on every TTS engine.

## 8. Verification performed

- Full Gradle build (`assembleDebug` + `assembleRelease`) executed in CI-sandbox —
  see `VERIFICATION.md` for the actual tool output.
- JUnit suite: `CalculatorEngineTest`, `EmotionAnalyzerTest`, `AgentRouterTest`,
  `TextProcessingTest`.

## 9. Troubleshooting

- **No voice replies** — install/enable a TTS engine (Google Speech Services) and set its
  language; the app works with text even without it.
- **No voice input** — a recognition service (Google app) must be present; typing always works.
- **HTTP 429** — free-tier quota; wait or change key. **401/403** — check the key.
- **Model 404** — the fallback chain engages automatically; you can also pin any model in
  Settings → Primary model.
