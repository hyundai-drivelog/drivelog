package com.drivelog.app.feature.car

import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.recording.haversineKm

/**
 * The community as a mockup: example courses in the app, and a recommendation that lives only in memory.
 * Nothing here reaches a server, so nothing here is a real person's post, and every screen says so.
 */
data class CommunityCourse(
    val id: String, val title: String, val originArea: String, val destinationName: String, val destinationAddress: String,
    val destination: MapPoint, val route: List<MapPoint>, val tags: List<String>, val minutes: Long,
    val recommendations: Int, val mine: Boolean = false,
) {
    val distanceKm get() = routeKm(route)
    fun toPlace() = NaviPlace(destinationName, destinationAddress, destination.latitude, destination.longitude)
}

/** What a recommendation would publish. Built from the trimmed route, so the screen previews exactly that. */
data class CourseDraft(
    val originArea: String, val destinationName: String, val destinationAddress: String, val destination: MapPoint,
    val route: List<MapPoint>, val minutes: Long,
)

val CourseTags = listOf("바다", "해안도로", "야경", "강변", "산책", "드라이브")

/** Starts and ends of drives are often a home or a workplace, so neither end of the route is ever shown. */
const val COURSE_TRIM_METERS = 500.0

fun routeKm(points: List<MapPoint>) = points.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) }

/** The line from exactly [meters] along it, cutting between points: recorded points can be hundreds of metres apart. */
private fun cutFront(line: List<MapPoint>, meters: Double): List<MapPoint>? {
    var walked = 0.0
    line.zipWithNext().forEachIndexed { index, (a, b) ->
        val length = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) * 1000
        if (length > 0 && walked + length > meters) {
            val along = (meters - walked) / length
            val cut = MapPoint(
                a.latitude + (b.latitude - a.latitude) * along, a.longitude + (b.longitude - a.longitude) * along,
                a.atMillis + ((b.atMillis - a.atMillis) * along).toLong(),
            )
            return listOf(cut) + line.subList(index + 1, line.size)
        }
        walked += length
    }
    return null
}

/** The route without its first [meters], or null when nothing would remain. The end is a public place and stays. */
fun trimRouteStart(points: List<MapPoint>, meters: Double = COURSE_TRIM_METERS): List<MapPoint>? = cutFront(points, meters)

/** The route without [meters] at each end, or null when nothing would remain to be a course. */
fun trimRouteEnds(points: List<MapPoint>, meters: Double = COURSE_TRIM_METERS): List<MapPoint>? {
    val withoutStart = cutFront(points, meters) ?: return null
    return cutFront(withoutStart.asReversed(), meters)?.asReversed()
}

private class Area(val name: String, val latitude: Double, val longitude: Double)

private val KnownAreas = listOf(
    Area("부산 수영구", 35.1455, 129.1131), Area("부산 해운대구", 35.1631, 129.1636), Area("부산 사하구", 35.1044, 128.9747),
    Area("부산 기장군", 35.2445, 129.2222), Area("부산 중구", 35.1064, 129.0323), Area("서울 강남구", 37.5172, 127.0473),
    Area("서울 마포구", 37.5663, 126.9019), Area("서울 중구", 37.5641, 126.9979),
)

private const val AREA_RADIUS_KM = 12.0

/** A neighbourhood-level name from a small table. A real version needs a geocoder; this one is only an example. */
fun nearestArea(point: MapPoint): String =
    KnownAreas.map { it to haversineKm(point.latitude, point.longitude, it.latitude, it.longitude) }
        .filter { it.second <= AREA_RADIUS_KM }.minByOrNull { it.second }?.first?.name ?: "출발지 주변"

fun courseDraftFrom(record: RecordV): CourseDraft? {
    val destination = record.destinationPoint() ?: return null
    val route = trimRouteEnds(record.route.toMapPoints()) ?: return null
    return CourseDraft(nearestArea(route.first()), record.placeName, record.placeAddress.orEmpty(), destination, route, record.elapsedSeconds / 60)
}

fun CourseDraft.toCourse(id: String, tags: List<String>) = CommunityCourse(
    id, "${shortName(destinationName)}까지 가는 길", originArea, destinationName, destinationAddress, destination, route, tags, minutes, 0, mine = true,
)

/** The city or province of an origin area: "부산 수영구" is listed under 부산. An unknown origin is 기타. */
fun courseRegion(originArea: String): String =
    originArea.substringBefore(' ').takeIf { city -> KnownAreas.any { it.name.startsWith("$city ") } } ?: "기타"

val CommunityCourse.region get() = courseRegion(originArea)

/** Every course reads in the same three buckets, never as a raw minute count beside a title. */
fun courseDurationLabel(minutes: Long) = when {
    minutes <= 30 -> "30분 이내"
    minutes <= 60 -> "1시간 이내"
    else -> "1시간 이상"
}

/** The driver's own come first, so a new recommendation is seen right away. */
fun filterCourses(courses: List<CommunityCourse>, region: String?, theme: String?) = courses
    .filter { (region == null || it.region == region) && (theme == null || theme in it.tags) }
    .sortedWith(compareByDescending<CommunityCourse> { it.mine }.thenByDescending { it.recommendations })

private fun line(vararg points: Pair<Double, Double>) = points.map { MapPoint(it.first, it.second) }

/** Hand-written examples: the places are real, the lines only roughly follow the roads, and the numbers are made up. */
val SampleCourses = listOf(
    CommunityCourse(
        "sample-gwangan-songjeong", "광안대교 보고 송정까지", "부산 수영구", "송정 해수욕장", "부산 해운대구", MapPoint(35.1786, 129.1996),
        line(35.1532 to 129.1186, 35.1545 to 129.1265, 35.1560 to 129.1370, 35.1585 to 129.1480, 35.1590 to 129.1600, 35.1620 to 129.1700, 35.1690 to 129.1800, 35.1740 to 129.1900, 35.1786 to 129.1990),
        listOf("바다", "해안도로"), 28, 128,
    ),
    CommunityCourse(
        "sample-dadaepo-gwangan", "부산 바다 끝에서 끝까지", "부산 사하구", "광안리 해수욕장", "부산 수영구", MapPoint(35.1532, 129.1186),
        line(35.05 to 128.966, 35.075 to 128.975, 35.085 to 128.99, 35.09 to 129.015, 35.098 to 129.035, 35.11 to 129.05, 35.125 to 129.07, 35.14 to 129.09, 35.153 to 129.118),
        listOf("바다", "야경", "드라이브"), 42, 96,
    ),
    CommunityCourse(
        "sample-haeundae-yonggungsa", "해운대에서 해동 용궁사로", "부산 해운대구", "해동 용궁사", "부산 기장군", MapPoint(35.1884, 129.2233),
        line(35.1631 to 129.1636, 35.1660 to 129.1760, 35.1720 to 129.1880, 35.1786 to 129.1996, 35.1830 to 129.2100, 35.1884 to 129.2233),
        listOf("해안도로", "산책"), 24, 54,
    ),
    CommunityCourse(
        "sample-gangnam-dumulmeori", "한강 따라 두물머리까지", "서울 강남구", "양평 두물머리", "경기 양평군", MapPoint(37.5465, 127.3183),
        line(37.517 to 127.047, 37.53 to 127.10, 37.55 to 127.16, 37.57 to 127.21, 37.56 to 127.27, 37.5465 to 127.31),
        listOf("강변", "드라이브"), 55, 73,
    ),
)
