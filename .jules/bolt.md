## 2026-03-29 - Memoize Selectable Locations Lookup in SkiResort
**Learning:** `SkiResort.getSelectableLocations()` filters and sorts node lists on every call. For UI dropdowns and search auto-completes invoked frequently during navigation, memoizing this result via `@Transient private var selectableLocationsCache` avoids repeated allocations and O(N log N) sorting.
**Action:** Always check data model lookup methods called by UI/navigation for lazy memoization patterns matching `nodeMapCache` and `outgoingCache`.
