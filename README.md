# Relay — Native Android Public SMS Inbox

**Relay** is a native Kotlin + Jetpack Compose Android application for browsing public virtual SMS inboxes, polling live public SMS providers, extracting one-tap verification codes, and testing carrier webhook payloads.

## Core Capabilities

- **Live Public SMS Polling**: Concurrently polls Anosim (`+61 485 841 378`) and receive-smss.live (`+61 468 279 945`, `+61 485 922 587`, `+61 483 966 924`) with single-flight throttling, HTML/Unicode entity decoding, 2-minute deduplication, and automatic 48-hour message retention pruning.
- **Local-First Persistence**: Stores your number pool (up to 40 lines) and cached SMS messages locally in Room SQLite with reactive `Flow` updates and Jetpack DataStore preferences—no sign-in required.
- **One-Tap OTP Extraction**: Automatically detects verification codes (`184920`, `G-184223`, `629-410`, etc.) and renders instant copy chips with haptic feedback.
- **Live Sync Telemetry**: Top-bar status pill and diagnostics sheet reporting per-provider health, latency, new message counts, and manual pull-to-refresh.
- **Circuit Amber & Sleek Themes**: Defaults to a dark carbon PCB circuitry theme with precision amber signals, plus switchable **Obsidian Mono**, **Stealth Emerald**, **Arctic Slate**, and **System Dynamic** Material You themes.
- **Connect & Payload Testing**: Register new E.164 lines, test simulated SMS presets (Telegram, Google, Discord), and test Twilio form-encoded or Telnyx JSON webhook payloads locally with 20/min burst protection.
