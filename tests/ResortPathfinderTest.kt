// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ResortPathfinderTest {

    private val gson = Gson()
    private val assetsDir = File("src/main/assets/resorts")

    private fun loadResort(id: String): SkiResort {
        val file = File(assetsDir, "$id.json")
        assertTrue("Asset file ${file.path} must exist", file.exists())
        return gson.fromJson(file.readText(Charsets.UTF_8), SkiResort::class.java)
    }

    @Test
    fun testAllNineResortsExistAndHaveTopologicalIntegrity() {
        val indexFile = File(assetsDir, "index.json")
        assertTrue(indexFile.exists())
        val type = object : TypeToken<List<ResortSummary>>() {}.type
        val summaries: List<ResortSummary> = gson.fromJson(indexFile.readText(Charsets.UTF_8), type)
        assertEquals(82, summaries.size)

        for (summary in summaries) {
            val resort = loadResort(summary.id)
            assertEquals(summary.id, resort.id)
            assertTrue("Resort ${resort.id} must have nodes", resort.nodes.isNotEmpty())
            assertTrue("Resort ${resort.id} must have edges", resort.edges.isNotEmpty())

            val nodeIds = resort.nodes.map { it.id }.toSet()
            assertEquals("Unique node IDs in ${resort.id}", resort.nodes.size, nodeIds.size)

            val edgeIds = resort.edges.map { it.id }.toSet()
            assertEquals("Unique edge IDs in ${resort.id}", resort.edges.size, edgeIds.size)

            for (edge in resort.edges) {
                assertNotNull("Edge ${edge.id} from-node exists", resort.getNode(edge.from))
                assertNotNull("Edge ${edge.id} to-node exists", resort.getNode(edge.to))
            }
        }
    }

    @Test
    fun testPathfindingAndDifficultyFilters() {
        val alpine = loadResort("alpine")

        // Same start and end returns empty list
        val emptyPath = ResortPathfinder.findPath(alpine, "base", "base")
        assertNotNull(emptyPath)
        assertTrue(emptyPath!!.isEmpty())

        // Valid route from base to summit_main and back
        val climbPath = ResortPathfinder.findPath(alpine, "base", "summit_main")
        assertNotNull(climbPath)
        assertTrue(climbPath!!.isNotEmpty())
        assertEquals("base", climbPath.first().from)
        assertEquals("summit_main", climbPath.last().to)

        // Downhill with blue maxDifficulty must not use red or black slopes
        val blueDownhill = ResortPathfinder.findPath(
            alpine,
            "summit_main",
            "base",
            RouteFilters(maxDifficulty = "blue", includeBus = true, fastestRoute = true)
        )
        assertNotNull(blueDownhill)
        assertTrue(blueDownhill!!.isNotEmpty())
        for (step in blueDownhill) {
            if (step.edge.type == "slope") {
                assertTrue(
                    "Expected green or blue slope, got ${step.edge.difficulty}",
                    step.edge.difficulty in setOf("green", "blue")
                )
            }
        }

        // Route alternatives across all real OSM resorts in index.json
        val indexFile = File(assetsDir, "index.json")
        val type = object : TypeToken<List<ResortSummary>>() {}.type
        val summaries: List<ResortSummary> = gson.fromJson(indexFile.readText(Charsets.UTF_8), type)
        for (id in summaries.map { it.id }.filter { it != "alpine" }) {
            val resort = loadResort(id)
            val stations = resort.getSelectableLocations()
            assertTrue("Resort $id has selectable stations", stations.size >= 2)

            var foundRoute = false
            for (i in 0 until minOf(10, stations.size - 1)) {
                val alts = ResortPathfinder.findRouteAlternatives(
                    resort,
                    stations[i].id,
                    stations[(i + 3) % stations.size].id
                )
                if (alts.isNotEmpty()) {
                    foundRoute = true
                    assertTrue(alts[0].stats.timeMinutes > 0)
                    break
                }
            }
            assertTrue("Expected at least one valid route in $id", foundRoute)
        }
    }

    @Test
    fun testFormatTime() {
        assertEquals("< 1 min", ResortPathfinder.formatTime(0))
        assertEquals("25 min", ResortPathfinder.formatTime(25))
        assertEquals("1h", ResortPathfinder.formatTime(60))
        assertEquals("1h 15m", ResortPathfinder.formatTime(75))
    }

    @Test
    fun testClosedEdgeIdsExclusion() {
        val alpine = loadResort("alpine")
        val normalPath = ResortPathfinder.findPath(alpine, "summit_main", "base")
        assertNotNull(normalPath)
        assertTrue(normalPath!!.isNotEmpty())

        val closedEdgeId = normalPath.first().edge.id
        val detourPath = ResortPathfinder.findPath(
            alpine,
            "summit_main",
            "base",
            RouteFilters(closedEdgeIds = setOf(closedEdgeId))
        )
        if (detourPath != null) {
            assertTrue("Detour must not contain closed edge", detourPath.none { it.edge.id == closedEdgeId })
        }

        // Close all outgoing edges from summit_main to make it completely blocked
        val allLeaving = alpine.edges
            .filter { it.from == "summit_main" || (it.to == "summit_main" && it.bidirectional == true) }
            .map { it.id }
            .toSet()
        val blockedPath = ResortPathfinder.findPath(
            alpine,
            "summit_main",
            "base",
            RouteFilters(closedEdgeIds = allLeaving)
        )
        assertNull("Path should be null when all exits are closed", blockedPath)
    }

    @Test
    fun testBusScheduleAndWaitTimeCalculation() {
        // Time parsing and formatting
        assertEquals(510, ResortPathfinder.parseTimeMinutes("08:30"))
        assertEquals(1050, ResortPathfinder.parseTimeMinutes("17:30"))
        assertEquals("08:30", ResortPathfinder.formatTimeMinutes(510))
        assertEquals("17:30", ResortPathfinder.formatTimeMinutes(1050))
        assertNull(ResortPathfinder.parseTimeMinutes("invalid"))

        val busEdge = ResortEdge(
            id = "bus_test",
            name = "Test Shuttle",
            type = "bus",
            from = "station_A",
            to = "station_B",
            distance = 4.0,
            duration = 10.0,
            schedule = BusSchedule(
                lineName = "Test Shuttle Line",
                startTime = "08:30",
                endTime = "17:30",
                frequencyMinutes = 15
            )
        )

        // 1. Without departure time -> average wait (15 / 2 = 7)
        val (avgWait, depNull) = ResortPathfinder.calculateBusWait(busEdge, null)
        assertEquals(7, avgWait)
        assertNull(depNull)

        // 2. Exact departure match at 08:30 (510 min) -> 0 wait
        val (exactWait, exactDep) = ResortPathfinder.calculateBusWait(busEdge, 510)
        assertEquals(0, exactWait)
        assertEquals("08:30", exactDep)

        // 3. Arrive at 08:34 (514 min) -> next is 08:45 (525 min), wait is 11 min
        val (wait11, dep45) = ResortPathfinder.calculateBusWait(busEdge, 514)
        assertEquals(11, wait11)
        assertEquals("08:45", dep45)

        // 4. Test exact departures list
        val exactScheduleBus = busEdge.copy(
            schedule = BusSchedule(
                lineName = "Exact Line",
                startTime = "09:00",
                endTime = "16:00",
                exactDepartures = listOf("09:00", "11:30", "14:15", "16:00")
            )
        )
        val (waitExactList, depExactList) = ResortPathfinder.calculateBusWait(exactScheduleBus, 9 * 60 + 10)
        assertEquals(140, waitExactList)
        assertEquals("11:30", depExactList)

        // 5. Test pathfinding with real resort bus (baqueira)
        val baqueira = loadResort("baqueira")
        val busEdges = baqueira.edges.filter { it.type == "bus" }
        assertTrue("Baqueira must have bus edges", busEdges.isNotEmpty())
        val bus = busEdges.first()
        assertNotNull("Bus must have schedule", bus.schedule)

        // Departure at 09:04 (544 min)
        val depTime = 9 * 60 + 4
        val filtersWithTime = RouteFilters(departureTimeMinutes = depTime)
        val path = ResortPathfinder.findPath(baqueira, bus.from, bus.to, filtersWithTime)
        assertNotNull(path)
        assertTrue(path!!.isNotEmpty())

        val busStep = path.firstOrNull { it.edge.type == "bus" }
        assertNotNull("Path should include a bus step", busStep)
        assertTrue("Bus step waitMinutes must be > 0", busStep!!.waitMinutes > 0)
        assertNotNull("Next departure time must be formatted", busStep.nextDepartureTime)

        val stats = ResortPathfinder.calculateRouteStats(path)
        assertTrue("Stats totalWaitMinutes must match bus step wait", stats.totalWaitMinutes >= busStep.waitMinutes)
        assertTrue("Total time must include wait minutes", stats.timeMinutes >= busStep.waitMinutes)
    }

    @Test
    fun testOutsideOperatingHoursExclusion() {
        val liftEdge = ResortEdge(
            id = "lift_1",
            name = "Chairlift",
            type = "lift",
            from = "A",
            to = "B",
            operatingHours = "08:45-16:45"
        )
        val busEdge = ResortEdge(
            id = "bus_1",
            name = "Ski Bus",
            type = "bus",
            from = "A",
            to = "B",
            schedule = BusSchedule(startTime = "08:30", endTime = "17:30", frequencyMinutes = 15)
        )

        // Inside hours (12:00 = 720 min)
        assertTrue(ResortPathfinder.isEdgeOperatingAt(liftEdge, 12 * 60))
        assertTrue(ResortPathfinder.isEdgeOperatingAt(busEdge, 12 * 60))

        // Outside hours evening (20:00 = 1200 min)
        assertFalse(ResortPathfinder.isEdgeOperatingAt(liftEdge, 20 * 60))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(busEdge, 20 * 60))

        // Outside hours early morning (06:00 = 360 min)
        assertFalse(ResortPathfinder.isEdgeOperatingAt(liftEdge, 6 * 60))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(busEdge, 6 * 60))

        // In a real resort (Formigal), at 20:00 all lifts and ski buses are closed
        val formigal = loadResort("formigal")
        val eveningFilters = RouteFilters(departureTimeMinutes = 20 * 60)
        val stations = formigal.getSelectableLocations()
        assertTrue(stations.size >= 2)

        val baseSextas = stations.firstOrNull { it.name.contains("Base") || it.name.contains("Sextas") }?.id ?: stations[0].id
        val summit = stations.firstOrNull { it.name.contains("Summit") || it.name.contains("Top") || it.name.contains("Cima") }?.id ?: stations[1].id
        val eveningPath = ResortPathfinder.findPath(formigal, baseSextas, summit, eveningFilters)
        if (eveningPath != null) {
            assertTrue(
                "No lift or bus should be open at 20:00",
                eveningPath.none { it.edge.type == "lift" || it.edge.type == "bus" }
            )
        }
    }
}
