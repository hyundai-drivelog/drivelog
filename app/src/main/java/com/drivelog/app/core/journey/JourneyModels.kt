package com.drivelog.app.core.journey

data class PlaceV(
    val id: String,
    val position: Int,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String? = null,
    val poiSubId: String? = null,
    val poiProvider: String? = null,
    val visitRecordId: String? = null,
    val visitedAtMillis: Long? = null,
)

data class GoalV(
    val id: String,
    val name: String,
    val status: String,
    val places: List<PlaceV>,
) {
    val visitedCount get() = places.count { it.visitRecordId != null }
    val achieved get() = places.isNotEmpty() && visitedCount == places.size
    val nextPlace get() = places.firstOrNull { it.visitRecordId == null }
}

object DistanceSource { const val GPS = "GPS_ESTIMATED"; const val VEHICLE = "VEHICLE"; const val NONE = "NONE" }
object RecordStatus { const val COMPLETE = "COMPLETE"; const val PARTIAL = "PARTIAL" }

/** route items are [latitude, longitude, epochMillis]; the server stores them verbatim. */
data class RecordV(
    val id: String,
    val goalId: String?,
    val placeId: String?,
    val placeName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val elapsedSeconds: Long,
    val distanceKm: Double?,
    val distanceSource: String,
    val status: String,
    val simulated: Boolean,
    val route: List<List<Double>>,
    val hasPhoto: Boolean = false,
    /** Where the driver was heading. The server ignores these until it stores them. */
    val placeAddress: String? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
)

data class JourneyV(
    val activeGoal: GoalV? = null,
    val archivedGoals: List<GoalV> = emptyList(),
    val records: List<RecordV> = emptyList(),
)

data class LinkCodeV(val pollToken: String, val code: String, val expiresAtMillis: Long, val webUrl: String?)
data class LinkPollV(val linked: Boolean, val expired: Boolean, val accessToken: String?)

/** One queued server write. Replays are safe: every endpoint is idempotent by client-generated id. */
data class OutboxOp(
    val type: String,
    val recordId: String = "",
    val record: RecordV? = null,
    val goal: GoalV? = null,
    val place: PlaceV? = null,
) {
    companion object {
        const val GOAL = "GOAL"
        const val PLACE = "PLACE"
        const val RECORD = "RECORD"
        const val VISIT = "VISIT"
        const val ARCHIVE = "ARCHIVE"
        const val DELETE_RECORD = "DELETE_RECORD"
    }
}
