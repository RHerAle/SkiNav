# Sentinel Security Journal

## 2026-05-18 - Prevent NaN Propagation and Denial of Service in Haversine A* Heuristic
**Vulnerability:** In trigonometric distance formulations using the Haversine formula, calculation of the squared half-chord parameter $a$ can slightly exceed 1.0 due to IEEE 754 floating-point rounding when evaluating nearly antipodal or extreme coordinates. Evaluating `sqrt(1.0 - a)` when $a > 1.0$ evaluates to `NaN`. In A* pathfinding, injecting `NaN` priorities into Java's `PriorityQueue` breaks comparison invariants and corrupts heap ordering. Furthermore, downstream route metrics calculation attempts `Double.roundToInt()`, which throws an unhandled `IllegalArgumentException: Cannot round NaN value`, leading to service denial.
**Learning:** Pure algorithmic and geometric functions operating on geographic coordinates must explicitly guard mathematical domain bounds `[0.0, 1.0]` against numerical precision anomalies before passing them to square roots or inverse trigonometric functions.
**Prevention:** Clamp intermediate calculation terms using `.coerceIn(0.0, 1.0)` prior to computing `sqrt(1 - a)` in all geodesic and heuristic distance algorithms.
