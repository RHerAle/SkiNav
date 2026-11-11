## 2026-03-05 - NaN Priority Queue Corruption via Haversine Computation
**Vulnerability:** Floating-point precision inaccuracies during Haversine distance computations can produce intermediate values slightly exceeding 1.0 (e.g. for antipodal points or numerical rounding), causing `sqrt(1.0 - a)` to return `Double.NaN`. When passed as heuristic costs into an A* PriorityQueue, `NaN` values break comparator transitivity and heap invariants, leading to nondeterministic routing failures or infinite loops.
**Learning:** In geometric heuristics feeding search priority queues, numerical edge cases must be strictly bounded.
**Prevention:** Clamp the intermediate Haversine argument `a` to `[0.0, 1.0]` using `coerceIn(0.0, 1.0)` before computing `sqrt(1.0 - a)`.
