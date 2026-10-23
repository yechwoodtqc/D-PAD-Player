package com.example.dpadplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen — launched from the three-dot menu in LibraryFragment.
 * Wraps PreferenceFragmentCompat in a custom layout that has a back-arrow header.
 *
 * Preferences persisted automatically by the Preference library.
 * Changes take effect immediately:
 *   - Theme: switches night mode on the fly via AppCompatDelegate
 *   - Seek step: read by PlayerFragment at scrub time from SharedPreferences
 *   - Sort order: triggers a track reload in the ViewModel
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: MusicViewModel by activityViewModels()

    private val createLogcatDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) exportLogcat(uri)
        }

    private fun exportLogcat(uri: android.net.Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder(
                        "logcat", "-d", "-v", "threadtime", "-t", "4000"
                    ).redirectErrorStream(true).start()
                    val logText = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val exitCode = process.waitFor()
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(logText.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file")
                    if (exitCode == 0) null else "logcat exited with code $exitCode. The output was saved; it may contain an error message."
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            val message = if (result == null) {
                getString(R.string.logcat_export_success)
            } else {
                getString(R.string.logcat_export_failed, result)
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
        }
    }

    // ── PreferenceFragmentCompat contract ─────────────────────────────────────

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("export_logcat")?.setOnPreferenceClickListener {
            createLogcatDocument.launch("dpad-logcat.txt")
            true
        }

        // Theme — apply immediately on change
        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, newValue ->
            applyTheme(newValue as String)
            true
        }

        // Accent color — recreate activity to re-apply setTheme()
        findPreference<ListPreference>("accent")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<ListPreference>("display_size")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { activity?.recreate() }
            true
        }

        // Sort order — reload tracks with new ordering
        findPreference<ListPreference>("sort_order")?.setOnPreferenceChangeListener { _, newValue ->
            viewModel.loadTracks(newValue as String)
            true
        }

        // Seek / skip duration — PlayerFragment and PlaybackService read it at runtime
    }

    // ── View wrapping — inject our header above the preference list ───────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate our wrapper layout
        val wrapper = inflater.inflate(R.layout.fragment_settings, container, false)

        // Let PreferenceFragmentCompat inflate its own list view
        val prefView = super.onCreateView(inflater, container, savedInstanceState)

        // Place the pref view inside our container slot
        val prefContainer = wrapper.findViewById<ViewGroup>(R.id.settings_prefs_container)
        prefContainer.addView(prefView)

        // Back button
        val btnBack = wrapper.findViewById<MaterialButton>(R.id.btn_settings_back)
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        val btnRescan = wrapper.findViewById<MaterialButton>(R.id.btn_settings_rescan)
        btnRescan.setOnClickListener { (activity as? MainActivity)?.rescanLibrary() }

        listOf(btnBack, btnRescan).forEach { button ->
            applyPlayerControlFocusBackground(button)
            val focusHandler = materialButtonFocusChangeHandler(button)
            button.setOnFocusChangeListener { focusedView, hasFocus ->
                focusedView.isSelected = hasFocus
                focusHandler(focusedView, hasFocus)
            }
            button.setOnKeyListener { focusedView, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstPreference()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        focusedView.performClick()
                        true
                    }
                    else -> false
                }
            }
        }

        return wrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                configurePreferenceFocus(child)
            }

            override fun onChildViewDetachedFromWindow(child: View) = Unit
        })
        for (index in 0 until listView.childCount) {
            configurePreferenceFocus(listView.getChildAt(index))
        }

        listView.post { focusFirstPreference() }
    }

    private fun configurePreferenceFocus(row: View) {
        row.isFocusable = true
        row.isFocusableInTouchMode = false
        applyItemFocusBackground(row)
        row.setOnFocusChangeListener { focusedView, hasFocus -> focusedView.isSelected = hasFocus }
        row.setOnKeyListener { focusedView, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (listView.getChildAdapterPosition(focusedView) == 0) {
                        view?.findViewById<View>(R.id.btn_settings_back)?.requestFocus()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    focusedView.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun focusFirstPreference() {
        val firstRow = listView.findViewHolderForAdapterPosition(0)?.itemView
        if (firstRow?.requestFocus() != true) {
            listView.post {
                listView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: listView.requestFocus()
            }
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    companion object {
        fun displayScale(value: String): Float = when (value) {
            "small" -> 0.9f
            "large" -> 1.15f
            else -> 1.0f
        }

        fun applyTheme(value: String) {
            val mode = when (value) {
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun accentThemeRes(accent: String): Int = when (accent) {
            "purple"     -> R.style.Theme_DPadPlayer_Purple
            "blue"       -> R.style.Theme_DPadPlayer_Blue
            "teal"       -> R.style.Theme_DPadPlayer_Teal
            "green"      -> R.style.Theme_DPadPlayer_Green
            "red"        -> R.style.Theme_DPadPlayer_Red
            "orange"     -> R.style.Theme_DPadPlayer_Orange
            "yellow"     -> R.style.Theme_DPadPlayer_Yellow
            "grey"       -> R.style.Theme_DPadPlayer_Grey
            "retro"      -> R.style.Theme_DPadPlayer_Retro
            "deep_purple" -> R.style.Theme_DPadPlayer_DeepPurple
            else           -> R.style.Theme_DPadPlayer
        }
    }
}
