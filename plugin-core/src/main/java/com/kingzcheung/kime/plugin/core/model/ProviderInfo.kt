package com.kingzcheung.kime.plugin.core.model

data class ProviderInfo(
    val className: String,
    val authorities: List<String>,
    val exported: Boolean = false,
    val enabled: Boolean = true
)