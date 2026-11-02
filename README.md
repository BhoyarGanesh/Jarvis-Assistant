# Jarvis: personal voice assistant for Android

Say **"Jarvis"**, wait for the chime, then **"call King"** or **"message Junior on WhatsApp hi"**.
Jarvis understands you with an LLM (Gemini by default, Claude optional) and does it: it calls, messages on
WhatsApp or SMS, sets alarms and timers, opens apps, searches, plays music, or just answers. Then it
speaks a short confirmation.

```
 Phone (Android app)                                   PC (Node.js backend)
 ┌──────────────────────────────────────────┐          ┌───────────────────────────────┐
 │ Vosk wake word (offline) ─► chime         │          │ POST /interpret               │
 │ SpeechRecognizer (STT)  ─► transcript ────┼── HTTP ─►│  auth + rate limit + validate │
 │                                           │  (LAN)   │  LLMProvider: Gemini | Claude │
 │ Contacts fuzzy match, 3 s cancel countdown│◄─────────┤  → {tool, args}               │
 │ Intents / AccessibilityService / TTS      │          └───────────────────────────────┘
 │ Room history, Compose UI                  │
 └──────────────────────────────────────────┘
```

The app never talks to an LLM directly. API keys live only in `backend/.env` on your PC.

## Repository layout

| Path | What |
|---|---|
| `app/` | Android app (a self-contained Gradle project: `app/gradlew`) |
| `app/src/main/java/com/jarvis/assistant/domain` | Pure Kotlin: orchestrator state machine, contact/app matching, command parsing, wake word logic |
| `app/src/main/java/com/jarvis/assistant/{data,platform,voice,service,ui}` | Room/DataStore/backend client, Android executors, Vosk/STT/TTS, services, Compose UI |
| `backend/` | Node.js + Express backend (`/interpret`, `/health`) |
| `scripts/download_vosk_model.{ps1,sh}` | Downloads the wake word model into the app's assets |

## Prerequisites

- Windows, macOS or Linux PC with **Node.js 20+** and **Git**
- **Android Studio** (2026.2 or newer), including its bundled JDK and an Android SDK with API 37
- An Android phone (Android 8.0 / API 26 or newer) with USB debugging, on the **same Wi-Fi** as the PC
- A free **Gemini API key** from Google AI Studio

## 1. Run the backend

```powershell
cd backend
npm install
```

### Put your free Gemini key into `backend/.env`

1. Open https://aistudio.google.com/apikey, sign in, and click **Create API key**.
2. Open `backend/.env`. It already exists, with placeholders only, and is gitignored.
3. Replace `GEMINI_API_KEY=PASTE_KEY_HERE` with your key.
4. Replace `APP_SHARED_SECRET=PASTE_SECRET_HERE` with a long random string. One way to make one:
   `node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"`
   You'll type the same value into the app.

```ini
LLM_PROVIDER=gemini
GEMINI_API_KEY=AIza...your key...
GEMINI_MODEL=gemini-3.5-flash-lite
GEMINI_FALLBACK_MODELS=gemini-3.8-flash
APP_SHARED_SECRET=your-long-random-secret
```

Optional: to use Claude instead, set `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=...` and optionally
`CLAUDE_MODEL` (default `claude-opus-5-5`). To try everything **without any key**, use `LLM_PROVIDER=mock`,
a keyword-based parser for simple commands.

```powershell
npm start
```

On startup the backend prints `Backend URL candidates for the app: http://192.168.x.y:8787`.

### Find your PC's LAN IP

- Windows: run `ipconfig` and use the **IPv4 Address** of your Wi-Fi adapter (e.g. `192.168.1.20`).
- macOS: run `ipconfig getifaddr en0`. Linux: run `hostname -I`.

Your backend URL is `http://<that IP>:8787`. To check it from the phone, open that URL plus `/health` in
the phone's browser. If it doesn't load, allow Node.js through **Windows Defender Firewall** on
**Private networks** (Windows asks the first time you run `npm start`). Also make sure the Wi-Fi is set
to *Private*, and that phone and PC are on the same network, not a guest network.

### Test it with curl

```bash
# Git Bash / macOS / Linux
SECRET=your-long-random-secret ./scripts/test_interpret.sh
```
```powershell
# PowerShell
.\scripts\test_interpret.ps1 -Secret your-long-random-secret
```

These scripts send one phrase for every tool (call, WhatsApp, SMS, alarm, timer, app, search, music,
answer, an ambiguous request), plus auth and validation checks. Automated tests, which use mocked
Gemini and Claude clients so no key is needed: `npm test`.

### API

`POST /interpret` with header `X-Jarvis-Secret: <APP_SHARED_SECRET>`:

```json
{ "text": "jarvis message junior on whatsapp hi", "contacts": ["Junior", "King"], "now": "2026-11-02 18:30 Mon", "locale": "en-US" }
```
→ `200 {"tool":"send_whatsapp","args":{"contact_name":"Junior","message":"hi"},"provider":"gemini","model":"gemini-3.5-flash-lite","latencyMs":612}`

Tools: `call_contact(name)`, `send_whatsapp(contact_name, message)`, `send_sms(contact_name, message)`,
`set_alarm(hour, minute, label)`, `set_timer(seconds, label)`, `open_app(app_name)`, `web_search(query)`,
`play_music(query)`, `answer(text)`.

Errors are `{"error": "...", "message": "<friendly, speakable text>"}`:

| Status | Meaning |
|---|---|
| 400 | Invalid input |
| 401 | Wrong secret |
| 429 | Rate limited, by the backend (30/min by default) or by the provider's free tier. The message is *"I'm getting too many requests right now. Please try again in a minute."* |
| 502 | The provider failed |
| 503 | No API key configured |

`GET /health` is public and reports the provider, the model, and whether a key is configured.

### Deploying later

The backend is a plain Express app. To use Jarvis away from home Wi-Fi, deploy `backend/` to any Node
host (Render, Railway, Fly.io, a VPS or Cloud Run). Set the same env vars there, and use the
`https://` URL in the app. Keep the shared secret long, since it is the only auth.

## 2. Build and install the app

```powershell
# One-time: download the wake word model (~40 MB) into app/src/main/assets/model-en-us
.\scripts\download_vosk_model.ps1        # or: ./scripts/download_vosk_model.sh

cd app
.\gradlew.bat assembleDebug              # needs JAVA_HOME = Android Studio's JDK, see below
adb install -r build\outputs\apk\debug\Jarvis-debug.apk
```

- `app/local.properties` (gitignored) must point to your SDK:
  `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` (note the escaped colon). Android Studio writes it automatically when you
  open the `app/` folder.
- From a terminal, use Android Studio's bundled JDK:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` (PowerShell).
- Alternatively, open the `app/` folder in Android Studio and press **Run**.
- No USB cable? Copy `Jarvis-debug.apk` (about 130 MB; most of it is the wake word model and unshrunk debug code) to the phone and open it. You'll need to allow *Install unknown apps*
  for your file manager.

Unit tests: `.\gradlew.bat testDebugUnitTest`. Lint: `.\gradlew.bat lintDebug`.

## 3. First run: permissions and accessibility

The onboarding walks through each step, and each one can be skipped and enabled later in Settings:

1. **Microphone**: required for the wake word and commands.
2. **Contacts, Phone, SMS**: for "call King" and "text Mom". If you deny one, Jarvis says what is
   missing instead of failing silently.
3. **Notifications** (Android 13+): for the persistent "Listening for Jarvis" notification.
4. **WhatsApp auto-send (Accessibility)**: Settings → Accessibility → *Jarvis WhatsApp auto-send* → On.
   On Android 13+ sideloaded apps get *Restricted setting*. Fix it with App info → ⋮ → **Allow restricted
   settings**, then enable the service. Without it, Jarvis still opens the chat with your text typed
   in, and asks you to tap Send.
5. **Battery optimization**: allow Jarvis to run in the background. On Xiaomi, Samsung, OnePlus, Oppo,
   Vivo and Huawei also enable Autostart or "never sleeping". The exact paths are under *Settings →
   If Jarvis stops listening* and on https://dontkillmyapp.com.
6. **Backend**: enter `http://<PC IP>:8787` and the shared secret, then tap **Test connection**.

Optional, under Settings: **Show over other apps**. This lets Jarvis bring its screen forward when the
wake word fires while another app is open.

## Using Jarvis

- Say **"Jarvis"**, wait for the chime, then speak your command. You can also tap the orb, the
  **Tap to talk** button, the **home-screen widget**, the **Quick Settings tile** (*Ask Jarvis*), or **Talk**
  in the notification.
- Calls and messages show a **3-second countdown** with **Cancel** and **Do it now** buttons. You can turn
  it off or change its length in Settings.
- If several contacts match ("King Kumar" and "King Singh"), Jarvis asks which one. Answer by name,
  say "the second one", or tap.
- Every action is logged in **History** (clock icon). You can clear it there.
- Settings has: wake word on/off, sensitivity and re-trigger delay, backend URL and secret, spoken replies,
  countdown, language (English, English (India), Hindi), start on boot, and sharing contact names.

## Known limitations

- **Wake word, then pause.** Vosk detects "Jarvis", then the platform recognizer starts after the chime.
  Words spoken before the chime are not captured. A single breath like "Jarvis call King" usually needs
  the "call King" part repeated after the chime.
- **Wake word quality.** It uses a small general English model restricted to `["jarvis", "[unk]"]`, so it is
  not a trained hotword model. Expect some false triggers in noisy rooms or with TV in the background,
  and tune Sensitivity and Re-trigger delay. The engine sits behind `WakeWordEngine`, so it can be
  replaced (e.g. by Porcupine or openWakeWord) by changing one Hilt binding in `di/AppModule.kt`.
- **Wake word language.** The wake word is English-only. Hindi applies to command recognition and
  spoken replies; on-device Hindi recognition needs the language pack, otherwise Jarvis falls back to
  the online recognizer.
- **Background starts.** Android limits what background apps may open. Calls use `TelecomManager` and
  work from the background. Opening WhatsApp, alarms or apps from a wake word while another app is in
  front works once the Accessibility service or *Show over other apps* is enabled, and may otherwise be
  blocked silently.
- **Start on boot.** On Android 14+ a microphone service can't start at boot, so Jarvis posts a "tap to
  turn on" notification instead.
- **WhatsApp auto-send.** It depends on WhatsApp's UI: it matches the view id `send`, then the
  content description "Send" and some translations, with a 10-second timeout. A WhatsApp redesign can
  break it; Jarvis then reports that you need to tap Send.
- **SMS.** Sends directly with the SMS permission, otherwise opens your messaging app prefilled.
  Delivery reports are not tracked.
- **Free-tier limits.** Gemini's free tier has per-minute and per-day limits. When they are hit, Jarvis
  says so and asks you to retry.
- **Security.** Traffic to a LAN backend is plain HTTP (cleartext is allowed for this). The shared secret
  is stored in app-private DataStore, not hardware-encrypted. Use HTTPS when you deploy publicly.

## Decisions

- **Gradle project lives in `app/`.** The repo layout asks for `/app` and `/backend`, so `app/` is a
  self-contained single-module Gradle project with its own wrapper and `local.properties`.
- **Toolchain.** Gradle 9.8.2 (wrapper), AGP 9.5 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.13,
  Hilt 2.60.1, Compose BOM 2026.09.00, Room 2.8.5. `compileSdk` and `targetSdk` are 37 (the installed,
  latest stable SDK) and `minSdk` is 26. The build runs on Android Studio's bundled JDK 25 and targets
  Java 17 bytecode.
- **Default Gemini model `gemini-3.5-flash-lite`, with `gemini-3.8-flash` as fallback.** Both are free-tier
  models on Google's pricing page (checked 2026-11-02). In live testing, Flash-Lite answered every tool
  correctly in 0.7–2 s, while `gemini-3.8-flash` was returning 503 "high demand". `gemini-2.5-flash` is
  closed to new users. The SDK's built-in retries can back off for up to a minute, so they are turned off.
  Each request instead has a 12 s timeout, and a 429/5xx or timeout moves on to the next model in
  `GEMINI_FALLBACK_MODELS`. Gemini is called with `FunctionCallingConfigMode.ANY`, so it always returns
  exactly one tool call; `answer` covers small talk.
- **Default Claude model `claude-opus-5-5`.** Current models reject forced `tool_choice`, so the backend
  uses `tool_choice: auto` with strict tool schemas and a prompt that requires one tool call. Any plain-text
  reply becomes `answer()`, and a refusal becomes a polite `answer()`. The server-side refusal fallback
  (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`) is enabled for models that support it.
  Effort defaults to `low` (`CLAUDE_EFFORT`) for snappy voice replies.
- **A third provider, `mock`.** A keyword parser that lets the app, curl scripts and demos run with no
  API key. Provider tests inject fake SDK clients, so the Anthropic path is covered without a key.
- **Validation in two places.** The backend validates LLM output with zod, and the app re-validates in
  `CommandParser`. Anything malformed becomes a short "say that again" answer, never an action.
- **Contacts stay on the phone.** The LLM only sees what you said. Contact resolution (fuzzy, nicknames,
  "which one?") happens on-device, so the LLM can't invent numbers. Sharing contact *names* (never numbers)
  with the backend is an opt-in setting, off by default.
- **Orchestration is pure Kotlin.** The whole voice flow (`AssistantOrchestrator`) depends only on
  interfaces, and is unit-tested with fakes: countdown, cancel, disambiguation, permission errors and
  backend errors.
- **Mic hand-off.** Vosk's `SpeechService` is shut down (not just paused) before `SpeechRecognizer`
  starts, so the microphone is actually free, and it is recreated afterwards. On-device recognition is
  preferred on Android 12+, falling back to the regular recognizer if the language pack is missing.
- **Wake word sensitivity.** Sensitivity 0–1 maps to a minimum Vosk word confidence of 0.95–0.45. Above
  0.8, partial results also trigger, which is faster but noisier. A debounce window (default 2 s) blocks
  repeat triggers.
- **Safety.** The countdown applies only to actions that reach other people (calls, WhatsApp, SMS). The
  accessibility service only acts while the app has an "armed" send request (10 s), and only in WhatsApp.
- **UI.** Material 3, dark only, with bundled OFL fonts (Space Grotesk for display, Inter for text).
  Glass effects are drawn with translucent gradients and hairline borders over a blurred aurora
  background (real blur on Android 12+).
- **Launchers.** It has both a Quick Settings tile and a classic RemoteViews widget, so no Glance dependency.
- **Release build.** Signed with the debug key for easy sideloading. Configure your own keystore before
  sharing it.
