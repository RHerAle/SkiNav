# SkiNav

The routing and pathfinding engine for ski resort navigation: topological graph
traversal, A* shortest path search, difficulty filtering, lift schedules, and
ski bus transit connectors across mountain resorts. Implemented in pure Kotlin
with zero platform or UI dependencies.

| File | What it computes |
|---|---|
| `src/ResortModels.kt` | Data structures for resorts, nodes (stations, summits, intersections), directed edges (slopes, chairlifts, gondolas, cable cars, funiculars, walks, ski buses), route preferences and alternative route summaries |
| `src/ResortPathfinder.kt` | A* pathfinding algorithm over topological ski graphs, using a binary min-heap priority queue, Haversine spatial heuristics, difficulty pruning, and penalty-based routing |

`data/*.json` contains the topological graphs and catalogs for 82 ski resorts
(31 in Spain, 48 in Italy, 2 in France, plus 1 synthetic demonstration network),
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

The `data/` directory contains complete topological networks for 82 ski resorts (31 in Spain 🇪🇸, 48 in Italy 🇮🇹, 2 in France 🇫🇷, and 1 synthetic test network 🏔️):

| Resort ID | Name | Country | Slopes | Lifts | Elevation (Drop) |
|---|---|---|---|---|---|
| `madonna` | Campiglio Dolomiti di Brenta | Italy 🇮🇹 | 88.5 km | 39 | 850m - 2504m (Δ 1654m) |
| `baqueira` | Baqueira Beret | Spain 🇪🇸 | 152.3 km | 33 | 1500m - 2610m (Δ 1110m) |
| `sierranevada` | Sierra Nevada | Spain 🇪🇸 | 104.4 km | 20 | 2100m - 3300m (Δ 1200m) |
| `formigal` | Formigal-Panticosa | Spain 🇪🇸 | 128.2 km | 23 | 1500m - 2250m (Δ 750m) |
| `cerler` | Cerler | Spain 🇪🇸 | 126.5 km | 19 | 1500m - 2630m (Δ 1130m) |
| `candanchu` | Candanchú | Spain 🇪🇸 | 44.9 km | 37 | 1530m - 2400m (Δ 870m) |
| `alpe` | Alpe d'Huez | France 🇫🇷 | 182.3 km | 61 | 1100m - 3330m (Δ 2230m) |
| `les3vallees` | Les 3 Vallées | France 🇫🇷 | 638.2 km | 102 | 1300m - 3230m (Δ 1930m) |
| `alpine` | Alpine Valley Resort | Demo 🏔️ | 60 km | 15 | 1550m - 2400m (Δ 850m) |
| `altocampoo` | Alto Campoo | Spain 🇪🇸 | 19.7 km | 11 | 1650m - 2125m (Δ 475m) |
| `javalambre` | Aramón Javalambre | Spain 🇪🇸 | 8.7 km | 9 | 1650m - 2000m (Δ 350m) |
| `valdelinares` | Aramón Valdelinares | Spain 🇪🇸 | 9.2 km | 12 | 1700m - 2024m (Δ 324m) |
| `astun` | Astún | Spain 🇪🇸 | 30.9 km | 17 | 1700m - 2300m (Δ 600m) |
| `boitaull` | Boí Taüll | Spain 🇪🇸 | 33.9 km | 9 | 2035m - 2751m (Δ 716m) |
| `manzaneda` | Cabeza de Manzaneda | Spain 🇪🇸 | 12.2 km | 6 | 1500m - 1778m (Δ 278m) |
| `espot` | Espot Esquí | Spain 🇪🇸 | 19.4 km | 6 | 1500m - 2500m (Δ 1000m) |
| `fuentesdeinvierno` | Fuentes de Invierno | Spain 🇪🇸 | 10.2 km | 5 | 1487m - 1950m (Δ 463m) |
| `lamolina` | La Molina | Spain 🇪🇸 | 91.4 km | 33 | 1700m - 2445m (Δ 745m) |
| `lapinilla` | La Pinilla | Spain 🇪🇸 | 11.2 km | 13 | 1500m - 2050m (Δ 550m) |
| `lunada` | Lunada | Spain 🇪🇸 | 6.1 km | 5 | 1280m - 1445m (Δ 165m) |
| `masella` | Masella | Spain 🇪🇸 | 68.4 km | 23 | 1600m - 2535m (Δ 935m) |
| `panticosa` | Panticosa | Spain 🇪🇸 | 18.3 km | 15 | 1145m - 2220m (Δ 1075m) |
| `portaine` | Port Ainé | Spain 🇪🇸 | 20.5 km | 8 | 1650m - 2440m (Δ 790m) |
| `portdelcomte` | Port del Comte | Spain 🇪🇸 | 30.5 km | 19 | 1700m - 2400m (Δ 700m) |
| `navacerrada` | Puerto de Navacerrada | Spain 🇪🇸 | 2.9 km | 6 | 1700m - 2177m (Δ 477m) |
| `santaines` | Punto de Nieve Santa Inés | Spain 🇪🇸 | 1.0 km | 2 | 1753m - 1832m (Δ 79m) |
| `sanisidro` | San Isidro | Spain 🇪🇸 | 23.5 km | 14 | 1500m - 2100m (Δ 600m) |
| `lacovatilla` | Sierra de Béjar - La Covatilla | Spain 🇪🇸 | 14.2 km | 4 | 1990m - 2368m (Δ 378m) |
| `tavascan` | Tavascan | Spain 🇪🇸 | 3.8 km | 3 | 1750m - 2250m (Δ 500m) |
| `valdesqui` | Valdesquí | Spain 🇪🇸 | 36.0 km | 15 | 1860m - 2280m (Δ 420m) |
| `valdezcaray` | Valdezcaray | Spain 🇪🇸 | 15.7 km | 9 | 1530m - 2125m (Δ 595m) |
| `valgrande` | Valgrande-Pajares | Spain 🇪🇸 | 15.3 km | 6 | 1450m - 1890m (Δ 440m) |
| `valldenuria` | Vall de Núria | Spain 🇪🇸 | 5.9 km | 7 | 1964m - 2252m (Δ 288m) |
| `leitariegos` | Valle Laciana - Leitariegos | Spain 🇪🇸 | 6.1 km | 6 | 1513m - 1800m (Δ 287m) |
| `vallter2000` | Vallter 2000 | Spain 🇪🇸 | 13.1 km | 10 | 1910m - 2535m (Δ 625m) |
| `trecime` | 3 Zinnen Dolomites (Sexten) | Italy 🇮🇹 | 129.9 km | 23 | 1130m - 2200m (Δ 1070m) |
| `abetone` | Abetone - Val di Luce | Italy 🇮🇹 | 49.7 km | 18 | 1240m - 1892m (Δ 652m) |
| `folgaria` | Alpe Cimbra (Folgaria - Lavarone) | Italy 🇮🇹 | 46.9 km | 31 | 1166m - 1850m (Δ 684m) |
| `alpelusia` | Alpe Lusia - San Pellegrino | Italy 🇮🇹 | 68.4 km | 16 | 1370m - 2513m (Δ 1143m) |
| `altabadia` | Alta Badia | Italy 🇮🇹 | 146.6 km | 54 | 1324m - 2778m (Δ 1454m) |
| `roccaraso` | Alto Sangro - Roccaraso | Italy 🇮🇹 | 63.8 km | 28 | 1390m - 2141m (Δ 751m) |
| `aprica` | Aprica | Italy 🇮🇹 | 13.7 km | 7 | 1180m - 2300m (Δ 1120m) |
| `arabba` | Arabba - Marmolada | Italy 🇮🇹 | 57.0 km | 21 | 1446m - 3269m (Δ 1823m) |
| `bardonecchia` | Bardonecchia | Italy 🇮🇹 | 52.8 km | 19 | 1312m - 2800m (Δ 1488m) |
| `belpiano` | Belpiano - Passo Resia (Schöneben) | Italy 🇮🇹 | 26.5 km | 14 | 1460m - 2390m (Δ 930m) |
| `bormio` | Bormio | Italy 🇮🇹 | 72.2 km | 14 | 1225m - 3012m (Δ 1787m) |
| `cervinia` | Breuil-Cervinia Valtournenche | Italy 🇮🇹 | 196.5 km | 35 | 1524m - 3883m (Δ 2359m) |
| `carezza` | Carezza Dolomites | Italy 🇮🇹 | 24.2 km | 13 | 1182m - 2337m (Δ 1155m) |
| `chiesa` | Chiesa in Valmalenco (Alpe Palù) | Italy 🇮🇹 | 49.8 km | 9 | 1000m - 2336m (Δ 1336m) |
| `cortina` | Cortina d'Ampezzo | Italy 🇮🇹 | 90.0 km | 28 | 1224m - 2930m (Δ 1706m) |
| `courmayeur` | Courmayeur Mont Blanc | Italy 🇮🇹 | 91.1 km | 22 | 1210m - 2755m (Δ 1545m) |
| `folgarida` | Folgarida - Marilleva | Italy 🇮🇹 | 42.9 km | 26 | 1300m - 2180m (Δ 880m) |
| `foppolo` | Foppolo - Carona (Brembo Ski) | Italy 🇮🇹 | 46.0 km | 12 | 1500m - 2163m (Δ 663m) |
| `gitschberg` | Gitschberg Jochtal | Italy 🇮🇹 | 17.2 km | 10 | 1307m - 2510m (Δ 1203m) |
| `kronplatz` | Kronplatz - Plan de Corones | Italy 🇮🇹 | 88.2 km | 25 | 935m - 2275m (Δ 1340m) |
| `lathuile` | La Thuile (Espace San Bernardo) | Italy 🇮🇹 | 147.2 km | 18 | 1440m - 2800m (Δ 1360m) |
| `limone` | Limone Piemonte (Riserva Bianca) | Italy 🇮🇹 | 40.5 km | 17 | 1050m - 2050m (Δ 1000m) |
| `livigno` | Livigno | Italy 🇮🇹 | 125.0 km | 31 | 1816m - 2798m (Δ 982m) |
| `madesimo` | Madesimo Valchiavenna | Italy 🇮🇹 | 34.7 km | 12 | 1550m - 2948m (Δ 1398m) |
| `mondole` | Mondolè Ski (Prato Nevoso - Artesina) | Italy 🇮🇹 | 72.2 km | 24 | 1300m - 2085m (Δ 785m) |
| `cimone` | Monte Cimone | Italy 🇮🇹 | 49.0 km | 21 | 900m - 2165m (Δ 1265m) |
| `zoncolan` | Monte Zoncolan | Italy 🇮🇹 | 23.7 km | 17 | 950m - 1970m (Δ 1020m) |
| `monterosa` | Monterosa Ski | Italy 🇮🇹 | 66.5 km | 26 | 1212m - 3275m (Δ 2063m) |
| `ovindoli` | Ovindoli & Campo Felice | Italy 🇮🇹 | 15.4 km | 12 | 1400m - 2220m (Δ 820m) |
| `paganella` | Paganella Ski (Andalo) | Italy 🇮🇹 | 33.1 km | 14 | 1030m - 2125m (Δ 1095m) |
| `pianidibobbio` | Piani di Bobbio - Valtorta | Italy 🇮🇹 | 17.0 km | 12 | 1340m - 1950m (Δ 610m) |
| `pila` | Pila | Italy 🇮🇹 | 55.0 km | 16 | 1540m - 2750m (Δ 1210m) |
| `plose` | Plose (Bressanone) | Italy 🇮🇹 | 25.0 km | 7 | 1067m - 2505m (Δ 1438m) |
| `pontedilegno` | Pontedilegno-Tonale | Italy 🇮🇹 | 55.1 km | 31 | 1121m - 3016m (Δ 1895m) |
| `racines` | Racines-Giovo (Ratschings-Jaufen) | Italy 🇮🇹 | 23.7 km | 11 | 1300m - 2150m (Δ 850m) |
| `sandomenico` | San Domenico di Varzo | Italy 🇮🇹 | 19.9 km | 7 | 1420m - 2550m (Δ 1130m) |
| `sanmartino` | San Martino di Castrozza - Passo Rolle | Italy 🇮🇹 | 51.0 km | 19 | 1404m - 2357m (Δ 953m) |
| `santacaterina` | Santa Caterina Valfurva | Italy 🇮🇹 | 34.9 km | 6 | 1738m - 2880m (Δ 1142m) |
| `obereggen` | Ski Center Latemar (Obereggen-Pampeago) | Italy 🇮🇹 | 85.5 km | 20 | 1540m - 2388m (Δ 848m) |
| `civetta` | Ski Civetta (Alleghe - Val di Zoldo) | Italy 🇮🇹 | 57.9 km | 26 | 1000m - 2100m (Δ 1100m) |
| `solda` | Solda all'Ortles (Sulden) | Italy 🇮🇹 | 35.6 km | 11 | 1900m - 3250m (Δ 1350m) |
| `speikboden` | Speikboden & Klausberg (Valle Aurina) | Italy 🇮🇹 | 24.9 km | 9 | 950m - 2510m (Δ 1560m) |
| `tarvisio` | Tarvisio - Monte Lussari | Italy 🇮🇹 | 24.0 km | 17 | 754m - 1760m (Δ 1006m) |
| `valgardena` | Val Gardena - Alpe di Siusi | Italy 🇮🇹 | 239.6 km | 83 | 1236m - 2518m (Δ 1282m) |
| `valsenales` | Val Senales (Schnalstal) | Italy 🇮🇹 | 32.3 km | 12 | 2011m - 3212m (Δ 1201m) |
| `valdifassa` | Val di Fassa (Belvedere - Col Rodella) | Italy 🇮🇹 | 89.2 km | 27 | 1320m - 2485m (Δ 1165m) |
| `vialattea` | Via Lattea (Sestriere - Sauze d'Oulx) | Italy 🇮🇹 | 479.9 km | 80 | 1350m - 2823m (Δ 1473m) |

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
