package com.example.panostitch

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

sealed interface Phase {
    data object Idle : Phase
    data class Working(val label: String, val progress: Float?) : Phase
    data class Done(val bitmap: Bitmap, val note: String?) : Phase
}

data class UiState(
    val uris: List<Uri> = emptyList(),
    val options: StitchOptions = StitchOptions(),
    val phase: Phase = Phase.Idle,
    val savedUri: Uri? = null,
    val error: String? = null
)

class PanoViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (!OpenCVLoader.initLocal()) _state.update { it.copy(error = "Could not load OpenCV.") }
    }

    fun addImages(new: List<Uri>) = _state.update { s ->
        s.copy(uris = (s.uris + new).distinct())
    }
    fun remove(uri: Uri) = _state.update { it.copy(uris = it.uris - uri) }
    fun clear() = _state.update { it.copy(uris = emptyList()) }
    fun setOptions(o: StitchOptions) = _state.update { it.copy(options = o) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun backToEditor() = _state.update { it.copy(phase = Phase.Idle, savedUri = null) }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = Phase.Idle) }
    }

    fun stitch() {
        val s = _state.value
        if (s.uris.size < 2) { _state.update { it.copy(error = "Pick at least 2 photos.") }; return }
        job = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val bitmaps = ArrayList<Bitmap>()
                val focals = ArrayList<Double?>()
                s.uris.forEachIndexed { i, uri ->
                    _state.update {
                        it.copy(phase = Phase.Working("Loading photo ${i + 1} of ${s.uris.size}",
                            (i + 1f) / (s.uris.size + 1)))
                    }
                    ImageIO.load(ctx, uri, s.options.maxInputEdge)?.let {
                        bitmaps.add(it); focals.add(ImageIO.focal35(ctx, uri))
                    }
                }
                if (bitmaps.size < 2) throw IllegalStateException("Couldn't read the selected photos.")

                _state.update { it.copy(phase = Phase.Working("Stitching… this can take a while", null)) }
                val result = withContext(Dispatchers.Default) { StitchEngine.stitch(bitmaps, focals, s.options) }
                bitmaps.forEach { it.recycle() }

                _state.update {
                    when (result) {
                        is StitchResult.Success -> it.copy(phase = Phase.Done(result.bitmap, result.note), savedUri = null)
                        is StitchResult.Failure -> it.copy(phase = Phase.Idle, error = result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(phase = Phase.Idle,
                    error = "Out of memory. Use fewer photos or lower the input/output size.") }
            } catch (e: Throwable) {
                _state.update { it.copy(phase = Phase.Idle, error = e.message ?: "Something went wrong.") }
            }
        }
    }

    /** Saves the result (once) and returns its Uri through [onDone]. */
    fun save(onDone: (Uri?) -> Unit) {
        val st = _state.value
        val done = st.phase as? Phase.Done ?: return
        st.savedUri?.let { onDone(it); return }
        viewModelScope.launch {
            val uri = ImageIO.saveToGallery(getApplication(), done.bitmap, st.options)
            _state.update { it.copy(savedUri = uri) }
            onDone(uri)
        }
    }
}
