package com.dwind.ui.model

data class FolderNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val trackCount: Int = 0
)
