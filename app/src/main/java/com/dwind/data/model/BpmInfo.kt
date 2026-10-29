package com.dwind.data.model

/**
 * Метаданные BPM дорожки для отображения в интерфейсе:
 * значение, уверенность (0..1), жанр и признак ручного ввода.
 */
data class BpmInfo(
    val bpm: Double,
    val confidence: Double? = null,
    val genre: String? = null,
    val manual: Boolean = false
)
