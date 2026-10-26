package com.example.dpadplayer

import android.app.Application
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DPadApplication : Application() {
    companion object {
        private const val PREF_KEEP_SERVICE_WHEN_PAUSED = "keep_service_when_paused"
        private const val PREF_KEEP_SERVICE_WHEN_PAUSED_MIGRATED = "keep_service_when_paused_default_migrated"
    }
    // Application-level coroutine scope for background work that should survive
    // UI/ViewModel lifecycles (cancellation only when the process is terminated).
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()

        // One-time migration from the old default (true). On API 23 that default
        // kept a foreground notification pinned even after playback was paused.
        // Users can opt back in after this migration via Settings.
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (!preferences.getBoolean(PREF_KEEP_SERVICE_WHEN_PAUSED_MIGRATED, false)) {
            preferences.edit()
                .putBoolean(PREF_KEEP_SERVICE_WHEN_PAUSED, false)
                .putBoolean(PREF_KEEP_SERVICE_WHEN_PAUSED_MIGRATED, true)
                .apply()
        }
        // Use the application scope for MediaStoreScanner background writes so
        // cached album data persists independently of UI lifecycles.
        MediaStoreScanner.scope = applicationScope
    }
}
