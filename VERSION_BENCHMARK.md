# Version Benchmark

Repeat this checklist after each major version to compare quality over time.

## Benchmark setup (keep stable)

- Use the same test deck set whenever possible.
- Include:
  - at least one math deck,
  - at least one non-math deck,
  - at least two similarly named decks (for ambiguity testing).
- Keep ASR conditions similar (same environment/device) when possible.

## Fixed utterance suite

Run these utterances in order:

1. "What decks are left?"
2. "Choose deck biology."
3. "Give me the next deck that's not math."
4. "Give me something similar."
5. "Yes, do it." (immediately after a suggestion)
6. Ambiguous command (example: "choose the first one")
7. Repeated-miss scenario (same card, miss one aspect repeatedly, 3 attempts)
8. Toggle-off control scenario (same card type, adaptive history disabled)

If you add more utterances, keep the original 6 unchanged for comparability.

## Scoring rubric (per utterance)

- **Expected behavior:** what the app should do
- **Actual behavior:** what happened
- **Pass/Fail**
- **Trust/Naturalness rating (1-5)**
- **Notes:** errors, awkward phrasing, delays, etc.

## Metrics to compute per version

- `switchSuccessRate` = correct deck switches / switch attempts
- `suggestionAcceptanceRate` = accepted suggestions / suggestions offered
- `clarificationRate` = clarification turns / relevant deck intents
- `misrouteCount` = number of wrong deck switches
- `medianResponseSeconds` = median voice response latency (rough manual timing is fine)
- `subjectiveVoiceUX` = overall 1-5 score
- `adaptiveHintUsefulness` = 1-5 rating for whether history-aware hints help

## Version results template

Copy this block for each major version.

---

### Version: <tag-or-date>

#### Environment

- Device:
- Language:
- Deck set snapshot:
- Notes:

#### Utterance results

1) "What decks are left?"
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

2) "Choose deck biology."
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

3) "Give me the next deck that's not math."
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

4) "Give me something similar."
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

5) "Yes, do it."
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

6) Ambiguous command
- Prompt used:
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

7) Repeated-miss scenario (adaptive ON)
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

8) Toggle-off control scenario (adaptive OFF)
- Expected:
- Actual:
- Pass/Fail:
- Trust/Naturalness (1-5):
- Notes:

#### Metrics

- switchSuccessRate:
- suggestionAcceptanceRate:
- clarificationRate:
- misrouteCount:
- medianResponseSeconds:
- subjectiveVoiceUX:
- adaptiveHintUsefulness:

#### Summary

- Biggest improvement since last version:
- Biggest failure mode:
- Priority fix before next release:

