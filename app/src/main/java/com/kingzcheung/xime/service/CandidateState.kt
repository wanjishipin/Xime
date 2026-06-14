package com.kingzcheung.xime.service

data class CandidateState(
    val candidates: Array<String> = emptyArray(),
    val candidateComments: Array<String> = emptyArray(),
    val inputText: String = "",
    val isComposing: Boolean = false,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val associationCandidates: Array<String> = emptyArray(),
    val pendingEnglishText: String = "",
    val isShowingRecentClipboard: Boolean = false
)
