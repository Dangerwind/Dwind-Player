package com.dwind.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dwind.R
import com.dwind.bpm.Genres

/**
 * Локализованное название стиля по его канонической метке.
 *
 * В базе и детекторе стиль хранится в виде стабильного ключа (значения из
 * [Genres]) — это не зависит от языка. Для показа переводятся только подписи.
 * Неизвестное значение возвращается как есть (например, старые записи).
 */
@Composable
fun genreLabel(genre: String?): String {
    if (genre.isNullOrBlank()) return ""
    val res = when (genre) {
        Genres.ELECTRONIC -> R.string.genre_electronic
        Genres.ROCK -> R.string.genre_rock
        Genres.ROCKNROLL -> R.string.genre_rocknroll
        Genres.BLUES -> R.string.genre_blues
        Genres.POP -> R.string.genre_pop
        Genres.HIPHOP -> R.string.genre_hiphop
        Genres.JAZZ -> R.string.genre_jazz
        Genres.DNB -> R.string.genre_dnb
        Genres.CLASSICAL -> R.string.genre_classical
        Genres.RNB -> R.string.genre_rnb
        Genres.REGGAE -> R.string.genre_reggae
        Genres.FOLK -> R.string.genre_folk
        Genres.METAL -> R.string.genre_metal
        Genres.PUNK -> R.string.genre_punk
        Genres.UNKNOWN -> R.string.genre_unknown
        Genres.OTHER -> R.string.genre_other
        else -> return genre
    }
    return stringResource(res)
}
