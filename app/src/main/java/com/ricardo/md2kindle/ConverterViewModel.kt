package com.ricardo.md2kindle

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ricardo.md2kindle.epub.EpubAsset
import com.ricardo.md2kindle.epub.EpubBook
import com.ricardo.md2kindle.epub.EpubGenerator
import com.ricardo.md2kindle.epub.EpubMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

data class ConverterUiState(
    val sourceName: String = "",
    val markdown: String = "",
    val title: String = "",
    val author: String = "",
    val language: String = "es",
    val images: List<EpubAsset> = emptyList(),
    val cover: EpubAsset? = null,
    val isBusy: Boolean = false,
    val generatedFile: File? = null,
    val status: String = "Selecciona un Markdown para empezar.",
    val warnings: List<String> = emptyList(),
)

class ConverterViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver: ContentResolver = application.contentResolver
    private val generator = EpubGenerator()
    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.parcelableStream()
                when {
                    uri != null -> loadSource(uri)
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.isNotBlank() == true ->
                        loadSharedText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                }
            }

            Intent.ACTION_VIEW -> intent.data?.let(::loadSource)
        }
    }

    fun loadSource(uri: Uri) {
        viewModelScope.launch {
            setBusy("Leyendo documento…")
            runCatching {
                persistPermission(uri)
                val name = displayName(uri) ?: "documento.md"
                val bytes = readLimited(uri, MAX_SOURCE_BYTES)
                val text = bytes.toString(Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .replace("\u0000", "")
                require(text.isNotBlank()) { "El documento está vacío." }
                val suggestedTitle = firstHeading(text)
                    ?: name.substringBeforeLast('.').ifBlank { "Libro" }
                _uiState.value = _uiState.value.copy(
                    sourceName = name,
                    markdown = text,
                    title = suggestedTitle,
                    images = emptyList(),
                    cover = null,
                    generatedFile = null,
                    warnings = emptyList(),
                    isBusy = false,
                    status = "Documento cargado. Revisa los datos y pulsa Crear EPUB.",
                )
            }.onFailure(::showError)
        }
    }

    fun loadSharedText(text: String) {
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(
            sourceName = "texto_compartido.md",
            markdown = text,
            title = firstHeading(text) ?: "Libro",
            images = emptyList(),
            cover = null,
            generatedFile = null,
            warnings = emptyList(),
            status = "Texto compartido cargado. Revisa los datos y pulsa Crear EPUB.",
        )
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            setBusy("Añadiendo imágenes…")
            runCatching {
                val newImages = withContext(Dispatchers.IO) {
                    var totalBytes = _uiState.value.images.sumOf { it.bytes.size }
                    uris.take(MAX_IMAGE_COUNT).mapNotNull { uri ->
                        persistPermission(uri)
                        val name = displayName(uri) ?: "imagen"
                        val declaredMime =
                            supportedImageMime(uri, name) ?: return@mapNotNull null
                        val bytes = readLimited(uri, MAX_IMAGE_BYTES)
                        val mime = verifiedImageMime(bytes, declaredMime)
                            ?: error("$name no contiene una imagen JPEG, PNG o GIF válida.")
                        totalBytes += bytes.size
                        require(totalBytes <= MAX_TOTAL_IMAGE_BYTES) {
                            "Las imágenes superan el límite total de " +
                                "${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024} MB."
                        }
                        EpubAsset(
                            sourceName = name,
                            mediaType = mime,
                            bytes = bytes,
                        )
                    }
                }
                val merged = (_uiState.value.images + newImages)
                    .distinctBy { normalizeName(it.sourceName) }
                    .take(MAX_IMAGE_COUNT)
                _uiState.value = _uiState.value.copy(
                    images = merged,
                    isBusy = false,
                    generatedFile = null,
                    status = "${merged.size} imágenes disponibles para resolver referencias del Markdown.",
                )
            }.onFailure(::showError)
        }
    }

    fun setCover(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            setBusy("Añadiendo portada…")
            runCatching {
                persistPermission(uri)
                val name = displayName(uri) ?: "portada"
                val mime = supportedImageMime(uri, name)
                    ?: error("La portada debe ser JPEG, PNG o GIF.")
                val cover = withContext(Dispatchers.IO) {
                    val bytes = readLimited(uri, MAX_IMAGE_BYTES)
                    val verifiedMime = verifiedImageMime(bytes, mime)
                        ?: error("La portada no contiene una imagen JPEG, PNG o GIF válida.")
                    EpubAsset(name, verifiedMime, bytes)
                }
                _uiState.value = _uiState.value.copy(
                    cover = cover,
                    isBusy = false,
                    generatedFile = null,
                    status = "Portada añadida: $name",
                )
            }.onFailure(::showError)
        }
    }

    fun removeCover() {
        _uiState.value = _uiState.value.copy(
            cover = null,
            generatedFile = null,
            status = "Portada retirada.",
        )
    }

    fun clearImages() {
        _uiState.value = _uiState.value.copy(
            images = emptyList(),
            generatedFile = null,
            status = "Imágenes auxiliares retiradas.",
        )
    }

    fun updateTitle(value: String) {
        _uiState.value = _uiState.value.copy(title = value, generatedFile = null)
    }

    fun updateAuthor(value: String) {
        _uiState.value = _uiState.value.copy(author = value, generatedFile = null)
    }

    fun updateLanguage(value: String) {
        _uiState.value = _uiState.value.copy(language = value, generatedFile = null)
    }

    fun generate() {
        val state = _uiState.value
        if (state.markdown.isBlank()) {
            showError(IllegalStateException("Selecciona primero un archivo Markdown o TXT."))
            return
        }
        if (state.title.isBlank()) {
            showError(IllegalStateException("Escribe un título para el libro."))
            return
        }

        viewModelScope.launch {
            setBusy("Creando EPUB…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val sharedDir = File(getApplication<Application>().cacheDir, "shared")
                        .apply { mkdirs() }
                    sharedDir.listFiles()?.forEach { it.delete() }
                    val target = File(sharedDir, "${safeFileName(state.title)}.epub")
                    val result = FileOutputStream(target).use { output ->
                        generator.write(
                            EpubBook(
                                markdown = state.markdown,
                                metadata = EpubMetadata(
                                    title = state.title.trim(),
                                    author = state.author.trim(),
                                    language = state.language.trim().ifBlank { "es" },
                                ),
                                images = state.images,
                                cover = state.cover,
                            ),
                            output,
                        )
                    }
                    target to result
                }
            }.onSuccess { (file, result) ->
                val imageText = if (result.embeddedImageCount == 1) "1 imagen" else
                    "${result.embeddedImageCount} imágenes"
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    generatedFile = file,
                    warnings = result.warnings,
                    status = "EPUB creado: ${result.chapterCount} capítulos y $imageText.",
                )
            }.onFailure(::showError)
        }
    }

    fun copyGeneratedTo(uri: Uri) {
        val file = _uiState.value.generatedFile ?: return
        viewModelScope.launch {
            setBusy("Guardando EPUB…")
            runCatching {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: error("Android no permitió abrir el destino.")
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    status = "EPUB guardado correctamente.",
                )
            }.onFailure(::showError)
        }
    }

    private fun setBusy(message: String) {
        _uiState.value = _uiState.value.copy(isBusy = true, status = message)
    }

    private fun showError(throwable: Throwable) {
        _uiState.value = _uiState.value.copy(
            isBusy = false,
            status = "Error: ${throwable.message ?: "operación no completada"}",
        )
    }

    private suspend fun readLimited(uri: Uri, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) {
                        "El archivo supera el límite de ${maxBytes / 1024 / 1024} MB."
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("No se pudo leer el archivo.")
        }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                cursor.getString(0)
            } else {
                uri.lastPathSegment?.substringAfterLast('/')
            }
        } finally {
            cursor?.close()
        }
    }

    private fun persistPermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun supportedImageMime(uri: Uri, name: String): String? {
        val resolverMime = resolver.getType(uri)?.lowercase()
        return when {
            resolverMime == "image/jpeg" || resolverMime == "image/jpg" -> "image/jpeg"
            resolverMime == "image/png" -> "image/png"
            resolverMime == "image/gif" -> "image/gif"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".gif", true) -> "image/gif"
            else -> null
        }
    }

    private fun verifiedImageMime(bytes: ByteArray, declaredMime: String): String? {
        if (bytes.size < 8) return null
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            ),
        )
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        val isGif = header == "GIF87a" || header == "GIF89a"
        return when {
            isJpeg && declaredMime == "image/jpeg" -> "image/jpeg"
            isPng && declaredMime == "image/png" -> "image/png"
            isGif && declaredMime == "image/gif" -> "image/gif"
            isJpeg -> "image/jpeg"
            isPng -> "image/png"
            isGif -> "image/gif"
            else -> null
        }
    }

    private fun firstHeading(markdown: String): String? =
        markdown.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("""^#\s+.+""")) }
            ?.removePrefix("#")
            ?.trim()
            ?.trimEnd('#')
            ?.trim()
            ?.replace(Regex("""[*_`~]"""), "")
            ?.takeIf { it.isNotBlank() }

    private fun safeFileName(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
        return ascii
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .replace(Regex("""\s+"""), "_")
            .trim('_', '.')
            .take(80)
            .ifBlank { "libro" }
    }

    private fun normalizeName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStream(): Uri? =
        getParcelableExtra(Intent.EXTRA_STREAM)

    companion object {
        private const val MAX_SOURCE_BYTES = 25 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        private const val MAX_TOTAL_IMAGE_BYTES = 60 * 1024 * 1024
        private const val MAX_IMAGE_COUNT = 100
    }
}
