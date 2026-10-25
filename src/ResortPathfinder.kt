// SkiNav
// © 2026 RHerAle
// SPDX-License-Identifier: AGPL-3.0-only
package com.skitracker.nav

import java.util.PriorityQueue
import kotlin.math.*

object ResortPathfinder {
    private val DIFFICULTY_ORDER = listOf("green", "blue", "red", "black")
    private class HeapItem(val nodeId: String, val priority: Double) : Comparable<HeapItem> {
        override fun compareTo(other: HeapItem): Int = this.priority.compareTo(other.priority)
    }

    private fun difficultyRank(difficulty: String?): Int = when (difficulty) {
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
        val colon = (start until end).firstOrNull { s[it] == ':' } ?: return null
        if (colon == start || colon == end - 1) return null
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

    private fun shouldIncludeEdge(edge: ResortEdge, filters: RouteFilters, maxDifficultyRank: Int, depTime: Int?): Boolean {
        if (filters.closedEdgeIds.isNotEmpty() && edge.id in filters.closedEdgeIds) return false
        if (depTime != null && !isEdgeOperatingAt(edge, depTime)) return false
        if (edge.type == "bus" && !filters.includeBus) return false
        if (edge.type == "slope" && maxDifficultyRank < 3) {
            val edgeRank = difficultyRank(edge.difficulty)
            if (edgeRank > maxDifficultyRank) return false
        }
        return true
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val sinDLatHalf = sin(dLat / 2.0)
        val sinDLngHalf = sin(dLng / 2.0)
        val a = sinDLatHalf * sinDLatHalf + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinDLngHalf * sinDLngHalf
        return 6371.0 * (2.0 * atan2(sqrt(a), sqrt(1.0 - a)))
    }

    private fun heuristicFast(
        node: ResortNode,
        destLatRad: Double,
        destLngRad: Double,
        destCosLat: Double
    ): Double {
        val latRad = Math.toRadians(node.lat)
        val lngRad = Math.toRadians(node.lng)
        val dLat = destLatRad - latRad
        val dLng = destLngRad - lngRad
        val sinDLatHalf = sin(dLat / 2.0)
        val sinDLngHalf = sin(dLng / 2.0)
        val a = sinDLatHalf * sinDLatHalf + cos(latRad) * destCosLat * sinDLngHalf * sinDLngHalf
        val distKm = 6371.0 * (2.0 * atan2(sqrt(a), sqrt(1.0 - a)))
        return (distKm / 20.0) * 60.0
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
        if (startId == endId) return emptyList()

        val destNode = resort.getNode(endId) ?: return null
        val startNode = resort.getNode(startId) ?: return null

        val destLatRad = Math.toRadians(destNode.lat)
        val destLngRad = Math.toRadians(destNode.lng)
        val destCosLat = cos(destLatRad)

        val maxDifficultyRank = if (filters.maxDifficulty.isNotEmpty()) difficultyRank(filters.maxDifficulty) else 3
        val depTime = filters.departureTimeMinutes

        val openSet = PriorityQueue<HeapItem>()
        val closedSet = HashSet<String>(resort.nodes.size)
        val cameFrom = HashMap<String, RouteStep>(resort.nodes.size)
        val gScore = HashMap<String, Double>(resort.nodes.size)

        gScore[startId] = 0.0
        openSet.add(HeapItem(startId, heuristicFast(startNode, destLatRad, destLngRad, destCosLat)))

        while (!openSet.isEmpty()) {
            val current = openSet.poll()?.nodeId ?: break
            if (current == endId) return reconstructPath(cameFrom, current)
            if (!closedSet.add(current)) continue

            val currentG = gScore[current] ?: Double.POSITIVE_INFINITY
            val neighbors = resort.getOutgoingNeighbors(current)

            for (neighbor in neighbors) {
                if (neighbor.nodeId in closedSet) continue
                if (!shouldIncludeEdge(neighbor.edge, filters, maxDifficultyRank, depTime)) continue

                val isBus = neighbor.edge.type == "bus"
                val busWait = if (isBus) calculateBusWait(neighbor.edge, depTime) else Pair(0, null)
                val waitMin = busWait.first

                val base = if (neighbor.edge.duration > 0) neighbor.edge.duration else 2.0
                val edgeCost = if (filters.fastestRoute) {
                    base + waitMin
                } else {
                    val penalty = when (neighbor.edge.type) {
                        "slope" -> when (neighbor.edge.difficulty) {
                            "green" -> 0.0
                            "red" -> 7.0
                            "black" -> 18.0
                            else -> 2.0
                        }
                        "bus" -> 6.0
                        "walk" -> 2.0
                        else -> 0.0
                    }
                    base + waitMin + penalty
                }

                val tentativeG = currentG + edgeCost
                if (tentativeG < (gScore[neighbor.nodeId] ?: Double.POSITIVE_INFINITY)) {
                    cameFrom[neighbor.nodeId] = RouteStep(current, neighbor.nodeId, neighbor.edge, neighbor.reverse, waitMin, busWait.second)
                    gScore[neighbor.nodeId] = tentativeG
                    val neighborNode = neighbor.node ?: resort.getNode(neighbor.nodeId)
                    val h = if (neighborNode != null) heuristicFast(neighborNode, destLatRad, destLngRad, destCosLat) else 0.0
                    openSet.add(HeapItem(neighbor.nodeId, tentativeG + h))
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
