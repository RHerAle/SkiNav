# SkiNav

The routing and pathfinding engine for ski resort navigation: topological graph
traversal, A* shortest path search, difficulty filtering, lift schedules, and
ski bus transit connectors across mountain resorts. Implemented in pure Kotlin
with zero platform or UI dependencies.

| File | What it computes |
|---|---|
| `src/ResortModels.kt` | Data structures for resorts, nodes (stations, summits, intersections), directed edges (slopes, chairlifts, gondolas, cable cars, funiculars, walks, ski buses), route preferences and alternative route summaries |
| `src/ResortPathfinder.kt` | A* pathfinding algorithm over topological ski graphs, using a binary min-heap priority queue, Haversine spatial heuristics, difficulty pruning, and penalty-based routing |

`data/*.json` contains the topological graphs and catalogs for 9 ski resorts,
compiled from OpenStreetMap piste and aerialway data.

## Architecture & Algorithm

### 1. Graph Formulation
A ski resort is modelled as a directed multigraph $G = (V, E)$:
- **Vertices ($V$):** Mountain summits, valley bases, lift terminals, and piste intersections.
- **Edges ($E$):** Piste runs (slopes with difficulties `green`, `blue`, `red`, `black`), lifts (`chairlift`, `gondola`, `cable_car`, `drag_lift`, `mixed_lift`, `funicular`), walking connectors (`walk`), and shuttle routes (`bus`).
- **Bidirectionality:** Walking connectors, ski buses, and enclosed aerial lifts (gondolas, cable cars, funiculars) allow bi-directional movement unless explicitly declared with `bidirectional = false`. Downhill ski slopes are strictly one-way down the mountain.

### 2. A* Pathfinding & Mathematical Cost Model
The optimal path from start node $s$ to destination $d$ minimizes the evaluation function:

$$f(n) = g(n) + h(n)$$

where:
- **$g(n)$** is the exact accumulated cost from $s$ to $n$.
- **$h(n)$** is the admissible Haversine heuristic estimating remaining time:
  $$h(n) = \frac{\text{haversine}(n, d)}{v_{\text{ref}}} \times 60$$
  with reference speed $v_{\text{ref}} = 20\text{ km/h}$.
- **Edge Cost Formulation ($c(e)$):**
  - **Fastest Route:** Minimizes pure travel time:
    $$c(e) = \text{duration}(e)$$
  - **Easiest / Scenic Route:** Applies non-linear safety penalties to steeper runs and transitions:
    - `green` slope: $+0\text{ min}$
    - `blue` slope: $+2\text{ min}$
    - `red` slope: $+7\text{ min}$
    - `black` slope: $+18\text{ min}$
    - `bus`: $+6\text{ min}$ (prioritizes remaining on skis over road transit)
    - `walk`: $+2\text{ min}$
- **Difficulty Filtering (`maxDifficulty`):** Slopes exceeding the chosen technical threshold (e.g. `blue` pruning all `red` and `black` slopes) are pruned prior to search expansion.
- **Alternative Routes (`findRouteAlternatives`):** Evaluates fastest, easiest/scenic, and ski-only routes (excluding ski buses).

## Topological Graphs (`data/*.json`)

The `data/` directory contains complete topological networks for 9 alpine and pyrenean resorts:

| Resort ID | Name | Region |
|---|---|---|
| `madonna` | Campiglio Dolomiti di Brenta | Trentino, Italy 🇮🇹 |
| `baqueira` | Baqueira Beret | Pyrenees, Spain 🇪🇸 |
| `sierranevada` | Sierra Nevada | Andalusia, Spain 🇪🇸 |
| `formigal` | Formigal-Panticosa | Pyrenees, Spain 🇪🇸 |
| `cerler` | Cerler | Pyrenees, Spain 🇪🇸 |
| `candanchu` | Candanchú | Pyrenees, Spain 🇪🇸 |
| `alpe` | Alpe d'Huez | French Alps, France 🇫🇷 |
| `les3vallees` | Les 3 Vallées | French Alps, France 🇫🇷 |
| `alpine` | Alpine Valley | Demonstration Network 🏔️ |

All resort networks are catalogued in `data/index.json`.

## Data Licence & Attribution

© OpenStreetMap contributors. The topological network data in `data/*.json` is
derived from OpenStreetMap geographic and aerialway databases and is published
under the Open Database License 1.0 ([`LICENSE-DATA`](LICENSE-DATA)).

## Code Licence

© 2026 SkiNav contributors. Source code is licensed under the GNU Affero General
Public License, version 3 ([`LICENSE`](LICENSE)).

A commercial or closed-source license is available from the author upon request.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the contribution agreement in [`FLA.md`](FLA.md).
