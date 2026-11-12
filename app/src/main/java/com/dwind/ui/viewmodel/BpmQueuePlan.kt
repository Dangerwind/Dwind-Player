package com.dwind.ui.viewmodel

import com.dwind.settings.SettingsState

/**
 * План очереди при включённом фильтре BPM.
 *
 * @param playableIds треки, которые разрешены фильтром.
 * @param queueIds новая очередь; `null` — очередь менять не нужно (нет ни одного
 *   подходящего трека, текущий должен спокойно доиграть).
 * @param preservedId трек, который играет сейчас, но уже не попадает в диапазон.
 *   Ему даём доиграть перед переходом к подходящим трекам.
 */
internal data class BpmQueuePlan(
    val playableIds: Set<String>,
    val queueIds: List<String>?,
    val preservedId: String?
)

/**
 * Строит план очереди по полному списку треков и текущим настройкам фильтра.
 * Чистая функция — используется плеером и покрыта unit-тестами.
 */
internal fun planBpmQueue(
    allIds: List<String>,
    bpmById: Map<String, Double>,
    currentId: String?,
    settings: SettingsState
): BpmQueuePlan {
    val playable = if (settings.bpmFilterEnabled) {
        allIds.filter { settings.isBpmAllowed(bpmById[it]) }
    } else {
        allIds
    }
    val playableSet = playable.toSet()

    // В диапазон не попал ни один трек: не пересобираем очередь, чтобы текущий
    // трек доиграл, и остановимся при переходе к следующему.
    if (settings.bpmFilterEnabled && playable.isEmpty()) {
        return BpmQueuePlan(
            playableIds = playableSet,
            queueIds = null,
            preservedId = null
        )
    }

    val keepCurrent = settings.bpmFilterEnabled &&
        currentId != null &&
        currentId in allIds &&
        currentId !in playableSet

    val queue = if (keepCurrent) {
        listOf(currentId) + playable
    } else {
        playable
    }
    return BpmQueuePlan(
        playableIds = playableSet,
        queueIds = queue,
        preservedId = if (keepCurrent) currentId else null
    )
}
