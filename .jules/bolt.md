# Bolt's Performance Journal

## 2026-11-04 - Caching computed location views on immutable resort models
**Learning:** `SkiResort` instances are parsed once from JSON but queried frequently by UI components (such as station picker dropdowns) and route tests. Methods like `getSelectableLocations()` perform multi-step filtering, string prefix matching, and lexicographical sorting, creating new list allocations and sorting overhead on every call.
**Action:** Use `@Transient private var` caching within data models parsed by Gson for any secondary filtered or sorted node collections to prevent repeated garbage collection pressure and CPU overhead without interfering with JSON serialization.
