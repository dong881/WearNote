package com.example.wearnote.util

import com.google.api.services.drive.DriveScopes

object DriveHelper {
    /**
     * Get the Drive scope for Google Drive API
     */
    fun getDriveScope(): String {
        return DriveScopes.DRIVE_FILE
    }
}
