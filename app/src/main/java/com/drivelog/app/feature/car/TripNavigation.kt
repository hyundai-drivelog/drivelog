package com.drivelog.app.feature.car

/** Keep the original card's parent when opening a different drive inside its trip. */
data class TripReturnTarget(val screen: Screen, val recordId: String? = null, val recordParent: Screen = Screen.HISTORY)

fun resolveTripReturn(target: TripReturnTarget, recordIds: Set<String>): TripReturnTarget {
    if (target.screen != Screen.RECORD || target.recordId in recordIds) return target
    return TripReturnTarget(if (target.recordParent == Screen.TRIP) Screen.HISTORY else target.recordParent)
}
