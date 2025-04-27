package com.example.wearnote.model

import java.util.Date

/**
 * Represents a file upload that is pending or failed and needs to be retried
 */
data class PendingUpload(
    val fileName: String,
    val filePath: String,
    val uploadType: UploadType,
    val fileId: String? = null,
    val failureReason: String? = null,
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val displayName: String = fileName // Add the displayName property with fileName as default
) {
    enum class UploadType {
        DRIVE,
        AI_PROCESSING,
        BOTH
    }
}
