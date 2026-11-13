package com.example.fm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration

private const val LONG_PRESS_MS = 250L

class MainActivity : ComponentActivity() {
    private val vm: FmViewModel by viewModels()
    private var granted by mutableStateOf(false)

    private val legacyPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAccess() }

    private fun checkAccess() {
        granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        DLog.i("storage access granted=$granted")
        if (granted) {
            DLog.retarget() // move the log to /sdcard/FileManager/fm.log once allowed
            vm.panes.indices.forEach { vm.refresh(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAccess()
        setContent {
            // Long-press (select / context menu) fires sooner than the system default (~500 ms)
            val base = LocalViewConfiguration.current
            val quick = remember(base) {
                object : ViewConfiguration by base {
                    override val longPressTimeoutMillis: Long get() = LONG_PRESS_MS
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides quick) {
            FmTheme {
                if (granted) FmRoot(vm) else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Cần quyền truy cập toàn bộ bộ nhớ", color = MaterialTheme.colorScheme.onSurface)
                        Button(onClick = ::requestAccess) { Text("Cấp quyền") }
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccess()
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // All-files access covers everything; launching a runtime dialog too would fight with this screen
            if (!Environment.isExternalStorageManager()) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure {
                    DLog.w("app-specific all-files screen missing", it)
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            legacyPerm.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }
}
