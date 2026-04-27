package com.dpad.messaging.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpad.messaging.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    val deliveryReports = repo.deliveryReports.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val useCustomColors = repo.useCustomColors.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val themeMode = repo.themeMode.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val primaryColor = repo.primaryColor.stateIn(viewModelScope, SharingStarted.Lazily, 0xFF1565C0.toInt())
    val fontSizeScale = repo.fontSizeScale.stateIn(viewModelScope, SharingStarted.Lazily, 1.0f)
    val alwaysShowTimestamps = repo.alwaysShowTimestamps.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val preferSim2 = repo.preferSim2.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun toggleDeliveryReports(enabled: Boolean) = viewModelScope.launch { repo.setDeliveryReports(enabled) }
    fun toggleCustomColors(enabled: Boolean) = viewModelScope.launch { repo.setUseCustomColors(enabled) }
    fun setThemeMode(mode: Int) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setPrimaryColor(color: Int) = viewModelScope.launch { repo.setPrimaryColor(color) }
    fun setFontSize(scale: Float) = viewModelScope.launch { repo.setFontSizeScale(scale) }
    fun toggleTimestamps(enabled: Boolean) = viewModelScope.launch { repo.setAlwaysShowTimestamps(enabled) }
    fun toggleSimPreference(preferSim2: Boolean) = viewModelScope.launch { repo.setPreferSim2(preferSim2) }
}
