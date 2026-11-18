package com.dwind.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dwind.R
import com.dwind.ui.model.FolderNode
import java.io.File

/** Название накопителя для показа пользователю. */
@Composable
fun storageLabel(path: String): String =
    if (path.startsWith("/storage/emulated/")) stringResource(R.string.storage_internal)
    else stringResource(R.string.storage_sd_card)

/**
 * Подпись папки в списке: накопители и корень показываются понятными
 * названиями, обычные папки — своим именем в файловой системе.
 */
@Composable
fun folderLabel(folder: FolderNode): String = when {
    folder.path == "/" -> stringResource(R.string.all_files)
    folder.isVolume -> storageLabel(folder.path)
    else -> folder.name
}

/** Заголовок экрана для текущего пути. */
@Composable
fun folderPathLabel(path: String, volumePaths: Set<String>, rootLabel: String): String = when {
    path.isEmpty() -> rootLabel
    path in volumePaths -> storageLabel(path)
    else -> File(path).name.ifEmpty { path }
}
