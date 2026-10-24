// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

import kotlin.math.*

object ResortPathfinder {

    private val DIFFICULTY_ORDER = listOf("green", "blue", "red", "black")
    private val BIDIRECTIONAL_LIFT_SUBTYPES = setOf("gondola", "cable_car", "mixed_lift", "funicular")

    private data class Neighbor(
        val nodeId: String,
        val edge: ResortEdge,
        val reverse: Boolean
    )

    private data class HeapItem(
        val nodeId: String,
        val priority: Double
    )

    private class MinHeap {
        private val heap = ArrayList<HeapItem>()

        fun isEmpty(): Boolean = heap.isEmpty()

        fun enqueue(nodeId: String, priority: Double) {
            heap.add(HeapItem(nodeId, priority))
            siftUp(heap.size - 1)
        }

        fun dequeue(): HeapItem {
            val top = heap[0]
            val last = heap.removeAt(heap.size - 1)
            if (heap.isNotEmpty()) {
                heap[0] = last
                siftDown(0)
            }
            return top
        }

        private fun siftUp(index: Int) {
            var idx = index
            while (idx > 0) {
                val parent = (idx - 1) / 2
                if (heap[idx].priority >= heap[parent].priority) break
                val tmp = heap[idx]
                heap[idx] = heap[parent]
                heap[parent] = tmp
                idx = parent
            }
        }

        private fun siftDown(index: Int) {
            var idx = index
            val n = heap.size
            while (true) {
                var smallest = idx
                val left = 2 * idx + 1
                val right = 2 * idx + 2
                if (left < n && heap[left].priority < heap[smallest].priority) {
                    smallest = left
                }
                if (right < n && heap[right].priority < heap[smallest].priority) {
                    smallest = right
                }
                if (smallest == idx) break
                val tmp = heap[idx]
                heap[idx] = heap[smallest]
                heap[smallest] = tmp
                idx = smallest
            }
        }
    }

    private fun buildAdjacencyList(
        resort: SkiResort,
        filters: RouteFilters
    ): Map<String, List<Neighbor>> {
        val graph = HashMap<String, MutableList<Neighbor>>(resort.nodes.size)
        for (node in resort.nodes) {
            graph[node.id] = ArrayList()
        }

        for (edge in resort.edges) {
            if (!shouldIncludeEdge(edge, filters)) continue

            graph[edge.from]?.add(Neighbor(edge.to, edge, reverse = false))

            val isReversible = edge.bidirectional != false && (
                edge.bidirectional == true ||
                    edge.type == "bus" ||
                    edge.type == "walk" ||
                    (edge.type == "lift" && edge.subtype in BIDIRECTIONAL_LIFT_SUBTYPES)
                )

            if (isReversible) {
                graph[edge.to]?.add(Neighbor(edge.from, edge, reverse = true))
            }
        }
        return graph
    }

    fun parseTimeMinutes(hhmm: String): Int? {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun formatTimeMinutes(minutes: Int): String {
        val norm = ((minutes % 1440) + 1440) % 1440
        val h = norm / 60
        val m = norm % 60
        return "%02d:%02d".format(java.util.Locale.US, h, m)
    }

    fun isEdgeOperatingAt(edge: ResortEdge, timeMinutes: Int): Boolean {
        if (edge.type == "bus" && edge.schedule != null) {
            val sched = edge.schedule
            val startMin = parseTimeMinutes(sched.startTime) ?: (8 * 60 + 30)
            val endMin = parseTimeMinutes(sched.endTime) ?: (17 * 60 + 30)
            val exact = sched.exactDepartures
            if (!exact.isNullOrEmpty()) {
                val depMinutes = exact.mapNotNull { parseTimeMinutes(it) }.sorted()
                if (timeMinutes < startMin - 30) return false
                return depMinutes.any { it >= timeMinutes }
            } else {
                return timeMinutes in startMin..endMin
            }
        }
        if (edge.operatingHours != null) {
            val parts = edge.operatingHours.split("-")
            if (parts.size == 2) {
                val startMin = parseTimeMinutes(parts[0])
                val endMin = parseTimeMinutes(parts[1])
                if (startMin != null && endMin != null) {
                    return timeMinutes in startMin..endMin
                }
            }
        }
        return true
    }

    fun calculateBusWait(edge: ResortEdge, departureTimeMinutes: Int?): Pair<Int, String?> {
        if (edge.type != "bus" || edge.schedule == null) {
            return Pair(0, null)
        }
        val sched = edge.schedule
        val startMin = parseTimeMinutes(sched.startTime) ?: (8 * 60 + 30)
        val endMin = parseTimeMinutes(sched.endTime) ?: (17 * 60 + 30)
        val freq = if (sched.frequencyMinutes > 0) sched.frequencyMinutes else 15

        if (departureTimeMinutes != null) {
            val exact = sched.exactDepartures
            if (!exact.isNullOrEmpty()) {
                val depMinutes = exact.mapNotNull { parseTimeMinutes(it) }.sorted()
                val nextDep = depMinutes.firstOrNull { it >= departureTimeMinutes }
                return if (nextDep != null) {
                    val wait = nextDep - departureTimeMinutes
                    Pair(maxOf(0, wait), formatTimeMinutes(nextDep))
                } else {
                    Pair(0, null)
                }
            } else {
                val nextMin = if (departureTimeMinutes <= startMin) {
                    startMin
                } else {
                    val elapsed = departureTimeMinutes - startMin
                    val k = ceil(elapsed.toDouble() / freq).toInt()
                    startMin + k * freq
                }
                return if (nextMin <= endMin) {
                    val wait = nextMin - departureTimeMinutes
                    Pair(maxOf(0, wait), formatTimeMinutes(nextMin))
                } else {
                    Pair(0, null)
                }
            }
        } else {
            val avgWait = maxOf(1, freq / 2)
            return Pair(avgWait, null)
        }
    }

    private fun shouldIncludeEdge(edge: ResortEdge, filters: RouteFilters): Boolean {
        if (edge.id in filters.closedEdgeIds) {
            return false
        }
        if (filters.departureTimeMinutes != null && !isEdgeOperatingAt(edge, filters.departureTimeMinutes)) {
            return false
        }
        if (edge.type == "bus" && !filters.includeBus) {
            return false
        }
        if (edge.type == "slope" && filters.maxDifficulty.isNotEmpty()) {
            val edgeIdx = DIFFICULTY_ORDER.indexOf(edge.difficulty ?: "blue").let { if (it < 0) 1 else it }
            val maxIdx = DIFFICULTY_ORDER.indexOf(filters.maxDifficulty).let { if (it < 0) 3 else it }
            if (edgeIdx > maxIdx) {
                return false
            }
        }
        return true
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun heuristic(resort: SkiResort, nodeId1: String, nodeId2: String): Double {
        val n1 = resort.getNode(nodeId1) ?: return 0.0
        val n2 = resort.getNode(nodeId2) ?: return 0.0
        val distKm = haversineKm(n1.lat, n1.lng, n2.lat, n2.lng)
        return (distKm / 20.0) * 60.0
    }

    private fun calculateEdgeCost(edge: ResortEdge, filters: RouteFilters): Double {
        var cost = if (edge.duration > 0) edge.duration else 2.0
        val (waitMins, _) = calculateBusWait(edge, filters.departureTimeMinutes)
        cost += waitMins
        if (filters.fastestRoute) return cost

        when (edge.type) {
            "slope" -> {
                cost += when (edge.difficulty) {
                    "green" -> 0.0
                    "blue" -> 2.0
                    "red" -> 7.0
                    "black" -> 18.0
                    else -> 2.0
                }
            }
            "bus" -> cost += 6.0
            "walk" -> cost += 2.0
        }
        return cost
    }

    fun findPath(
        resort: SkiResort,
        startId: String,
        endId: String,
        filters: RouteFilters = RouteFilters()
    ): List<RouteStep>? {
        if (startId == endId) return emptyList()

        val graph = buildAdjacencyList(resort, filters)
        val openSet = MinHeap()
        val closedSet = HashSet<String>()
        val cameFrom = HashMap<String, RouteStep>()

        val gScore = HashMap<String, Double>()
        gScore[startId] = 0.0

        val startF = heuristic(resort, startId, endId)
        openSet.enqueue(startId, startF)

        while (!openSet.isEmpty()) {
            val current = openSet.dequeue().nodeId
            if (current == endId) {
                return reconstructPath(cameFrom, current)
            }
            if (!closedSet.add(current)) continue

            val currentG = gScore[current] ?: Double.POSITIVE_INFINITY
            val neighbors = graph[current] ?: emptyList()

            for (neighbor in neighbors) {
                if (neighbor.nodeId in closedSet) continue
                val tentativeG = currentG + calculateEdgeCost(neighbor.edge, filters)
                val prevG = gScore[neighbor.nodeId]
                if (prevG == null || tentativeG < prevG) {
                    val (waitMins, nextDep) = if (neighbor.edge.type == "bus") {
                        calculateBusWait(neighbor.edge, filters.departureTimeMinutes)
                    } else {
                        Pair(0, null)
                    }
                    cameFrom[neighbor.nodeId] = RouteStep(
                        from = current,
                        to = neighbor.nodeId,
                        edge = neighbor.edge,
                        reverse = neighbor.reverse,
                        waitMinutes = waitMins,
                        nextDepartureTime = nextDep
                    )
                    gScore[neighbor.nodeId] = tentativeG
                    val f = tentativeG + heuristic(resort, neighbor.nodeId, endId)
                    openSet.enqueue(neighbor.nodeId, f)
                }
            }
        }
        return null
    }

    private fun reconstructPath(
        cameFrom: Map<String, RouteStep>,
        endNodeId: String
    ): List<RouteStep> {
        val path = ArrayList<RouteStep>()
        var curr = endNodeId
        while (cameFrom.containsKey(curr)) {
            val step = cameFrom[curr]!!
            path.add(step)
            curr = step.from
        }
        path.reverse()
        return path
    }

    fun calculateRouteStats(path: List<RouteStep>?): RouteStats {
        if (path.isNullOrEmpty()) {
            return RouteStats(
                distanceKm = "0.0",
                timeMinutes = 0,
                elevationGainMeters = 0,
                elevationLossMeters = 0,
                slopesCount = 0,
                liftsCount = 0,
                busesCount = 0,
                walksCount = 0,
                difficulties = emptyList(),
                totalWaitMinutes = 0
            )
        }

        var totalDistance = 0.0
        var totalTime = 0.0
        var totalWait = 0
        var totalGain = 0.0
        var totalLoss = 0.0
        var slopes = 0
        var lifts = 0
        var buses = 0
        var walks = 0
        val difficulties = LinkedHashSet<String>()

        for (step in path) {
            val edge = step.edge
            totalDistance += edge.distance
            totalTime += edge.duration + step.waitMinutes
            totalWait += step.waitMinutes
            when (edge.type) {
                "slope" -> {
                    slopes++
                    edge.difficulty?.let { difficulties.add(it) }
                    totalLoss += abs(edge.elevation ?: (edge.distance * 160.0))
                }
                "lift" -> {
                    lifts++
                    totalGain += abs(edge.elevation ?: (edge.distance * 220.0))
                }
                "bus" -> buses++
                "walk" -> walks++
            }
        }

        return RouteStats(
            distanceKm = "%.1f".format(java.util.Locale.US, totalDistance),
            timeMinutes = totalTime.roundToInt(),
            elevationGainMeters = totalGain.roundToInt(),
            elevationLossMeters = totalLoss.roundToInt(),
            slopesCount = slopes,
            liftsCount = lifts,
            busesCount = buses,
            walksCount = walks,
            difficulties = difficulties.toList(),
            totalWaitMinutes = totalWait
        )
    }

    fun findRouteAlternatives(
        resort: SkiResort,
        startId: String,
        endId: String,
        baseFilters: RouteFilters = RouteFilters()
    ): List<RouteAlternative> {
        val routes = ArrayList<RouteAlternative>()

        // 1. Fastest route
        val fastestFilters = baseFilters.copy(fastestRoute = true)
        val fastest = findPath(resort, startId, endId, fastestFilters)
        if (!fastest.isNullOrEmpty()) {
            routes.add(
                RouteAlternative(
                    name = "Fastest Route",
                    path = fastest,
                    stats = calculateRouteStats(fastest),
                    type = "fastest"
                )
            )
        }

        // 2. Easiest / Scenic route
        val easiestFilters = baseFilters.copy(fastestRoute = false)
        val easiest = findPath(resort, startId, endId, easiestFilters)
        if (!easiest.isNullOrEmpty() && !arePathsEqual(easiest, fastest)) {
            routes.add(
                RouteAlternative(
                    name = "Easiest / Scenic Route",
                    path = easiest,
                    stats = calculateRouteStats(easiest),
                    type = "easiest"
                )
            )
        }

        // 3. Ski-Only (No Bus) route
        if (!fastest.isNullOrEmpty() && fastest.any { it.edge.type == "bus" }) {
            val noBusFilters = baseFilters.copy(includeBus = false, fastestRoute = true)
            val skiOnly = findPath(resort, startId, endId, noBusFilters)
            if (!skiOnly.isNullOrEmpty() && !arePathsEqual(skiOnly, fastest)) {
                routes.add(
                    RouteAlternative(
                        name = "Ski-Only (No Bus)",
                        path = skiOnly,
                        stats = calculateRouteStats(skiOnly),
                        type = "ski-only"
                    )
                )
            }
        }

        return routes
    }

    private fun arePathsEqual(p1: List<RouteStep>?, p2: List<RouteStep>?): Boolean {
        if (p1 == null || p2 == null || p1.size != p2.size) return false
        for (i in p1.indices) {
            if (p1[i].edge.id != p2[i].edge.id) return false
        }
        return true
    }

    fun formatTime(minutes: Int): String {
        if (minutes <= 0) return "< 1 min"
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m > 0) "${h}h ${m}m" else "${h}h"
    }
}
