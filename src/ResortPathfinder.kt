// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

import java.util.PriorityQueue
import kotlin.math.*

object ResortPathfinder {
    private val DIFFICULTY_ORDER = listOf("green", "blue", "red", "black")
    private class HeapItem(val nodeId: String, val priority: Double)

    // ponytail: zero-alloc ASCII time parser
    fun parseTimeMinutes(hhmm: String): Int? = parseTimeSlice(hhmm, 0, hhmm.length)

    private fun parseTimeSlice(s: String, from: Int, to: Int): Int? {
        var start = from
        var end = to
        while (start < end && s[start] <= ' ') start++
        while (end > start && s[end - 1] <= ' ') end--
        val colon = (start until end).firstOrNull { s[it] == ':' } ?: return null
        if (colon == start || colon == end - 1) return null
        // Security check: strictly validate HH:MM / H:MM structure to prevent integer overflow and DoS
        if (colon - start !in 1..2 || end - (colon + 1) != 2) return null
        var h = 0
        for (i in start until colon) {
            val c = s[i]
            if (c !in '0'..'9') return null
            h = h * 10 + (c - '0')
        }
        var m = 0
        for (i in colon + 1 until end) {
            val c = s[i]
            if (c !in '0'..'9') return null
            m = m * 10 + (c - '0')
        }
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun formatTimeMinutes(minutes: Int): String {
        val norm = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(java.util.Locale.US, norm / 60, norm % 60)
    }

    // ponytail: index lookup replaces regex and string split
    fun isEdgeOperatingAt(edge: ResortEdge, timeMinutes: Int): Boolean {
        if (edge.type == "bus" && edge.schedule != null) {
            val sched = edge.schedule
            val start = parseTimeMinutes(sched.startTime) ?: 510
            val end = parseTimeMinutes(sched.endTime) ?: 1050
            val exact = sched.exactDepartures
            return if (!exact.isNullOrEmpty()) {
                if (timeMinutes < start - 30) false else exact.any { (parseTimeMinutes(it) ?: -1) >= timeMinutes }
            } else {
                timeMinutes in start..end
            }
        }
        edge.operatingHours?.let { hours ->
            val dash = hours.indexOf('-')
            if (dash > 0) {
                val start = parseTimeSlice(hours, 0, dash)
                val end = parseTimeSlice(hours, dash + 1, hours.length)
                if (start != null && end != null) return timeMinutes in start..end
            }
        }
        return true
    }

    // ponytail: single-pass departure search without list allocation
    fun calculateBusWait(edge: ResortEdge, departureTimeMinutes: Int?): Pair<Int, String?> {
        val sched = edge.schedule ?: return Pair(0, null)
        if (edge.type != "bus") return Pair(0, null)
        val freq = if (sched.frequencyMinutes > 0) sched.frequencyMinutes else 15
        if (departureTimeMinutes == null) return Pair(maxOf(1, freq / 2), null)

        val start = parseTimeMinutes(sched.startTime) ?: 510
        val end = parseTimeMinutes(sched.endTime) ?: 1050
        val exact = sched.exactDepartures

        val nextMin = if (!exact.isNullOrEmpty()) {
            var candidate: Int? = null
            for (dep in exact) {
                val t = parseTimeMinutes(dep) ?: continue
                if (t >= departureTimeMinutes && (candidate == null || t < candidate)) candidate = t
            }
            candidate
        } else {
            val target = if (departureTimeMinutes <= start) start else {
                start + ceil((departureTimeMinutes - start).toDouble() / freq).toInt() * freq
            }
            if (target <= end) target else null
        }
        return if (nextMin != null) Pair(maxOf(0, nextMin - departureTimeMinutes), formatTimeMinutes(nextMin)) else Pair(0, null)
    }

    private fun shouldIncludeEdge(edge: ResortEdge, filters: RouteFilters): Boolean {
        if (edge.id in filters.closedEdgeIds) return false
        if (filters.departureTimeMinutes != null && !isEdgeOperatingAt(edge, filters.departureTimeMinutes)) return false
        if (edge.type == "bus" && !filters.includeBus) return false
        if (edge.type == "slope" && filters.maxDifficulty.isNotEmpty()) {
            val edgeIdx = DIFFICULTY_ORDER.indexOf(edge.difficulty ?: "blue").let { if (it < 0) 1 else it }
            val maxIdx = DIFFICULTY_ORDER.indexOf(filters.maxDifficulty).let { if (it < 0) 3 else it }
            if (edgeIdx > maxIdx) return false
        }
        return true
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 6371.0 * (2 * atan2(sqrt(a), sqrt(1 - a)))
    }

    private fun heuristic(resort: SkiResort, n1Id: String, n2Id: String): Double {
        val n1 = resort.getNode(n1Id) ?: return 0.0
        val n2 = resort.getNode(n2Id) ?: return 0.0
        return (haversineKm(n1.lat, n1.lng, n2.lat, n2.lng) / 20.0) * 60.0
    }

    private fun calculateEdgeCost(edge: ResortEdge, filters: RouteFilters): Double {
        val base = if (edge.duration > 0) edge.duration else 2.0
        val wait = calculateBusWait(edge, filters.departureTimeMinutes).first
        if (filters.fastestRoute) return base + wait
        val penalty = when (edge.type) {
            "slope" -> when (edge.difficulty) {
                "green" -> 0.0
                "red" -> 7.0
                "black" -> 18.0
                else -> 2.0
            }
            "bus" -> 6.0
            "walk" -> 2.0
            else -> 0.0
        }
        return base + wait + penalty
    }

    // ponytail: A* search using stdlib PriorityQueue and cached resort adjacency
    fun findPath(
        resort: SkiResort,
        startId: String,
        endId: String,
        filters: RouteFilters = RouteFilters()
    ): List<RouteStep>? {
        // Security check: validate input node existence to avoid DoS via full-graph traversal on invalid IDs
        if (resort.getNode(startId) == null || resort.getNode(endId) == null) return null
        if (startId == endId) return emptyList()

        val openSet = PriorityQueue<HeapItem>(compareBy { it.priority })
        val closedSet = HashSet<String>(resort.nodes.size)
        val cameFrom = HashMap<String, RouteStep>(resort.nodes.size)
        val gScore = HashMap<String, Double>(resort.nodes.size)

        gScore[startId] = 0.0
        openSet.add(HeapItem(startId, heuristic(resort, startId, endId)))

        while (!openSet.isEmpty()) {
            val current = openSet.poll()?.nodeId ?: break
            if (current == endId) return reconstructPath(cameFrom, current)
            if (!closedSet.add(current)) continue

            val currentG = gScore[current] ?: Double.POSITIVE_INFINITY
            val neighbors = resort.getOutgoingNeighbors(current)

            for (neighbor in neighbors) {
                if (neighbor.nodeId in closedSet) continue
                if (!shouldIncludeEdge(neighbor.edge, filters)) continue
                val tentativeG = currentG + calculateEdgeCost(neighbor.edge, filters)
                if (tentativeG < (gScore[neighbor.nodeId] ?: Double.POSITIVE_INFINITY)) {
                    val wait = if (neighbor.edge.type == "bus") calculateBusWait(neighbor.edge, filters.departureTimeMinutes) else Pair(0, null)
                    cameFrom[neighbor.nodeId] = RouteStep(current, neighbor.nodeId, neighbor.edge, neighbor.reverse, wait.first, wait.second)
                    gScore[neighbor.nodeId] = tentativeG
                    openSet.add(HeapItem(neighbor.nodeId, tentativeG + heuristic(resort, neighbor.nodeId, endId)))
                }
            }
        }
        return null
    }

    private fun reconstructPath(cameFrom: Map<String, RouteStep>, endNodeId: String): List<RouteStep> {
        val path = ArrayList<RouteStep>()
        var curr = endNodeId
        while (true) {
            val step = cameFrom[curr] ?: break
            path.add(step)
            curr = step.from
        }
        path.reverse()
        return path
    }

    // ponytail: single-pass route stats computation
    fun calculateRouteStats(path: List<RouteStep>?): RouteStats {
        if (path.isNullOrEmpty()) return RouteStats("0.0", 0, 0, 0, 0, 0, 0, 0, emptyList(), 0)

        var dist = 0.0
        var time = 0.0
        var wait = 0
        var gain = 0.0
        var loss = 0.0
        var slopes = 0
        var lifts = 0
        var buses = 0
        var walks = 0
        val difficulties = LinkedHashSet<String>()

        for (step in path) {
            val e = step.edge
            dist += e.distance
            time += e.duration + step.waitMinutes
            wait += step.waitMinutes
            when (e.type) {
                "slope" -> {
                    slopes++
                    e.difficulty?.let { difficulties.add(it) }
                    loss += abs(e.elevation ?: (e.distance * 160.0))
                }
                "lift" -> {
                    lifts++
                    gain += abs(e.elevation ?: (e.distance * 220.0))
                }
                "bus" -> buses++
                "walk" -> walks++
            }
        }

        return RouteStats(
            distanceKm = "%.1f".format(java.util.Locale.US, dist),
            timeMinutes = time.roundToInt(),
            elevationGainMeters = gain.roundToInt(),
            elevationLossMeters = loss.roundToInt(),
            slopesCount = slopes,
            liftsCount = lifts,
            busesCount = buses,
            walksCount = walks,
            difficulties = difficulties.toList(),
            totalWaitMinutes = wait
        )
    }

    // ponytail: deduplicated route alternative generation
    fun findRouteAlternatives(
        resort: SkiResort,
        startId: String,
        endId: String,
        baseFilters: RouteFilters = RouteFilters()
    ): List<RouteAlternative> {
        val routes = ArrayList<RouteAlternative>(3)
        val fastest = findPath(resort, startId, endId, baseFilters.copy(fastestRoute = true))
        if (!fastest.isNullOrEmpty()) {
            routes.add(RouteAlternative("Fastest Route", fastest, calculateRouteStats(fastest), "fastest"))

            val easiest = findPath(resort, startId, endId, baseFilters.copy(fastestRoute = false))
            if (!easiest.isNullOrEmpty() && !arePathsEqual(easiest, fastest)) {
                routes.add(RouteAlternative("Easiest / Scenic Route", easiest, calculateRouteStats(easiest), "easiest"))
            }

            if (fastest.any { it.edge.type == "bus" }) {
                val skiOnly = findPath(resort, startId, endId, baseFilters.copy(includeBus = false, fastestRoute = true))
                if (!skiOnly.isNullOrEmpty() && !arePathsEqual(skiOnly, fastest)) {
                    routes.add(RouteAlternative("Ski-Only (No Bus)", skiOnly, calculateRouteStats(skiOnly), "ski-only"))
                }
            }
        }
        return routes
    }

    private fun arePathsEqual(p1: List<RouteStep>?, p2: List<RouteStep>?): Boolean =
        p1 != null && p2 != null && p1.size == p2.size && p1.indices.all { p1[it].edge.id == p2[it].edge.id }

    fun formatTime(minutes: Int): String = when {
        minutes <= 0 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
