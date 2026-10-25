// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

// ponytail: compact data models
data class ResortSummary(val id: String, val name: String, val country: String, val flag: String = "🏔️", val stats: ResortStats? = null)
data class ResortStats(val slopes: String = "", val lifts: Int = 0, val elevation: String = "", val drop: String = "")
data class ResortNode(val id: String, val name: String, val lat: Double, val lng: Double, val type: String = "station")
data class BusSchedule(val lineName: String? = null, val startTime: String = "08:30", val endTime: String = "17:30", val frequencyMinutes: Int = 15, val exactDepartures: List<String>? = null)
enum class EdgeStatus { OPEN, CLOSED, ON_HOLD }

data class ResortEdge(
    val id: String,
    val name: String,
    val type: String,
    val from: String,
    val to: String,
    val distance: Double = 0.0,
    val duration: Double = 2.0,
    val elevation: Double? = null,
    val difficulty: String? = null,
    val subtype: String? = null,
    val bidirectional: Boolean? = null,
    val geometry: List<List<Double>>? = null,
    val schedule: BusSchedule? = null,
    val operatingHours: String? = null
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
    @Transient private var nodeMapCache: Map<String, ResortNode>? = null
    @Transient private var outgoingCache: Map<String, List<RouteNeighbor>>? = null

    // ponytail: lazy cache map lookup
    fun getNode(nodeId: String): ResortNode? =
        (nodeMapCache ?: nodes.associateBy { it.id }.also { nodeMapCache = it })[nodeId]

    // ponytail: cached adjacency list per resort eliminates full-graph rebuilds in A*
    fun getOutgoingNeighbors(nodeId: String): List<RouteNeighbor> =
        (outgoingCache ?: buildAdjacency().also { outgoingCache = it })[nodeId] ?: emptyList()

    private fun buildAdjacency(): Map<String, List<RouteNeighbor>> {
        val map = HashMap<String, MutableList<RouteNeighbor>>(nodes.size)
        val nodeMap = nodeMapCache ?: nodes.associateBy { it.id }.also { nodeMapCache = it }
        for (node in nodes) map[node.id] = ArrayList()
        for (edge in edges) {
            map[edge.from]?.add(RouteNeighbor(edge.to, edge, false, nodeMap[edge.to]))
            val rev = edge.bidirectional != false && (
                edge.bidirectional == true || edge.type == "bus" || edge.type == "walk" ||
                (edge.type == "lift" && edge.subtype in BIDIRECTIONAL_LIFTS)
            )
            if (rev) map[edge.to]?.add(RouteNeighbor(edge.from, edge, true, nodeMap[edge.from]))
        }
        return map
    }

    fun getSelectableLocations(): List<ResortNode> =
        nodes.filter { it.type == "station" }.sortedBy { it.name } +
            nodes.filter { it.type == "intersection" && it.name.isNotBlank() && !it.name.startsWith("Junction #") }.sortedBy { it.name }
}

val BIDIRECTIONAL_LIFTS = setOf("gondola", "cable_car", "mixed_lift", "funicular")
class RouteNeighbor(val nodeId: String, val edge: ResortEdge, val reverse: Boolean, val node: ResortNode? = null)

data class RouteFilters(
    val maxDifficulty: String = "black",
    val includeBus: Boolean = true,
    val fastestRoute: Boolean = true,
    val closedEdgeIds: Set<String> = emptySet(),
    val departureTimeMinutes: Int? = null
)

data class RouteStep(
    val from: String,
    val to: String,
    val edge: ResortEdge,
    val reverse: Boolean = false,
    val waitMinutes: Int = 0,
    val nextDepartureTime: String? = null
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
    val difficulties: List<String>,
    val totalWaitMinutes: Int = 0
)

data class RouteAlternative(val name: String, val path: List<RouteStep>, val stats: RouteStats, val type: String)
