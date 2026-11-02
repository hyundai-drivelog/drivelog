package com.drivelog.app.core.drive

import android.content.Context

class PleosDriveDataSource(@Suppress("UNUSED_PARAMETER") context: Context) : DriveDataSource {
    override suspend fun capture(): DrivePayload {
        throw PleosUnavailableException("SDK 미포함 빌드 · 차량 데이터 취득 불가")
    }
}
