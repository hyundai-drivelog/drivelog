package com.drivelog.app.core.journey

import java.security.MessageDigest

/** A token is never used as a file name or written to the JSON snapshot. */
internal fun journeyProfileId(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A complete server snapshot must not overwrite edits made while its HTTP request was in flight. */
internal fun reconcileJourney(remote: JourneyV, pending: List<OutboxOp>): JourneyV {
    val records = remote.records.associateByTo(linkedMapOf()) { it.id }
    pending.forEach { op ->
        when (op.type) {
            OutboxOp.RECORD -> op.record?.let { records[it.id] = it }
            OutboxOp.DELETE_RECORD -> records.remove(op.recordId)
        }
    }
    return remote.copy(records = records.values.sortedByDescending { it.startedAtMillis })
}

/** Importing unlinked history requires a separate, unchecked-by-default user choice. */
internal fun linkHistory(profile: StoreState, local: StoreState, includeLocalRecords: Boolean): StoreState {
    if (!includeLocalRecords || local.token != null) return profile
    val imported = local.journey.records.filter { record -> profile.journey.records.none { it.id == record.id } }
        .map { it.copy(goalId = null, placeId = null) }
    val records = profile.journey.records + imported
    val claimed = profile.trips.flatMap { it.recordIds }.toMutableSet()
    val existingTripIds = profile.trips.map { it.id }.toSet()
    val trips = retainTripRecords(local.trips, records).filterNot { it.id in existingTripIds }.mapNotNull { trip ->
        val ids = trip.recordIds.filterNot { it in claimed }
        if (ids.isEmpty()) null else {
            claimed.addAll(ids)
            trip.copy(recordIds = ids, coverRecordId = trip.coverRecordId?.takeIf { it in ids } ?: ids.first())
        }
    }
    return profile.copy(
        journey = profile.journey.copy(records = records.sortedByDescending { it.startedAtMillis }),
        outbox = profile.outbox + imported.map { OutboxOp(OutboxOp.RECORD, it.id, it) },
        trips = profile.trips + trips,
    )
}
