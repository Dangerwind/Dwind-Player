package com.dwind.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dwind.data.model.AudioItem
import com.dwind.data.repo.AudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FoldersUiState(
    val items: List<AudioItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false
)

class FoldersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AudioRepository(app)
    private val _uiState = MutableStateFlow(FoldersUiState())
    val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()

    fun setPermission(granted: Boolean) {
        _uiState.value = _uiState.value.copy(hasPermission = granted)
        if (granted) loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val items = repo.getAllAudio()
            _uiState.value = _uiState.value.copy(items = items, isLoading = false)
        }
    }
}
