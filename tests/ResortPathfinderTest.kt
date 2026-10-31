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
    private val assetsDir = File("src/main/assets/resorts").let { if (it.exists()) it else File("data") }

    private fun loadResort(id: String): SkiResort =
        gson.fromJson(File(assetsDir, "$id.json").readText(), SkiResort::class.java)

    private fun loadSummaries(): List<ResortSummary> =
        gson.fromJson(File(assetsDir, "index.json").readText(), object : TypeToken<List<ResortSummary>>() {}.type)

    @Test
    fun testAllNineResortsExistAndHaveTopologicalIntegrity() {
        val summaries = loadSummaries()
        assertEquals(82, summaries.size)
        for (s in summaries) {
            val r = loadResort(s.id)
            assertEquals(s.id, r.id)
            assertTrue("Nodes in ${r.id}", r.nodes.isNotEmpty())
            assertTrue("Edges in ${r.id}", r.edges.isNotEmpty())
            assertEquals("Unique nodes in ${r.id}", r.nodes.size, r.nodes.map { it.id }.toSet().size)
            assertEquals("Unique edges in ${r.id}", r.edges.size, r.edges.map { it.id }.toSet().size)
            for (e in r.edges) {
                assertNotNull("From node ${e.from}", r.getNode(e.from))
                assertNotNull("To node ${e.to}", r.getNode(e.to))
            }
        }
    }

    @Test
    fun testPathfindingAndDifficultyFilters() {
        val alpine = loadResort("alpine")
        assertTrue(ResortPathfinder.findPath(alpine, "base", "base")!!.isEmpty())

        val climb = ResortPathfinder.findPath(alpine, "base", "summit_main")!!
        assertTrue(climb.isNotEmpty())
        assertEquals("base", climb.first().from)
        assertEquals("summit_main", climb.last().to)

        val blueDown = ResortPathfinder.findPath(alpine, "summit_main", "base", RouteFilters(maxDifficulty = "blue"))!!
        assertTrue(blueDown.isNotEmpty())
        assertTrue(blueDown.filter { it.edge.type == "slope" }.all { it.edge.difficulty in setOf("green", "blue") })

        for (id in loadSummaries().map { it.id }.filter { it != "alpine" }) {
            val resort = loadResort(id)
            val stations = resort.getSelectableLocations()
            assertTrue("Stations in $id", stations.size >= 2)
            val found = (0 until minOf(10, stations.size - 1)).any { i ->
                val alts = ResortPathfinder.findRouteAlternatives(resort, stations[i].id, stations[(i + 3) % stations.size].id)
                alts.isNotEmpty() && alts[0].stats.timeMinutes > 0
            }
            assertTrue("Route in $id", found)
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
        val normal = ResortPathfinder.findPath(alpine, "summit_main", "base")!!
        val closedId = normal.first().edge.id
        val detour = ResortPathfinder.findPath(alpine, "summit_main", "base", RouteFilters(closedEdgeIds = setOf(closedId)))
        if (detour != null) assertTrue(detour.none { it.edge.id == closedId })

        val allLeaving = alpine.edges.filter { it.from == "summit_main" || (it.to == "summit_main" && it.bidirectional == true) }.map { it.id }.toSet()
        assertNull(ResortPathfinder.findPath(alpine, "summit_main", "base", RouteFilters(closedEdgeIds = allLeaving)))
    }

    @Test
    fun testBusScheduleAndWaitTimeCalculation() {
        assertEquals(510, ResortPathfinder.parseTimeMinutes("08:30"))
        assertEquals(1050, ResortPathfinder.parseTimeMinutes("17:30"))
        assertEquals("08:30", ResortPathfinder.formatTimeMinutes(510))
        assertEquals("17:30", ResortPathfinder.formatTimeMinutes(1050))
        assertNull(ResortPathfinder.parseTimeMinutes("invalid"))

        val bus = ResortEdge("bus_test", "Test Shuttle", "bus", "A", "B", 4.0, 10.0,
            schedule = BusSchedule("Line", "08:30", "17:30", 15))
        assertEquals(Pair(7, null), ResortPathfinder.calculateBusWait(bus, null))
        assertEquals(Pair(0, "08:30"), ResortPathfinder.calculateBusWait(bus, 510))
        assertEquals(Pair(11, "08:45"), ResortPathfinder.calculateBusWait(bus, 514))

        val exactBus = bus.copy(schedule = BusSchedule("Exact", "09:00", "16:00", 15, listOf("09:00", "11:30", "14:15", "16:00")))
        assertEquals(Pair(140, "11:30"), ResortPathfinder.calculateBusWait(exactBus, 550))

        val baqueira = loadResort("baqueira")
        val realBus = baqueira.edges.first { it.type == "bus" }
        val path = ResortPathfinder.findPath(baqueira, realBus.from, realBus.to, RouteFilters(departureTimeMinutes = 544))!!
        val busStep = path.first { it.edge.type == "bus" }
        assertTrue(busStep.waitMinutes > 0)
        assertNotNull(busStep.nextDepartureTime)

        val stats = ResortPathfinder.calculateRouteStats(path)
        assertTrue(stats.totalWaitMinutes >= busStep.waitMinutes)
        assertTrue(stats.timeMinutes >= busStep.waitMinutes)
    }

    @Test
    fun testOutsideOperatingHoursExclusion() {
        val lift = ResortEdge("lift_1", "Chairlift", "lift", "A", "B", operatingHours = "08:45-16:45")
        val bus = ResortEdge("bus_1", "Bus", "bus", "A", "B", schedule = BusSchedule(startTime = "08:30", endTime = "17:30"))

        assertTrue(ResortPathfinder.isEdgeOperatingAt(lift, 720))
        assertTrue(ResortPathfinder.isEdgeOperatingAt(bus, 720))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(lift, 1200))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(bus, 1200))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(lift, 360))
        assertFalse(ResortPathfinder.isEdgeOperatingAt(bus, 360))

        val formigal = loadResort("formigal")
        val stations = formigal.getSelectableLocations()
        val base = stations.firstOrNull { it.name.contains("Base") || it.name.contains("Sextas") }?.id ?: stations[0].id
        val summit = stations.firstOrNull { it.name.contains("Summit") || it.name.contains("Top") || it.name.contains("Cima") }?.id ?: stations[1].id
        val evening = ResortPathfinder.findPath(formigal, base, summit, RouteFilters(departureTimeMinutes = 1200))
        if (evening != null) assertTrue(evening.none { it.edge.type == "lift" || it.edge.type == "bus" })
    }

    @Test
    fun testInputValidationAndSecurity() {
        val alpine = loadResort("alpine")
        // Non-existent start or end node IDs should safely return null
        assertNull(ResortPathfinder.findPath(alpine, "non_existent_start", "base"))
        assertNull(ResortPathfinder.findPath(alpine, "base", "non_existent_end"))
        assertNull(ResortPathfinder.findPath(alpine, "invalid", "invalid"))

        // Malformed or excessively long time strings should be rejected
        assertNull(ResortPathfinder.parseTimeMinutes("2147483658:00"))
        assertNull(ResortPathfinder.parseTimeMinutes("0008:00"))
        assertNull(ResortPathfinder.parseTimeMinutes("12:0000"))
        assertNull(ResortPathfinder.parseTimeMinutes("25:00"))
        assertNull(ResortPathfinder.parseTimeMinutes("12:60"))
        assertNull(ResortPathfinder.parseTimeMinutes(":15"))
        assertNull(ResortPathfinder.parseTimeMinutes("12:"))

        // Sentinel security tests: bus wait calculations with negative, out-of-bounds or invalid schedules
        val busZeroFreq = ResortEdge("bus_zero", "Zero Freq", "bus", "A", "B",
            schedule = BusSchedule(frequencyMinutes = 0))
        val waitDefault = ResortPathfinder.calculateBusWait(busZeroFreq, null)
        assertEquals(7, waitDefault.first)

        val busNegativeDep = ResortEdge("bus_neg", "Bus", "bus", "A", "B",
            schedule = BusSchedule(startTime = "08:30", endTime = "17:30", frequencyMinutes = 15))
        assertEquals(Pair(0, null), ResortPathfinder.calculateBusWait(busNegativeDep, -100))
        assertEquals(Pair(0, null), ResortPathfinder.calculateBusWait(busNegativeDep, 20000))

        // Negative duration edge should not cause infinite or negative loops
        val corruptedAlpine = alpine.copy(
            edges = alpine.edges.map { if (it.id == "lift_base_mid") it.copy(duration = -50.0) else it }
        )
        val path = ResortPathfinder.findPath(corruptedAlpine, "base", "mid_central")
        assertNotNull(path)
        assertTrue(path!!.isNotEmpty())
    }
}
