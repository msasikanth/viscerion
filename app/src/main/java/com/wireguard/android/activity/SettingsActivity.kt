/*
 * Copyright © 2017-2018 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.SparseArray
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.transaction
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.fragment.AppListDialogFragment
import com.wireguard.android.util.asString
import java.util.ArrayList
import java.util.Arrays

/**
 * Interface for changing application-global persistent settings.
 */

class SettingsActivity : AppCompatActivity() {
    private val permissionRequestCallbacks = SparseArray<(permissions: Array<String>, granted: IntArray) -> Unit>()
    private var permissionRequestCounter: Int = 0

    fun ensurePermissions(
        permissions: Array<String>,
        function: (permissions: Array<String>, granted: IntArray) -> Unit
    ) {
        val needPermissions = ArrayList<String>(permissions.size)
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                needPermissions.add(permission)
        }
        if (needPermissions.isEmpty()) {
            val granted = IntArray(permissions.size)
            Arrays.fill(granted, PackageManager.PERMISSION_GRANTED)
            function(permissions, granted)
            return
        }
        val idx = permissionRequestCounter++
        permissionRequestCallbacks.put(idx, function)
        ActivityCompat.requestPermissions(
            this,
            needPermissions.toTypedArray(), idx
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.transaction {
                add(android.R.id.content, SettingsFragment())
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val f = permissionRequestCallbacks.get(requestCode)
        if (f != null) {
            permissionRequestCallbacks.remove(requestCode)
            f(permissions, grantResults)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), AppListDialogFragment.AppExclusionListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            addPreferencesFromResource(R.xml.preferences)
            val screen = preferenceScreen
            val wgQuickOnlyPrefs = arrayOf(
                preferenceScreen.findPreference<Preference>("tools_installer"),
                preferenceScreen.findPreference<CheckBoxPreference>("restore_on_boot")
            )
            val debugOnlyPrefs = arrayOf(
                preferenceScreen.findPreference<SwitchPreferenceCompat>("force_userspace_backend")
            )
            val wgOnlyPrefs = arrayOf(
                preferenceScreen.findPreference<CheckBoxPreference>("whitelist_exclusions")
            )
            val exclusionsPref = preferenceManager.findPreference<Preference>("global_exclusions")
            val integrationSecretPref =
                preferenceManager.findPreference<EditTextPreference>("intent_integration_secret")
            for (pref in wgQuickOnlyPrefs + wgOnlyPrefs + debugOnlyPrefs)
                pref.isVisible = false

            if (BuildConfig.DEBUG && Application.supportsKernelModule)
                for (pref in debugOnlyPrefs)
                    pref.isVisible = true

            Application.backendAsync.thenAccept { backend ->
                for (pref in wgQuickOnlyPrefs) {
                    if (backend is WgQuickBackend)
                        pref.isVisible = true
                    else
                        screen.removePreference(pref)
                }
                for (pref in wgOnlyPrefs) {
                    if (backend is GoBackend)
                        pref.isVisible = true
                    else
                        screen.removePreference(pref)
                }
            }
            exclusionsPref.setOnPreferenceClickListener {
                val excludedApps = ArrayList<String>(Application.appPrefs.exclusionsArray)
                val fragment = AppListDialogFragment.newInstance(excludedApps, true, this)
                fragment.show(requireFragmentManager(), null)
                true
            }
            integrationSecretPref.setSummaryProvider { preference ->
                if (Application.appPrefs.allowTaskerIntegration &&
                    preference.isEnabled &&
                    Application.appPrefs.taskerIntegrationSecret.isEmpty()
                )
                    getString(R.string.tasker_integration_summary_empty_secret)
                else
                    getString(R.string.tasker_integration_secret_summary)
            }
        }

        override fun onExcludedAppsSelected(excludedApps: List<String>) {
            if (excludedApps.asString() == Application.appPrefs.exclusions) return
            Application.tunnelManager.getTunnels().thenAccept { tunnels ->
                if (excludedApps.isNotEmpty()) {
                    tunnels.forEach { tunnel ->
                        val oldConfig = tunnel.getConfig()
                        oldConfig?.let {
                            Application.appPrefs.exclusionsArray.forEach { exclusion ->
                                it.`interface`.excludedApplications.remove(
                                    exclusion
                                )
                            }
                            it.`interface`.excludedApplications.addAll(excludedApps.toCollection(ArrayList()))
                            tunnel.setConfig(it)
                        }
                    }
                    Application.appPrefs.exclusions = excludedApps.asString()
                } else {
                    tunnels.forEach { tunnel ->
                        Application.appPrefs.exclusionsArray.forEach { exclusion ->
                            tunnel.getConfig()?.`interface`?.excludedApplications?.remove(exclusion)
                        }
                    }
                    Application.appPrefs.exclusions = ""
                }
            }
        }
    }
}
