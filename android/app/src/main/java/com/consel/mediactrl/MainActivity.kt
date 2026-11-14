package com.consel.mediactrl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        discovery = Discovery(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MediaTheme { AppRoot(discovery) } }
    }

    override fun onStart() {
        super.onStart()
        Remote.setUiVisible(true)
        discovery.start()
    }

    override fun onStop() {
        Remote.setUiVisible(false)
        discovery.stop()
        super.onStop()
    }

    /** With the app open, the volume keys always drive the computer (if enabled). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = Remote.state.value
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            s.link == Link.Connected && s.sysVolume != null && Remote.volumeKeys.value
        ) {
            Remote.stepVolume(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) ControlService.VOLUME_STEP
                              else -ControlService.VOLUME_STEP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Remote.state.value.link == Link.Connected && Remote.volumeKeys.value
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}
