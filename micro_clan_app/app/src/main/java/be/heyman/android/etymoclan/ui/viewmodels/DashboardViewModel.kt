package be.heyman.android.etymoclan.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.heyman.android.etymoclan.ClanMember
import be.heyman.android.etymoclan.GemmaTamagotchiEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val engine: GemmaTamagotchiEngine?
        get() = GemmaTamagotchiEngine.getInstance()

    val members: StateFlow<List<ClanMember>> = engine?.clanMembers ?: MutableStateFlow(emptyList())

    fun simulateScanTerreau() {
        viewModelScope.launch {
            engine?.onNfcPayloadReceived("Type:Terreau;Origin:Sarcomusation;Lot:MS_2026")
        }
    }

    fun simulateScanTomate() {
        viewModelScope.launch {
            engine?.onNfcPayloadReceived("Type:Tomate;Origin:Pépinière Locale;Lot:T_2026")
        }
    }

    fun simulateScanBasilic() {
        viewModelScope.launch {
            engine?.onNfcPayloadReceived("Type:Basilic;Origin:Serre Organique;Lot:B_2026")
        }
    }

    fun simulateScanMoinette(context: Context) {
        viewModelScope.launch {
            try {
                val jsonString = context.assets.open("schema/5410702000133.json").bufferedReader().use { it.readText() }
                engine?.onNfcPayloadReceived(jsonString)
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to load Moinette mock JSON from assets", e)
            }
        }
    }
}
