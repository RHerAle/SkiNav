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
        assertEquals(9, summaries.size)

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

        // Route alternatives across real OSM resorts
        for (id in listOf("madonna", "baqueira", "sierranevada", "formigal", "cerler", "candanchu", "alpe", "les3vallees")) {
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
}
