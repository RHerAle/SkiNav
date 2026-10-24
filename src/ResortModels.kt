// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

data class ResortSummary(
    val id: String,
    val name: String,
    val country: String,
    val flag: String = "🏔️",
    val stats: ResortStats? = null
)

data class ResortStats(
    val slopes: String = "",
    val lifts: Int = 0,
    val elevation: String = "",
    val drop: String = ""
)

data class ResortNode(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val type: String = "station"
)

data class ResortEdge(
    val id: String,
    val name: String,
    val type: String, // "slope", "lift", "bus", "walk"
    val from: String,
    val to: String,
    val distance: Double = 0.0,
    val duration: Double = 2.0,
    val elevation: Double? = null,
    val difficulty: String? = null, // "green", "blue", "red", "black"
    val subtype: String? = null,    // "gondola", "cable_car", "chair_lift", "mixed_lift", "funicular"
    val bidirectional: Boolean? = null,
    val geometry: List<List<Double>>? = null
)

data class SkiResort(
    val id: String,
    val name: String,
    val country: String,
    val flag: String? = "🏔️",
    val bounds: List<List<Double>>,
    val center: List<Double>,
    val stats: ResortStats,
    val nodes: List<ResortNode>,
    val edges: List<ResortEdge>
) {
    @Transient
    private var nodeMapCache: Map<String, ResortNode>? = null

    fun getNode(nodeId: String): ResortNode? {
        var map = nodeMapCache
        if (map == null) {
            map = nodes.associateBy { it.id }
            nodeMapCache = map
        }
        return map[nodeId]
    }

    fun getSelectableLocations(): List<ResortNode> {
        val stations = nodes.filter { it.type == "station" }.sortedBy { it.name }
        val junctions = nodes.filter {
            it.type == "intersection" && it.name.isNotBlank() && !it.name.startsWith("Junction #")
        }.sortedBy { it.name }
        return stations + junctions
    }
}

data class RouteFilters(
    val maxDifficulty: String = "black",
    val includeBus: Boolean = true,
    val fastestRoute: Boolean = true
)

data class RouteStep(
    val from: String,
    val to: String,
    val edge: ResortEdge,
    val reverse: Boolean = false
)

data class RouteStats(
    val distanceKm: String,
    val timeMinutes: Int,
    val elevationGainMeters: Int,
    val elevationLossMeters: Int,
    val slopesCount: Int,
    val liftsCount: Int,
    val busesCount: Int,
    val walksCount: Int,
    val difficulties: List<String>
)

data class RouteAlternative(
    val name: String,
    val path: List<RouteStep>,
    val stats: RouteStats,
    val type: String
)
