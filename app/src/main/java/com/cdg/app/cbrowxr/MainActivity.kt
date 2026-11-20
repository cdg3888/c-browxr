package com.cdg.app.cbrowxr

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cdg.app.cbrowxr.browser.BrowserEngine
import com.cdg.app.cbrowxr.browser.UserAgentProfile
import com.cdg.app.cbrowxr.downloader.DownloadForegroundService
import com.cdg.app.cbrowxr.sniffer.MediaSniffer
import com.cdg.app.cbrowxr.sniffer.SniffedMedia
import com.cdg.app.cbrowxr.updater.EngineUpdater
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val cbrowxrScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF78A9FF),
                onPrimary = androidx.compose.ui.graphics.Color(0xFF07101F),
                primaryContainer = androidx.compose.ui.graphics.Color(0xFF1F3A66),
                secondary = androidx.compose.ui.graphics.Color(0xFF78E7C7),
                secondaryContainer = androidx.compose.ui.graphics.Color(0xFF163F36),
                tertiary = androidx.compose.ui.graphics.Color(0xFFFFB86B),
                background = androidx.compose.ui.graphics.Color(0xFF090C12),
                onBackground = androidx.compose.ui.graphics.Color(0xFFE8EDF7),
                surface = androidx.compose.ui.graphics.Color(0xFF11151C),
                onSurface = androidx.compose.ui.graphics.Color(0xFFE8EDF7),
                surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202631),
                outline = androidx.compose.ui.graphics.Color(0xFF424B5A)
            )
            MaterialTheme(colorScheme = cbrowxrScheme) {
                CBrowxrScreen(
                    onEnterPip = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                            enterPictureInPictureMode(params)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CBrowxrScreen(onEnterPip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf("https://duckduckgo.com") }
    var inputUrl by remember { mutableStateOf(currentUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var blockedPopups by remember { mutableIntStateOf(0) }
    var uaProfile by remember { mutableStateOf(UserAgentProfile.ANDROID) }
    var ephemeralMode by remember { mutableStateOf(false) }
    var showSnifferSheet by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }

    val detectedMedia by MediaSniffer.detectedMedia.collectAsState()

    val sessionState = remember(uaProfile, ephemeralMode) {
        BrowserEngine.createHardenedSession(
            context = context,
            ephemeral = ephemeralMode,
            profile = uaProfile,
            onUrlChanged = { newUrl ->
                currentUrl = newUrl
                inputUrl = newUrl
                MediaSniffer.inspectUrl(newUrl, newUrl)
            },
            onProgressChanged = { progress = it },
            onBlockedPopup = {
                blockedPopups++
            }
        ).apply {
            loadUri(currentUrl)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    title = {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .height(44.dp)
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Lock else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = if (currentUrl.startsWith("https://"))
                                        MaterialTheme.colorScheme.secondary
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextField(
                                    value = inputUrl,
                                    onValueChange = { inputUrl = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Buscar o escribir dirección") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            val formatted = formatAddress(inputUrl)
                                            val clean = MediaSniffer.cleanUrl(formatted)
                                            currentUrl = clean
                                            inputUrl = clean
                                            sessionState.loadUri(clean)
                                        }
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { sessionState.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                        }
                        Box {
                            IconButton(onClick = { showToolsMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Herramientas")
                            }
                            DropdownMenu(
                                expanded = showToolsMenu,
                                onDismissRequest = { showToolsMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("UA: ${uaProfile.label}") },
                                    onClick = {
                                        val next = when (uaProfile) {
                                            UserAgentProfile.ANDROID -> UserAgentProfile.DESKTOP_LINUX
                                            UserAgentProfile.DESKTOP_LINUX -> UserAgentProfile.SMART_TV
                                            UserAgentProfile.SMART_TV -> UserAgentProfile.ANDROID
                                        }
                                        uaProfile = next
                                        showToolsMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (ephemeralMode) "Sandbox: ACTIVO" else "Sandbox: OFF") },
                                    onClick = {
                                        ephemeralMode = !ephemeralMode
                                        showToolsMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Modo PiP") },
                                    onClick = {
                                        showToolsMenu = false
                                        onEnterPip()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Actualizar yt-dlp") },
                                    onClick = {
                                        showToolsMenu = false
                                        scope.launch {
                                            val res = EngineUpdater.hotUpdateYtDlp(context)
                                            Toast.makeText(
                                                context,
                                                res.getOrElse { "Error: ${it.message}" },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = false,
                    onClick = { sessionState.goBack() },
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás") },
                    label = { Text("Atrás") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { sessionState.goForward() },
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Adelante") },
                    label = { Text("Avanzar") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = {
                        val home = "https://duckduckgo.com"
                        currentUrl = home
                        inputUrl = home
                        sessionState.loadUri(home)
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Inicio") },
                    label = { Text("Inicio") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = detectedMedia.isNotEmpty(),
                    onClick = {
                        if (detectedMedia.isEmpty()) {
                            Toast.makeText(context, "Aún no se detectaron streams", Toast.LENGTH_SHORT).show()
                        } else {
                            showSnifferSheet = true
                        }
                    },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Streams") },
                    label = { Text("Streams") }
                )
                NavigationBarItem(
                    selected = ephemeralMode,
                    onClick = { ephemeralMode = !ephemeralMode },
                    icon = { Icon(Icons.Default.Shield, contentDescription = "Modo privado") },
                    label = { Text(if (ephemeralMode) "Privado" else "Protección") }
                )
            }
        },
        floatingActionButton = {
            if (detectedMedia.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showSnifferSheet = true },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Streams") },
                    text = { Text("Media (${detectedMedia.size})") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        setSession(sessionState)
                    }
                },
                update = { view ->
                    if (view.session != sessionState) {
                        view.setSession(sessionState)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (blockedPopups > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Popups bloqueados: $blockedPopups",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (showSnifferSheet) {
            ModalBottomSheet(onDismissRequest = { showSnifferSheet = false }) {
                SnifferSheetContent(
                    items = detectedMedia,
                    onExportTxt = {
                        val msg = MediaSniffer.exportToTxt(context, asM3u = false)
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    onExportM3u = {
                        val msg = MediaSniffer.exportToTxt(context, asM3u = true)
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    onClear = {
                        MediaSniffer.clearCurrentList()
                        showSnifferSheet = false
                    },
                    onDownloadIdm = { item ->
                        startBgDownload(context, item.url, "idm")
                    },
                    onDownloadYtDlp = { item ->
                        startBgDownload(context, item.url, "ytdlp")
                    },
                    onCastProxy = { item ->
                        val app = context.applicationContext as CBrowxrApp
                        val proxy = app.streamProxy
                        if (proxy == null) {
                            Toast.makeText(context, "Proxy local no disponible", Toast.LENGTH_SHORT).show()
                        } else {
                            val proxied = proxy.registerStream(item.url, item.headers)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(proxied), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(intent, "Enviar stream a reproductor / TV"))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SnifferSheetContent(
    items: List<SniffedMedia>,
    onExportTxt: () -> Unit,
    onExportM3u: () -> Unit,
    onClear: () -> Unit,
    onDownloadIdm: (SniffedMedia) -> Unit,
    onDownloadYtDlp: (SniffedMedia) -> Unit,
    onCastProxy: (SniffedMedia) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Streams Detectados (${items.size})", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = onExportTxt) { Text(".TXT") }
                TextButton(onClick = onExportM3u) { Text(".M3U") }
                TextButton(onClick = onClear) { Text("Limpiar") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            items(items) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.type.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = item.url,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onDownloadYtDlp(item) }) {
                                Text("yt-dlp")
                            }
                            OutlinedButton(onClick = { onDownloadIdm(item) }) {
                                Text("IDM 8x")
                            }
                            OutlinedButton(onClick = { onCastProxy(item) }) {
                                Text("Proxy/TV")
                            }
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("cURL", item.toCurlCommand()))
                                Toast.makeText(context, "cURL copiado", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar cURL")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatAddress(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
    }
}

private fun startBgDownload(context: Context, url: String, mode: String) {
    val intent = Intent(context, DownloadForegroundService::class.java).apply {
        putExtra("url", url)
        putExtra("mode", mode)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Toast.makeText(context, "Descarga iniciada en segundo plano", Toast.LENGTH_SHORT).show()
}
