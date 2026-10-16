package com.lordprogrammer.nativescan2

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val STABLE_EPS = 0.02f
private const val STABLE_MS = 700L
private val Accent = Color(0xFF00E5A0)

@Composable
fun CameraScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val scope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val capturing = remember { AtomicBoolean(false) }
    var live by remember { mutableStateOf<FloatArray?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    val capture = rememberUpdatedState {
        if (capturing.compareAndSet(false, true)) {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val rotation = image.imageInfo.rotationDegrees
                        val buf = image.planes[0].buffer
                        val jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
                        image.close()
                        scope.launch {
                            vm.addCapture(jpeg, rotation)
                            capturing.set(false)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing.set(false)
                        vm.message = exception.message
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        var lastCorners: FloatArray? = null
        var stableSince = 0L
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image ->
            image.use {
                if (capturing.get()) return@use
                val plane = it.planes[0]
                val raw = NativeScanner.detectQuadLuma(plane.buffer, it.width, it.height, plane.rowStride)
                val upright = raw?.let { c -> rotateCorners(c, it.imageInfo.rotationDegrees) }
                live = upright

                val now = System.currentTimeMillis()
                val prev = lastCorners
                if (upright != null && prev != null && maxDelta(prev, upright) < STABLE_EPS) {
                    if (now - stableSince >= STABLE_MS) {
                        stableSince = now + STABLE_MS // evita ráfaga
                        ContextCompat.getMainExecutor(context).execute { capture.value() }
                    }
                } else {
                    stableSince = now
                }
                lastCorners = upright
            }
        }

        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            p.unbindAll()
            camera = p.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis,
            )
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            analysis.clearAnalyzer()
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Escanear documento",
            onBack = { if (vm.pages.isNotEmpty()) vm.show(Screen.REVIEW) else vm.closeDocument() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .align(Alignment.Center),
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val c = live ?: return@Canvas
                    val path = Path().apply {
                        for (i in 0 until 4) {
                            val x = c[i * 2] * size.width
                            val y = c[i * 2 + 1] * size.height
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(path, Accent.copy(alpha = 0.2f), style = Fill)
                    drawPath(path, Accent, style = Stroke(width = 4.dp.toPx()))
                }
            }
        }
        ActionDock {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (hasFlash) {
                        val scheme = MaterialTheme.colorScheme
                        ToolButton(
                            if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                            "Luz",
                            onClick = { torch = !torch },
                            container = if (torch) scheme.primaryContainer else scheme.surfaceContainerLow,
                            content = if (torch) scheme.primary else scheme.onSurface,
                        )
                    }
                }
                ShutterButton(onClick = { capture.value() })
                Box(Modifier.weight(1f))
            }
        }
    }
}

private fun maxDelta(a: FloatArray, b: FloatArray): Float {
    var m = 0f
    for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
    return m
}

/** Esquinas del frame del sensor → orientación de pantalla, reordenadas tl,tr,br,bl. */
private fun rotateCorners(c: FloatArray, degrees: Int): FloatArray {
    if (degrees == 0) return c
    val pts = Array(4) { i ->
        val x = c[i * 2]
        val y = c[i * 2 + 1]
        when (degrees) {
            90 -> Offset(1 - y, x)
            180 -> Offset(1 - x, 1 - y)
            270 -> Offset(y, 1 - x)
            else -> Offset(x, y)
        }
    }
    val tl = pts.minBy { it.x + it.y }
    val br = pts.maxBy { it.x + it.y }
    val tr = pts.minBy { it.y - it.x }
    val bl = pts.maxBy { it.y - it.x }
    return floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
}
