package app.minimpos.app.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.minimpos.app.R
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.theme.LocalDimens
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** Full-screen single-scan dialog. */
@Composable
fun ScannerDialog(
    mode: ScanMode,
    onDismiss: () -> Unit,
    hint: String? = null,
    onResult: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                ScannerView(mode = mode, onResult = onResult, modifier = Modifier.fillMaxSize())
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
                }
                ScanHint(
                    hint ?: stringResource(if (mode == ScanMode.QR) R.string.scan_hint_qr else R.string.scan_hint_barcode),
                    Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** What to point the camera at, over the camera picture: white on a dark pill, so it stays legible on any image. */
@Composable
fun ScanHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            modifier
                .padding(LocalDimens.current.screenPadding * 2)
                .background(Color.Black.copy(alpha = HINT_ALPHA), MaterialTheme.shapes.large)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private const val HINT_ALPHA = 0.6f

/**
 * Camera preview that decodes barcodes with ZXing (no Google Play services needed, which Adyen terminals lack).
 * Without [continuous] it reports the first code only; with it, it keeps scanning and reports a code again only after
 * a different one. [onResult] is called on the main thread. The camera permission is asked for when missing.
 */
@Composable
fun ScannerView(
    mode: ScanMode,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    continuous: Boolean = false,
    acceptResult: (String) -> Boolean = { true },
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    // Always dark, like the camera picture, so the white text over it can be read before the camera starts too.
    val surface = modifier.background(Color.Black)
    if (!granted) {
        CameraPermissionPrompt(onGrant = { launcher.launch(Manifest.permission.CAMERA) }, modifier = surface)
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onResult)
    val latestAcceptResult by rememberUpdatedState(acceptResult)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var error by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mode, continuous) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var delivered = false
        var lastCode: String? = null
        val analyzer =
            BarcodeAnalyzer(mode.formats, rotateForLinear = mode != ScanMode.QR) { code ->
                mainExecutor.execute {
                    if (continuous) {
                        if (code != lastCode) {
                            lastCode = code
                            latestOnResult(code)
                        }
                    } else if (!delivered && latestAcceptResult(code)) {
                        delivered = true
                        latestOnResult(code)
                    }
                }
            }
        providerFuture.addListener({
            runCatching {
                bindCamera(providerFuture.get(), lifecycleOwner, previewView) { setAnalyzer(executor, analyzer) }
            }.onFailure { error = true }
        }, mainExecutor)
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    CameraPreview(previewView, error, square = mode == ScanMode.QR, modifier = surface)
}

/**
 * The camera picture with a frame to aim at (square for QR codes, wide for barcodes), or a message over it when the
 * camera could not be started.
 */
@Composable
private fun CameraPreview(
    previewView: PreviewView,
    error: Boolean,
    square: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .fillMaxWidth(FRAME_WIDTH)
                .aspectRatio(if (square) 1f else BARCODE_FRAME_RATIO)
                .border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large),
        )
        if (error) {
            Text(
                stringResource(R.string.scan_camera_error),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Explains why the camera is needed, with a button that asks for the permission again. */
@Composable
private fun CameraPermissionPrompt(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.NoPhotography, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.scan_permission),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
        PrimaryButton(stringResource(R.string.scan_grant), onGrant, modifier = Modifier.widthIn(max = 320.dp))
    }
}

private const val FRAME_WIDTH = 0.7f
private const val BARCODE_FRAME_RATIO = 1.6f

/**
 * Binds the preview and a 1280×720 (or the closest) analysis stream to [lifecycleOwner], using the back camera when
 * there is one. [configureAnalysis] installs the analyzer. Call on the main thread.
 */
private fun bindCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    configureAnalysis: ImageAnalysis.() -> Unit,
) {
    val selector =
        if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
    val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
    val analysis =
        ImageAnalysis
            .Builder()
            .setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    ).build(),
            ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply(configureAnalysis)
    provider.unbindAll()
    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
}

private class BarcodeAnalyzer(
    formats: List<BarcodeFormat>,
    private val rotateForLinear: Boolean,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to formats,
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            val width = image.width
            val height = image.height
            // Rows of the Y plane are rowStride bytes apart, which can be more than the image width; the source crops to it.
            val stride = plane.rowStride
            val source = PlanarYUVLuminanceSource(data, stride, height, 0, 0, width, height, false)
            val text =
                decode(source)
                    ?: if (rotateForLinear) {
                        decode(
                            PlanarYUVLuminanceSource(rotate(data, stride, width, height), height, width, 0, 0, height, width, false),
                        )
                    } else {
                        null
                    }
            text?.let(onCode)
        }
    }

    private fun decode(source: LuminanceSource): String? =
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (ignored: ReaderException) {
            // Most frames hold no readable code (NotFoundException).
            null
        } finally {
            reader.reset()
        }

    /** Rotates the luminance plane 90° so barcodes held across the camera's long axis can be read. */
    private fun rotate(
        data: ByteArray,
        stride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val rotated = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                rotated[x * height + (height - 1 - y)] = data[y * stride + x]
            }
        }
        return rotated
    }
}
