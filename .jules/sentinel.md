## 2026-10-30 - Floating-Point Haversine NaN Priority Queue Corruption
**Vulnerability:** Double precision arithmetic in Haversine distance calculation (`sLat*sLat + cos*cos*sLng*sLng`) could produce values slightly greater than `1.0` near antipodal points or boundary coordinates, causing `sqrt(1.0 - a)` to yield `NaN`.
**Learning:** `NaN` propagation in A* heuristic calculations causes `PriorityQueue` comparisons (`Double.NaN.compareTo(...)`) to violate heap invariants, leading to infinite search loops or corrupted path results.
**Prevention:** Always clamp the intermediate trigonometric scalar `a` using `.coerceIn(0.0, 1.0)` before computing `sqrt(1.0 - a)` in Haversine geometric functions.
