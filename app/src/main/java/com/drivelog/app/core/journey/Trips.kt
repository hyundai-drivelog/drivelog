package com.drivelog.app.core.journey

/** A local, user-curated collection. Drives stay independent; a trip never fabricates a new drive. */
data class TripV(
    val id: String,
    val title: String,
    val recordIds: List<String>,
    val coverRecordId: String?,
    val createdAtMillis: Long,
)

const val TRIP_TITLE_LIMIT = 40

fun tripRecords(trip: TripV, records: List<RecordV>): List<RecordV> {
    val ids = trip.recordIds.toSet()
    return records.filter { it.id in ids }.distinctBy { it.id }
        .sortedWith(compareBy<RecordV> { it.startedAtMillis }.thenBy { it.id })
}

data class TripSummary(
    val records: List<RecordV>,
    val distanceKm: Double,
    val unknownDistances: Int,
    val recordingSeconds: Long,
    val restSeconds: Long,
    val simulated: Boolean,
) {
    val firstAtMillis get() = records.firstOrNull()?.startedAtMillis
    val lastAtMillis get() = records.maxOfOrNull { it.endedAtMillis }
}

fun summarizeTrip(trip: TripV, records: List<RecordV>): TripSummary {
    val selected = tripRecords(trip, records)
    val distances = selected.mapNotNull { it.distanceKm?.takeIf { km -> km.isFinite() && km >= 0 } }
    return TripSummary(selected, distances.sum(), selected.size - distances.size,
        selected.sumOf { it.elapsedSeconds.coerceAtLeast(0) }, selected.sumOf { it.restSeconds() },
        selected.any { it.simulated })
}

/** Validate at commit time too: a sync or deletion may have changed records since the editor opened. */
fun prepareTrip(
    id: String, title: String, recordIds: List<String>, coverRecordId: String?, now: Long,
    trips: List<TripV>, records: List<RecordV>,
): TripV? {
    val name = title.trim().replace(Regex("\\s+"), " ")
    val selected = recordIds.distinct()
    val available = records.map { it.id }.toSet()
    val ownedElsewhere = trips.filterNot { it.id == id }.flatMap { it.recordIds }.toSet()
    if (name.isBlank() || name.length > TRIP_TITLE_LIMIT || selected.isEmpty() ||
        selected.any { it !in available || it in ownedElsewhere }) return null
    return TripV(id, name, selected, coverRecordId?.takeIf { it in selected } ?: selected.first(),
        trips.firstOrNull { it.id == id }?.createdAtMillis ?: now)
}

/** Removing a drive changes only its references. An empty named trip remains available for more drives. */
fun retainTripRecords(trips: List<TripV>, records: List<RecordV>): List<TripV> {
    val available = records.map { it.id }.toSet()
    return trips.map { trip ->
        val ids = trip.recordIds.distinct().filter { it in available }
        trip.copy(recordIds = ids, coverRecordId = trip.coverRecordId?.takeIf { it in ids } ?: ids.firstOrNull())
    }
}
