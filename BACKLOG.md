# AnkiVoice backlog

Ideas and planned work. Done items are kept briefly for history.

## Done (recent)

- **On-device neural TTS**: Sherpa-ONNX + Piper (EN `amy-low`, DE `thorsten-medium`), settings backend + Test voice, study integration (`Local Piper (exp)` vs system engine).
- Explicit **deck id** for scheduling (`limit=?,deckID=?`) so the queue matches the deck you care about (fixes empty queue when another deck was “active” internally).
- **Deck list** in Settings (hierarchical names with `::`) + “same as AnkiDroid home selection”.
- **Skip tags**: comma-separated note tags; due cards with any of those tags are **buried** and skipped for voice.
- **Study screen**: “Refresh deck info” + line showing deck name and due counts (debug / confidence).
- **Claude (Anthropic)** and OpenAI-compatible LLM providers.

## Next up (suggested order)

1. **Deck list refresh** when returning from AnkiDroid without restarting the app (e.g. `Lifecycle` observer or pull-to-refresh on Settings).
2. **Safer skip-tag behavior**: option to “skip without burying” (e.g. only re-queue end of session) — needs product decision.
3. **Strip / simplify HTML** further for TTS (code blocks, images): detect heavy cards and auto-skip or shorten.
4. **Offline / API failure**: queue grading when the network returns; clearer error toasts.
5. **Android Auto / driving mode**: explicit “parked only” flow and larger touch targets (policy + UX).
6. **Optional cloud STT** (Whisper, etc.) behind BYOK for accuracy vs. device speech.
7. **Headphone / media-button controls**: play/pause with single press, optional next/previous gesture mapping for hands-free session control.
8. **Tag-driven AI TTS preprocessor for code/math cards**: if a note has a configured tag, run one extra LLM step to verbalize symbols/code without changing meaning; optional caching into custom note/card metadata to avoid repeated API calls.

## AI pipeline framework (tool-first, in-app)

- Replace free-form JSON grading with provider tool/function calling, keeping orchestration fully in-app (no extra microservice).
- Runtime input to the model should include: card front, card back, and learner STT transcript as structured fields.
- The assistant may answer concisely in text between tool calls when needed, but is incentivized to minimize chatter.
- Every finalized card interaction should end via an explicit rating tool call (`1..4`) instead of text-only grading.
- Add framework hooks for future tools: reread card, end conversation, switch deck, and "how many left".
- Add framework support for code/math readability preprocessing:
  - Optional AI transform from card content to TTS-friendly phrasing.
  - Cache transformed text in card/note metadata when feasible to reduce repeat cost.
  - Add strict "speech-safe output" guardrails: avoid symbols/LaTeX/code notation in tutor speech and rewrite to natural spoken language.
  - Evaluate whether prompt engineering is enough; if quality remains unstable across models, consider a small fine-tuned rewrite model for speech-safe math/code verbalization.
- Add support for "do not read this card" semantics (e.g. image/screenshot-heavy backs), configurable via tool and/or metadata policy.
- Keep advanced capabilities in backlog and ship incrementally, but design the tool router/state machine now so these features plug in without architecture changes.

## Parking lot

- FSRS / scheduling transparency (“why this interval?”).
- Per-deck language for TTS/STT.
- Wear OS / shortcut tiles.
- Import decks without AnkiDroid (out of scope for the current integration).

Add new bullets as you like; keep this file as the single lightweight backlog unless you move to Linear/Jira.
