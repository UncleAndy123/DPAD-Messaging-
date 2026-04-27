package com.dpad.messaging.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val DELIVERY_REPORTS_KEY = booleanPreferencesKey("delivery_reports")
        val USE_CUSTOM_COLORS_KEY = booleanPreferencesKey("custom_colors")
        val THEME_MODE_KEY = intPreferencesKey("theme_mode") // 0: System, 1: Light, 2: Dark, 3: Amoled
        val PRIMARY_COLOR_KEY = intPreferencesKey("primary_color") // Storing hex color as int
        val FONT_SIZE_KEY = floatPreferencesKey("font_size_scale")
        val ALWAYS_SHOW_TIMESTAMPS_KEY = booleanPreferencesKey("always_show_timestamps")
        val PREFER_SIM_2_KEY = booleanPreferencesKey("prefer_sim_2")
    }

    val deliveryReports: Flow<Boolean> = context.dataStore.data.map { it[DELIVERY_REPORTS_KEY] ?: false }
    val useCustomColors: Flow<Boolean> = context.dataStore.data.map { it[USE_CUSTOM_COLORS_KEY] ?: false }
    val themeMode: Flow<Int> = context.dataStore.data.map { it[THEME_MODE_KEY] ?: 0 }
    val primaryColor: Flow<Int> = context.dataStore.data.map { it[PRIMARY_COLOR_KEY] ?: 0xFF1565C0.toInt() }
    val fontSizeScale: Flow<Float> = context.dataStore.data.map { it[FONT_SIZE_KEY] ?: 1.0f }
    val alwaysShowTimestamps: Flow<Boolean> = context.dataStore.data.map { it[ALWAYS_SHOW_TIMESTAMPS_KEY] ?: false }
    val preferSim2: Flow<Boolean> = context.dataStore.data.map { it[PREFER_SIM_2_KEY] ?: false }

    suspend fun setDeliveryReports(enabled: Boolean) = context.dataStore.edit { it[DELIVERY_REPORTS_KEY] = enabled }
    suspend fun setUseCustomColors(enabled: Boolean) = context.dataStore.edit { it[USE_CUSTOM_COLORS_KEY] = enabled }
    suspend fun setThemeMode(mode: Int) = context.dataStore.edit { it[THEME_MODE_KEY] = mode }
    suspend fun setPrimaryColor(color: Int) = context.dataStore.edit { it[PRIMARY_COLOR_KEY] = color }
    suspend fun setFontSizeScale(scale: Float) = context.dataStore.edit { it[FONT_SIZE_KEY] = scale }
    suspend fun setAlwaysShowTimestamps(enabled: Boolean) = context.dataStore.edit { it[ALWAYS_SHOW_TIMESTAMPS_KEY] = enabled }
    suspend fun setPreferSim2(prefer: Boolean) = context.dataStore.edit { it[PREFER_SIM_2_KEY] = prefer }
}
