## 2026-10-27 - Fail-Secure Slope Difficulty and Filter Input Handling
**Vulnerability:** Unrecognized or missing slope difficulty strings defaulted to index 1 (blue / intermediate) in `ResortPathfinder.difficultyIndex` and `calculateEdgeCost`, allowing potentially dangerous unrated slopes through beginner filters. Additionally, unrecognized `maxDifficulty` filter parameters were treated inconsistently.
**Learning:** Default fallback values in safety-critical routing filters must always default to the most restrictive tier (fail-secure principle) rather than an arbitrary intermediate value.
**Prevention:** Always validate and normalize enum-like inputs with explicit fallbacks to safe states, ensuring unrated items are treated with maximum caution.
