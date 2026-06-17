package be.heyman.android.etymoclan.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.heyman.android.etymoclan.ClanMember
import be.heyman.android.etymoclan.GemmaTamagotchiEngine
import be.heyman.android.etymoclan.crypto.Pollen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PollenLedgerViewModel : ViewModel() {
    private val engine: GemmaTamagotchiEngine?
        get() = GemmaTamagotchiEngine.getInstance()

    private val _selectedMemberId = MutableStateFlow<String?>(null)
    val selectedMemberId: StateFlow<String?> = _selectedMemberId

    val activeMember: StateFlow<ClanMember?> = combine(
        engine?.clanMembers ?: MutableStateFlow(emptyList()),
        _selectedMemberId
    ) { members, id ->
        members.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pollens: StateFlow<List<Pollen>> = combine(
        engine?.clanMembers ?: MutableStateFlow(emptyList()),
        _selectedMemberId
    ) { members, id ->
        members.find { it.id == id }?.pollens ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectMember(memberId: String?) {
        _selectedMemberId.value = memberId
    }
}
