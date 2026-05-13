package com.kingzcheung.xime.settings

data class SchemaInfo(
    val schemaId: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val isDownloaded: Boolean = false,
    val needsUpdate: Boolean = false
)