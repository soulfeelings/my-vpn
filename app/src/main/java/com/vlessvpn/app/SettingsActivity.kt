package com.vlessvpn.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Show current values as summaries
            findPreference<EditTextPreference>("server_address")?.apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<EditTextPreference>("server_port")?.apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<EditTextPreference>("uuid")?.apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<EditTextPreference>("sni")?.apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<ListPreference>("network")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<ListPreference>("security")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<ListPreference>("fingerprint")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<ListPreference>("flow")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        }
    }
}
