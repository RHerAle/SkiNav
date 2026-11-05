# Sentinel Security Journal

## 2026-03-31 - Route Cost Heap Corruption and Time Boundary Bypass
**Vulnerability:** Unsanitized route filter parameters and floating-point edge durations (e.g. `NaN`, `Infinity`, or negative values) could lead to undefined PriorityQueue comparisons during A* search, potentially resulting in heap corruption or infinite loops (algorithmic DoS). In addition, unbounded departure times (`departureTimeMinutes`) bypassed daily operating schedules.
**Learning:** In A* pathfinding engines using binary min-heaps, IEEE 754 `NaN` costs violate the strict weak ordering required by `Comparable<HeapItem>`, which can silently corrupt heap invariants or cause traversal starvation.
**Prevention:** Always validate and clamp user-controlled scheduling inputs to allowable day bounds (`[0, 1439]`), and enforce strict finite and positive bounds (`edge.duration > 0.0 && edge.duration.isFinite()`) when calculating graph edge costs.
