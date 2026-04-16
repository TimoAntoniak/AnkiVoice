# AnkiVoice backlog

Ideas and planned work. Done items are kept briefly for history.

## Done (recent)

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
7. **Headphone / media-button controls**: play/pause with single press, optional next/previous gesture mapping for hands-free session control.
8. **Tag-driven AI TTS preprocessor for code/math cards**: if a note has a configured tag, run one extra LLM step to verbalize symbols/code without changing meaning; optional caching into custom note/card metadata to avoid repeated API calls.

## Parking lot

- FSRS / scheduling transparency (“why this interval?”).
- Per-deck language for TTS/STT.
- Wear OS / shortcut tiles.
- Import decks without AnkiDroid (out of scope for the current integration).

Add new bullets as you like; keep this file as the single lightweight backlog unless you move to Linear/Jira.
