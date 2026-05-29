package co.ke.kumea.ui.screen.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.repository.FarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FarmListViewModel @Inject constructor(
    private val repository: FarmRepository,
) : ViewModel() {

    val farms: StateFlow<List<FarmEntity>> = repository.getAllActive()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
