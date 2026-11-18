package com.dwind.ui.model

data class FolderNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val trackCount: Int = 0,
    /** Узел — корень накопителя (внутренняя память или SD-карта). */
    val isVolume: Boolean = false
)
