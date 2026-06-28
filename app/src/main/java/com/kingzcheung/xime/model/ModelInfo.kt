package com.kingzcheung.xime.model

enum class ModelCategory {
    PREDICTION,
    ASR,
    PUNCTUATION,
    HANDWRITING,
    OTHER
}

data class ModelFile(
    val name: String,
    val downloadUrl: String
)

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: ModelCategory,
    val storageDir: String,
    val files: List<ModelFile>,
    val archiveUrl: String? = null,
    val size: String = ""
)

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
    data object Complete : ModelDownloadState()
}
