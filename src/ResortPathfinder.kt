// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

import java.util.PriorityQueue
import kotlin.math.*

object ResortPathfinder {
    private val DIFFICULTY_ORDER = listOf("green", "blue", "red", "black")

    private class HeapItem(val nodeId: String, val priority: Double) : Comparable<HeapItem> {
        override fun compareTo(other: HeapItem): Int = priority.compareTo(other.priority)
    }

    private fun difficultyIndex(diff: String?): Int = when (diff) {
        "green" -> 0
        "blue" -> 1
        "red" -> 2
        "black" -> 3
        else -> 1
    }

    // ponytail: zero-alloc ASCII time parser
    fun parseTimeMinutes(hhmm: String): Int? = parseTimeSlice(hhmm, 0, hhmm.length)

    private fun parseTimeSlice(s: String, from: Int, to: Int): Int? {
        var start = from
        var end = to
        while (start < end && s[start] <= ' ') start++
        while (end > start && s[end - 1] <= ' ') end--
        val colon = s.indexOf(':', start)
        if (colon == -1 || colon >= end || colon == start || colon == end - 1) return null
        val hLen = colon - start
        val mLen = end - (colon + 1)
        if (hLen !in 1..2 || mLen != 2) return null
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
        return isWithinHours(edge.operatingHours, timeMinutes)
    }

    /** Si una hora cae dentro de un horario «HH:mm-HH:mm». Sin horario, o con uno que no se entiende, siempre. */
    fun isWithinHours(hours: String?, timeMinutes: Int): Boolean {
        if (hours == null) return true
        val dash = hours.indexOf('-')
        if (dash > 0) {
            val start = parseTimeSlice(hours, 0, dash)
            val end = parseTimeSlice(hours, dash + 1, hours.length)
            if (start != null && end != null) return timeMinutes in start..end
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

    private fun shouldIncludeEdge(edge: ResortEdge, filters: RouteFilters, maxDiffIdx: Int): Boolean {
        if (filters.closedEdgeIds.isNotEmpty() && edge.id in filters.closedEdgeIds) return false
        if (filters.departureTimeMinutes != null && !isEdgeOperatingAt(edge, filters.departureTimeMinutes)) return false
        if (edge.type == "bus" && !filters.includeBus) return false
        if (edge.type == "slope" && filters.maxDifficulty.isNotEmpty()) {
            val edgeIdx = difficultyIndex(edge.difficulty)
            if (edgeIdx > maxDiffIdx) return false
        }
        return true
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val sLat = sin(dLat * 0.5)
        val sLng = sin(dLng * 0.5)
        val a = sLat * sLat + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sLng * sLng
        return 6371.0 * (2 * atan2(sqrt(a), sqrt(1 - a)))
    }

    // equirectangular distance heuristic replaces expensive Haversine trigonometric calls
    private fun heuristicNode(resort: SkiResort, n1Id: String, endLatRad: Double, endLngRad: Double, cosEndLat: Double): Double {
        val n1 = resort.getNode(n1Id) ?: return 0.0
        val n1LatRad = Math.toRadians(n1.lat)
        val x = (endLngRad - Math.toRadians(n1.lng)) * cosEndLat
        val y = endLatRad - n1LatRad
        // 19113.0 = Earth radius (6371 km) * (60 min / 20 km/h)
        return 19113.0 * sqrt(x * x + y * y)
    }

    private fun calculateEdgeCost(edge: ResortEdge, filters: RouteFilters, busWait: Int): Double {
        val base = if (edge.duration > 0) edge.duration else 2.0
        if (filters.fastestRoute) return base + busWait
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
        return base + busWait + penalty
    }

    // ponytail: A* search using precomputed destination coordinates, direct Comparable heap, and fast edge filters
    fun findPath(
        resort: SkiResort,
        startId: String,
        endId: String,
        filters: RouteFilters = RouteFilters()
    ): List<RouteStep>? {
        if (resort.getNode(startId) == null || resort.getNode(endId) == null) return null
        if (startId == endId) return emptyList()

        val endNode = resort.getNode(endId) ?: return null
        val endLatRad = Math.toRadians(endNode.lat)
        val endLngRad = Math.toRadians(endNode.lng)
        val cosEndLat = cos(endLatRad)
        val maxDiffIdx = if (filters.maxDifficulty.isNotEmpty()) difficultyIndex(filters.maxDifficulty) else 3

        val openSet = PriorityQueue<HeapItem>()
        val closedSet = HashSet<String>(resort.nodes.size)
        val cameFrom = HashMap<String, RouteStep>(resort.nodes.size)
        val gScore = HashMap<String, Double>(resort.nodes.size)

        gScore[startId] = 0.0
        openSet.add(HeapItem(startId, heuristicNode(resort, startId, endLatRad, endLngRad, cosEndLat)))

        while (!openSet.isEmpty()) {
            val current = openSet.poll()?.nodeId ?: break
            if (current == endId) return reconstructPath(cameFrom, current)
            if (!closedSet.add(current)) continue

            val currentG = gScore[current] ?: Double.POSITIVE_INFINITY
            val neighbors = resort.getOutgoingNeighbors(current)

            for (neighbor in neighbors) {
                if (neighbor.nodeId in closedSet) continue
                if (!shouldIncludeEdge(neighbor.edge, filters, maxDiffIdx)) continue

                var busWait = 0
                var nextDepartureTime: String? = null
                if (neighbor.edge.type == "bus") {
                    val waitPair = calculateBusWait(neighbor.edge, filters.departureTimeMinutes)
                    busWait = waitPair.first
                    nextDepartureTime = waitPair.second
                }

                val tentativeG = currentG + calculateEdgeCost(neighbor.edge, filters, busWait)
                if (tentativeG < (gScore[neighbor.nodeId] ?: Double.POSITIVE_INFINITY)) {
                    cameFrom[neighbor.nodeId] = RouteStep(current, neighbor.nodeId, neighbor.edge, neighbor.reverse, busWait, nextDepartureTime)
                    gScore[neighbor.nodeId] = tentativeG
                    openSet.add(HeapItem(neighbor.nodeId, tentativeG + heuristicNode(resort, neighbor.nodeId, endLatRad, endLngRad, cosEndLat)))
                }
            }
        }
        return null
    }

    private fun reconstructPath(cameFrom: Map<String, RouteStep>, current: String): List<RouteStep> {
        val path = ArrayList<RouteStep>()
        var curr = current
        while (cameFrom.containsKey(curr)) {
            val step = cameFrom[curr]!!
            path.add(step)
            curr = step.from
        }
        path.reverse()
        return path
    }

    // ponytail: single-pass route statistics calculation
    fun calculateRouteStats(path: List<RouteStep>): RouteStats {
        var dist = 0.0
        var time = 0.0
        var wait = 0.0
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
            totalWaitMinutes = wait.toInt()
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

    private fun arePathsEqual(p1: List<RouteStep>?, p2: List<RouteStep>?): Boolean {
        if (p1 == null || p2 == null || p1.size != p2.size) return false
        for (i in p1.indices) {
            if (p1[i].edge.id != p2[i].edge.id) return false
        }
        return true
    }

    fun formatTime(minutes: Int): String = when {
        minutes <= 0 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
