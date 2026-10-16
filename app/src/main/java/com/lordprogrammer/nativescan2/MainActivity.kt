package com.lordprogrammer.nativescan2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import com.lordprogrammer.nativescan2.ui.theme.NativeScanTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NativeScanTheme {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
private fun App(vm: ScanViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    BackHandler(enabled = vm.screen != Screen.LIBRARY) {
        when (vm.screen) {
            Screen.CAMERA ->
                if (vm.pages.isNotEmpty()) vm.show(Screen.REVIEW) else vm.closeDocument()
            Screen.EDIT -> vm.show(Screen.REVIEW)
            Screen.REVIEW -> vm.closeDocument()
            Screen.LIBRARY -> Unit
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            vm.screen == Screen.LIBRARY -> LibraryScreen(vm)
            vm.screen == Screen.CAMERA && !granted ->
                Text("Se requiere permiso de cámara", Modifier.align(Alignment.Center))
            vm.screen == Screen.CAMERA -> CameraScreen(vm)
            vm.screen == Screen.EDIT -> CornerEditorScreen(vm)
            else -> ReviewScreen(vm)
        }
        if (vm.busy && vm.screen != Screen.REVIEW) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
