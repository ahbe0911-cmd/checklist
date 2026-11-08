package com.ahbe.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/** This application contains only the self-contained alarm feature. */
class MainActivity : Activity() {
    private var settingsVisible = false
    private var refreshSettingsAfterReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showHome()
    }

    fun markSettingsRefresh() { refreshSettingsAfterReturn = true }

    override fun onResume() {
        super.onResume()
        if (refreshSettingsAfterReturn && settingsVisible) {
            refreshSettingsAfterReturn = false
            showSettings()
        }
    }

    fun showHome() {
        settingsVisible = false
        AlarmUi.show(this)
    }

    fun showSettings() {
        settingsVisible = true
        AlarmSettingsUi.show(this)
    }

    @Deprecated("Android back navigation compatibility")
    override fun onBackPressed() {
        if (settingsVisible) showHome() else super.onBackPressed()
    }

    @Deprecated("Android document picker callback compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AlarmUi.PICK_AUDIO && resultCode == RESULT_OK) {
            AlarmUi.receivePickedAudio(this, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        when(requestCode) {
            AlarmUi.LOCATION_PERMISSION -> AlarmUi.onLocationPermissionResult(granted)
            AlarmUi.MIC_PERMISSION -> AlarmUi.onMicPermissionResult(granted)
            AlarmSettingsUi.NOTIFICATION_PERMISSION -> if (settingsVisible) showSettings()
            AlarmSettingsUi.SETTINGS_LOCATION_PERMISSION,
            AlarmSettingsUi.SETTINGS_MIC_PERMISSION -> if (settingsVisible) showSettings()
        }
    }
}
