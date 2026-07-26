package com.ricardo.md2kindle

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private val converterViewModel: ConverterViewModel by viewModels()
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIntent = intent
        setContent {
            MD2KindleTheme {
                val received = pendingIntent
                LaunchedEffect(received) {
                    converterViewModel.consumeIntent(received)
                    pendingIntent = null
                }
                ConverterScreen(
                    viewModel = converterViewModel,
                    onShare = ::shareEpub,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }

    private fun shareEpub(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Enviar EPUB a Kindle"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConverterScreen(
    viewModel: ConverterViewModel = viewModel(),
    onShare: (File) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    val openSource = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::loadSource) },
    )
    val openImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = viewModel::addImages,
    )
    val openCover = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = viewModel::setCover,
    )
    val saveEpub = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip"),
        onResult = { uri -> uri?.let(viewModel::copyGeneratedTo) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MD2Kindle", fontWeight = FontWeight.Bold)
                        Text(
                            "Markdown → Kindle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IntroCard()

            Button(
                onClick = {
                    openSource.launch(
                        arrayOf(
                            "text/markdown",
                            "text/x-markdown",
                            "application/x-markdown",
                            "text/plain",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(if (state.sourceName.isBlank()) "Abrir Markdown o TXT" else "Cambiar documento")
            }

            if (state.sourceName.isNotBlank()) {
                SourceCard(state)
                IndexPreviewCard(state)

                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("Título del libro") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.author,
                    onValueChange = viewModel::updateAuthor,
                    label = { Text("Autor (opcional)") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.language,
                    onValueChange = viewModel::updateLanguage,
                    label = { Text("Idioma") },
                    supportingText = { Text("Para español usa «es».") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            openCover.launch(arrayOf("image/jpeg", "image/png", "image/gif"))
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text(if (state.cover == null) "Portada" else "Cambiar")
                    }
                    OutlinedButton(
                        onClick = {
                            openImages.launch(arrayOf("image/jpeg", "image/png", "image/gif"))
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("Imágenes")
                    }
                }

                if (state.cover != null || state.images.isNotEmpty()) {
                    AssetsCard(
                        state = state,
                        onRemoveCover = viewModel::removeCover,
                        onClearImages = viewModel::clearImages,
                    )
                }

                Button(
                    onClick = viewModel::generate,
                    enabled = !state.isBusy && state.title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF356859),
                    ),
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                    }
                    Spacer(Modifier.padding(horizontal = 5.dp))
                    Text(if (state.isBusy) "Procesando…" else "Crear EPUB")
                }
            }

            StatusCard(state)

            state.generatedFile?.let { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { saveEpub.launch(file.name) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("Guardar")
                    }
                    Button(
                        onClick = { onShare(file) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("Enviar a Kindle")
                    }
                }
                Text(
                    "Elige Kindle o «Enviar a Kindle» en el menú Compartir. Amazon añadirá el EPUB a tu biblioteca y después podrá sincronizarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IntroCard() {
    Surface(
        color = Color(0xFFF7F3EA),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Convierte sin subir tu texto a terceros",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "La conversión se hace en el móvil. La v2 reconoce H1, H2, H3 y títulos de capítulo escritos sin #.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SourceCard(state: ConverterUiState) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Documento", style = MaterialTheme.typography.labelLarge)
            Text(
                state.sourceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${state.markdown.length} caracteres",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IndexPreviewCard(state: ConverterUiState) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Índice detectado · ${state.tableOfContents.size} entradas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.tableOfContents.take(30).forEach { entry ->
                Text(
                    text = if (entry.level == 1) entry.title else "↳ ${entry.title}",
                    modifier = Modifier.padding(start = if (entry.level == 1) 0.dp else 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.tableOfContents.size > 30) {
                Text(
                    "…y ${state.tableOfContents.size - 30} entradas más",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Este mismo índice se incluirá como página visible y en «Ir a / Índice» de Kindle.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AssetsCard(
    state: ConverterUiState,
    onRemoveCover: () -> Unit,
    onClearImages: () -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.cover?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Portada: ${it.sourceName}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRemoveCover) { Text("Quitar") }
                }
            }
            if (state.images.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.images.size} imágenes para el contenido",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onClearImages) { Text("Vaciar") }
                }
                Text(
                    "Selecciona los archivos citados como ![texto](imagen.jpg). La app los empareja por nombre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConverterUiState) {
    val isError = state.status.startsWith("Error:")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    isError -> Color(0xFFFFE5E5)
                    state.generatedFile != null -> Color(0xFFE5F3EC)
                    else -> Color(0xFFF1F1F1)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                state.status,
                fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isError) Color(0xFF8B1A1A) else Color.Unspecified,
            )
            state.warnings.forEach { warning ->
                Text(
                    "Aviso: $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A4E00),
                )
            }
        }
    }
}

@Composable
private fun MD2KindleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF356859),
            onPrimary = Color.White,
            secondary = Color(0xFF8A5A44),
            background = Color(0xFFFFFBF5),
            surface = Color(0xFFFFFBF5),
        ),
        content = content,
    )
}
