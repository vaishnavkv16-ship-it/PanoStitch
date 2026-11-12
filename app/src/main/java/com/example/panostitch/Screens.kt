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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun PanoApp(vm: PanoViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    when (val p = s.phase) {
        is Phase.Idle -> EditorScreen(s, vm)
        is Phase.Working -> WorkingScreen(p, vm)
        is Phase.Done -> ResultScreen(p.bitmap, vm)
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

// ───────────────────────── Editor ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(s: UiState, vm: PanoViewModel) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(40)
    ) { vm.addImages(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PanoStitch") },
                actions = {
                    if (s.uris.isNotEmpty()) IconButton(onClick = vm::clear) {
                        Icon(Icons.Default.Delete, "Clear all")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::stitch,
                    enabled = s.uris.size >= 2,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()
                ) {
                    Text(if (s.uris.size >= 2) "Stitch ${s.uris.size} photos" else "Add at least 2 photos")
                }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
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
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    if (s.uris.isEmpty()) Text(
                        "Pick photos in sweep order (left to right), overlapping by ~30%. Order matters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OptionsCard(s.options, vm::setOptions)
                }
            }
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
            Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), RoundedCornerShape(50)).padding(2.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(o: StitchOptions, onChange: (StitchOptions) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Options", style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = o.mode == m,
                        onClick = { onChange(o.copy(mode = m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)
                    ) { Text(m.label) }
                }
            }

            LabeledSlider("Matching detail", "${"%.1f".format(o.matchMegapix)} MP",
                o.matchMegapix.toFloat(), 0.3f..1.5f) { onChange(o.copy(matchMegapix = it.toDouble())) }
            LabeledSlider("Match strictness (lower = stricter)", "${"%.2f".format(o.matchRatio)}",
                o.matchRatio.toFloat(), 0.6f..0.9f) { onChange(o.copy(matchRatio = it.toDouble())) }

            ChipRow("Input size", listOf(1600, 2400, 3200), o.maxInputEdge) { onChange(o.copy(maxInputEdge = it)) }
            ChipRow("Max output", listOf(4096, 6144, 8000), o.maxOutputEdge) { onChange(o.copy(maxOutputEdge = it)) }

            SwitchRow("Auto-crop black borders", o.autoCrop) { onChange(o.copy(autoCrop = it)) }
            SwitchRow("Balance exposure between photos", o.balanceExposure) { onChange(o.copy(balanceExposure = it)) }
            SwitchRow("Save as PNG (lossless)", o.png) { onChange(o.copy(png = it)) }
            if (!o.png) LabeledSlider("JPEG quality", "${o.jpegQuality}%",
                o.jpegQuality.toFloat(), 70f..100f) { onChange(o.copy(jpegQuality = it.roundToInt())) }
        }
    }
}

@Composable
private fun LabeledSlider(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = v, onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(title: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach {
                FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text("$it px") })
            }
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
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (p.progress != null) CircularProgressIndicator(progress = { p.progress })
        else CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(p.label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }
}

// ───────────────────────── Result ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultScreen(bmp: Bitmap, vm: PanoViewModel) {
    val ctx = LocalContext.current
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
                                Toast.makeText(ctx, if (uri != null) "Saved to Pictures/PanoStitch" else "Save failed",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
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
                        modifier = Modifier.weight(1f)
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
        ) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = "Panorama",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
                )
            )
        }
    }
}
