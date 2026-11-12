package com.example.panostitch

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private val Brand = Color(0xFF1565C0)

@Composable
fun PanoApp(vm: PanoViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    when (val p = s.phase) {
        is Phase.Idle -> if (s.uris.isEmpty()) HomeScreen(vm) else ReviewScreen(s, vm)
        is Phase.Working -> WorkingScreen(p, vm)
        is Phase.Done -> ResultScreen(p, vm)
    }
    s.error?.let {
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Couldn't create panorama") },
            text = { Text(it) }
        )
    }
}

@Composable
private fun rememberPhotoPicker(vm: PanoViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(40)
    ) { vm.addImages(it) }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

// ───────────────────────── Home ─────────────────────────

@Composable
private fun HomeScreen(vm: PanoViewModel) {
    val pick = rememberPhotoPicker(vm)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)).background(Brand),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(24.dp))
        Text("PanoStitch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Turn overlapping photos into one wide panorama.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = pick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Select photos", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(32.dp))
        Tip("Overlap each photo with the next by about 30%.")
        Tip("Turn on the spot and keep the phone level.")
        Tip("Any order works — photos are arranged automatically.")
    }
}

@Composable
private fun Tip(text: String) {
    Text(
        "•  $text", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

// ───────────────────────── Review ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(s: UiState, vm: PanoViewModel) {
    val pick = rememberPhotoPicker(vm)
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${s.uris.size} photos") },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = vm::clear) { Icon(Icons.Default.Delete, "Clear all") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m ->
                            FilterChip(
                                selected = s.options.mode == m,
                                onClick = { vm.setOptions(s.options.copy(mode = m)) },
                                label = { Text(m.label) }
                            )
                        }
                    }
                    Text(
                        s.options.mode.hint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = vm::stitch,
                        enabled = s.uris.size >= 2,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            if (s.uris.size >= 2) "Create panorama" else "Add at least 2 photos",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(pad)
        ) {
            item {
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = pick),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, "Add photos", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            items(s.uris, key = { it.toString() }) { uri -> Thumb(uri) { vm.remove(uri) } }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheet(s.options, vm::setOptions)
        }
    }
}

@Composable
private fun Thumb(uri: Uri, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = ImageIO.load(ctx, uri, 256) }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
            Icon(
                Icons.Default.Close, "Remove", tint = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(50)).padding(3.dp)
            )
        }
    }
}

// ───────────────────────── Settings ─────────────────────────

private data class Preset(val name: String, val input: Int, val match: Double, val output: Int)

private val presets = listOf(
    Preset("Fast", 1600, 0.4, 4096),
    Preset("Balanced", 2400, 0.6, 8000),
    Preset("High", 3200, 1.0, 8000)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(o: StitchOptions, onChange: (StitchOptions) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Quality", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                FilterChip(
                    selected = o.maxInputEdge == p.input,
                    onClick = { onChange(o.copy(maxInputEdge = p.input, matchMegapix = p.match, maxOutputEdge = p.output)) },
                    label = { Text(p.name) }
                )
            }
        }
        Text(
            "Higher quality is slower and needs more memory. If the app runs out of memory, choose Fast.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Match strictness", style = MaterialTheme.typography.bodyMedium)
                Text("%.2f".format(o.matchRatio), color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = o.matchRatio.toFloat(), valueRange = 0.6f..0.9f,
                onValueChange = { onChange(o.copy(matchRatio = it.toDouble())) }
            )
            Text(
                "Lower = fewer, more reliable matches. Raise it if photos fail to join.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SwitchRow("Balance brightness between photos", o.balanceExposure) { onChange(o.copy(balanceExposure = it)) }
        SwitchRow("Crop empty borders", o.autoCrop) { onChange(o.copy(autoCrop = it)) }
        SwitchRow("Save as PNG (lossless)", o.png) { onChange(o.copy(png = it)) }
        if (!o.png) Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("JPEG quality", style = MaterialTheme.typography.bodyMedium)
                Text("${o.jpegQuality}%", color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = o.jpegQuality.toFloat(), valueRange = 70f..100f,
                onValueChange = { onChange(o.copy(jpegQuality = it.roundToInt())) }
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ───────────────────────── Working ─────────────────────────

@Composable
private fun WorkingScreen(p: Phase.Working, vm: PanoViewModel) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (p.progress != null) CircularProgressIndicator(progress = { p.progress })
        else CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(p.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Matching and blending photos — large panoramas can take a minute.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }
}

// ───────────────────────── Result ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultScreen(done: Phase.Done, vm: PanoViewModel) {
    val ctx = LocalContext.current
    val bmp = done.bitmap
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${bmp.width} × ${bmp.height}") },
                navigationIcon = {
                    IconButton(onClick = vm::backToEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            vm.save { uri ->
                                Toast.makeText(
                                    ctx, if (uri != null) "Saved to Pictures/PanoStitch" else "Save failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = {
                            vm.save { uri ->
                                if (uri == null) return@save
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = ctx.contentResolver.getType(uri) ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "Share panorama"))
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                }
            }
        }
    ) { pad ->
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.padding(pad).fillMaxSize().clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f
                    })
                }
        ) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = "Panorama",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
                )
            )
            done.note?.let {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp
                ) { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
