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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.cdg.app.cbrowxr.browser.BrowserPreferences
import com.cdg.app.cbrowxr.browser.AppAccent
import com.cdg.app.cbrowxr.browser.BrowserSettings
import com.cdg.app.cbrowxr.browser.CookiePolicy
import com.cdg.app.cbrowxr.browser.DownloadEngine
import com.cdg.app.cbrowxr.browser.SearchEngine
import com.cdg.app.cbrowxr.browser.ThemeMode
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
        val settings = BrowserPreferences.load(this)
        setContent {
            val isAmoled = settings.themeMode == ThemeMode.AMOLED
            val surface = if (isAmoled)
                androidx.compose.ui.graphics.Color(0xFF000000)
            else
                androidx.compose.ui.graphics.Color(0xFF11151C)
            val cbrowxrScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(settings.accent.primaryHex),
                onPrimary = androidx.compose.ui.graphics.Color(0xFF07101F),
                primaryContainer = androidx.compose.ui.graphics.Color(0xFF1F3A66),
                secondary = androidx.compose.ui.graphics.Color(settings.accent.secondaryHex),
                secondaryContainer = androidx.compose.ui.graphics.Color(0xFF163F36),
                tertiary = androidx.compose.ui.graphics.Color(0xFFFFB86B),
                background = if (isAmoled)
                    androidx.compose.ui.graphics.Color(0xFF000000)
                else
                    androidx.compose.ui.graphics.Color(0xFF090C12),
                onBackground = androidx.compose.ui.graphics.Color(0xFFE8EDF7),
                surface = surface,
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

    var settings by remember { mutableStateOf(BrowserPreferences.load(context)) }
    var currentUrl by remember { mutableStateOf(settings.searchEngine.baseUrl) }
    var inputUrl by remember { mutableStateOf(currentUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var blockedPopups by remember { mutableIntStateOf(0) }
    var uaProfile by remember { mutableStateOf(UserAgentProfile.ANDROID) }
    var ephemeralMode by remember { mutableStateOf(settings.privateMode) }
    var showSnifferSheet by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var nextTabId by remember { mutableIntStateOf(2) }
    val browserTabs = remember {
        mutableStateListOf(BrowserTabState(1, "Nueva pestaña", settings.searchEngine.baseUrl))
    }
    var activeTabId by remember { mutableIntStateOf(1) }

    val detectedMedia by MediaSniffer.detectedMedia.collectAsState()

    val sessionState = remember(
        uaProfile,
        ephemeralMode,
        settings.adBlockEnabled,
        settings.trackingEnabled,
        settings.javaScriptEnabled,
        settings.blockPopups,
        settings.cookiePolicy
    ) {
        BrowserEngine.createHardenedSession(
            context = context,
            ephemeral = ephemeralMode,
            profile = uaProfile,
            trackingEnabled = settings.trackingEnabled,
            blockPopups = settings.blockPopups,
            adBlockEnabled = settings.adBlockEnabled,
            javaScriptEnabled = settings.javaScriptEnabled,
            cookiePolicy = settings.cookiePolicy,
            onUrlChanged = { newUrl ->
                currentUrl = newUrl
                inputUrl = newUrl
                MediaSniffer.inspectUrl(newUrl, newUrl)
                val tabIndex = browserTabs.indexOfFirst { it.id == activeTabId }
                if (tabIndex >= 0) {
                    browserTabs[tabIndex] = browserTabs[tabIndex].copy(
                        url = newUrl,
                        title = tabTitle(newUrl)
                    )
                }
            },
            onProgressChanged = { progress = it },
            onNavigationError = { uri, error ->
                (context.applicationContext as CBrowxrApp).logError(
                    "NAVIGATION_ERROR",
                    IllegalStateException(
                        "GeckoView could not load $uri (category=${error.category}, code=${error.code})",
                        error
                    )
                )
                Toast.makeText(
                    context,
                    "No se pudo cargar la página. Revisa Herramientas → Diagnóstico.",
                    Toast.LENGTH_LONG
                ).show()
            },
            onBlockedPopup = {
                blockedPopups++
            }
        ).apply {
            loadUri(currentUrl)
        }
    }

    LaunchedEffect(activeTabId) {
        val tab = browserTabs.firstOrNull { it.id == activeTabId }
        if (tab != null) {
            currentUrl = tab.url
            inputUrl = tab.url
            sessionState.loadUri(tab.url)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(browserTabs, key = { it.id }) { tab ->
                            val selected = tab.id == activeTabId
                            Surface(
                                modifier = Modifier
                                    .height(36.dp)
                                    .widthIn(min = 120.dp, max = 190.dp),
                                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                                color = if (selected)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else
                                    MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(start = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tab.title,
                                        modifier = Modifier.weight(1f).clickable { activeTabId = tab.id },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    IconButton(
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            if (browserTabs.size == 1) {
                                                val reset = BrowserTabState(1, "Nueva pestaña", settings.searchEngine.baseUrl)
                                                browserTabs.clear()
                                                browserTabs.add(reset)
                                                activeTabId = 1
                                            } else {
                                                val index = browserTabs.indexOf(tab)
                                                browserTabs.removeAt(index)
                                                if (tab.id == activeTabId) {
                                                    val nextIndex = index.coerceAtMost(browserTabs.lastIndex)
                                                    activeTabId = browserTabs[nextIndex].id
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Cerrar pestaña", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    IconButton(onClick = {
                        val id = nextTabId
                        nextTabId++
                        browserTabs.add(BrowserTabState(id, "Nueva pestaña", settings.searchEngine.baseUrl))
                        activeTabId = id
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Nueva pestaña")
                    }
                }
                TopAppBar(
                    title = {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .height(56.dp)
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
                                                val formatted = formatAddress(inputUrl, settings.searchEngine)
                                            val clean = MediaSniffer.cleanUrl(formatted)
                                            currentUrl = clean
                                            inputUrl = clean
                                                val tabIndex = browserTabs.indexOfFirst { it.id == activeTabId }
                                                if (tabIndex >= 0) {
                                                    browserTabs[tabIndex] = browserTabs[tabIndex].copy(
                                                        url = clean,
                                                        title = tabTitle(clean)
                                                    )
                                                }
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
                                    text = { Text("Ajustes") },
                                    onClick = {
                                        showToolsMenu = false
                                        showSettingsSheet = true
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
                                            res.onFailure {
                                                (context.applicationContext as CBrowxrApp)
                                                    .logError("YTDLP_UPDATE_ERROR", it)
                                            }
                                            Toast.makeText(
                                                context,
                                                res.getOrElse { "Error: ${it.message}" },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Diagnóstico de errores") },
                                    onClick = {
                                        showToolsMenu = false
                                        val app = context.applicationContext as CBrowxrApp
                                        app.readDiagnostics().fold(
                                            onSuccess = {
                                                diagnosticsError = null
                                                diagnosticsText = it.ifBlank { "No hay errores registrados." }
                                            },
                                            onFailure = {
                                                diagnosticsError = it.message ?: "Error leyendo el diagnóstico"
                                                diagnosticsText = ""
                                            }
                                        )
                                        showDiagnostics = true
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
                        val home = settings.searchEngine.baseUrl
                        currentUrl = home
                        inputUrl = home
                        val tabIndex = browserTabs.indexOfFirst { it.id == activeTabId }
                        if (tabIndex >= 0) {
                            browserTabs[tabIndex] = browserTabs[tabIndex].copy(
                                url = home,
                                title = "Inicio"
                            )
                        }
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
                    onClick = {
                        ephemeralMode = !ephemeralMode
                        settings = settings.copy(privateMode = ephemeralMode)
                        BrowserPreferences.save(context, settings)
                    },
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
                    defaultDownloadEngine = settings.downloadEngine,
                    idmThreads = settings.idmThreads,
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
                        startBgDownload(context, item.url, "idm", settings.idmThreads)
                    },
                    onDownloadYtDlp = { item ->
                        startBgDownload(context, item.url, "ytdlp", settings.idmThreads)
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

        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("Ajustes", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(14.dp))

                    SettingsSectionTitle("Motor de búsqueda")
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings = settings.copy(searchEngine = engine)
                                    BrowserPreferences.save(context, settings)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.searchEngine == engine,
                                onClick = {
                                    settings = settings.copy(searchEngine = engine)
                                    BrowserPreferences.save(context, settings)
                                }
                            )
                            Text(engine.label)
                        }
                    }

                    HorizontalDivider()
                    SettingsSectionTitle("Navegación y privacidad")
                    SettingSwitch(
                        title = "Bloqueador de anuncios",
                        description = "Activa el bloqueo integrado de anuncios, rastreo y minería.",
                        checked = settings.adBlockEnabled,
                        onCheckedChange = {
                            settings = settings.copy(adBlockEnabled = it)
                            BrowserPreferences.save(context, settings)
                        }
                    )
                    SettingSwitch(
                        title = "Protección contra rastreo",
                        description = "Limita identificadores y rastreo entre sitios.",
                        checked = settings.trackingEnabled,
                        onCheckedChange = {
                            settings = settings.copy(trackingEnabled = it)
                            BrowserPreferences.save(context, settings)
                        }
                    )
                    SettingSwitch(
                        title = "JavaScript",
                        description = "Desactivarlo puede romper sitios modernos.",
                        checked = settings.javaScriptEnabled,
                        onCheckedChange = {
                            settings = settings.copy(javaScriptEnabled = it)
                            BrowserPreferences.save(context, settings)
                        }
                    )
                    SettingSwitch(
                        title = "Bloquear ventanas emergentes",
                        description = "Impide la apertura automática de nuevas ventanas.",
                        checked = settings.blockPopups,
                        onCheckedChange = {
                            settings = settings.copy(blockPopups = it)
                            BrowserPreferences.save(context, settings)
                        }
                    )

                    SettingsSectionTitle("Cookies")
                    CookiePolicy.entries.forEach { policy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings = settings.copy(cookiePolicy = policy)
                                    BrowserPreferences.save(context, settings)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.cookiePolicy == policy,
                                onClick = {
                                    settings = settings.copy(cookiePolicy = policy)
                                    BrowserPreferences.save(context, settings)
                                }
                            )
                            Text(policy.label)
                        }
                    }

                    HorizontalDivider()
                    SettingsSectionTitle("Descargas")
                    DownloadEngine.entries.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings = settings.copy(downloadEngine = engine)
                                    BrowserPreferences.save(context, settings)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.downloadEngine == engine,
                                onClick = {
                                    settings = settings.copy(downloadEngine = engine)
                                    BrowserPreferences.save(context, settings)
                                }
                            )
                            Text(engine.label)
                        }
                    }
                    if (settings.downloadEngine == DownloadEngine.IDM) {
                        Text("Hilos IDM: ${settings.idmThreads}")
                        Slider(
                            value = settings.idmThreads.toFloat(),
                            onValueChange = { settings = settings.copy(idmThreads = it.toInt()) },
                            onValueChangeFinished = { BrowserPreferences.save(context, settings) },
                            valueRange = 2f..16f,
                            steps = 13
                        )
                    }

                    HorizontalDivider()
                    SettingsSectionTitle("Tema y color")
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings = settings.copy(themeMode = mode)
                                    BrowserPreferences.save(context, settings)
                                    (context as? MainActivity)?.recreate()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode,
                                onClick = {
                                    settings = settings.copy(themeMode = mode)
                                    BrowserPreferences.save(context, settings)
                                    (context as? MainActivity)?.recreate()
                                }
                            )
                            Text(mode.label)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(AppAccent.entries) { accent ->
                            val accentColor = androidx.compose.ui.graphics.Color(accent.primaryHex)
                            IconButton(onClick = {
                                settings = settings.copy(accent = accent)
                                BrowserPreferences.save(context, settings)
                                (context as? MainActivity)?.recreate()
                            }) {
                                Surface(
                                    modifier = Modifier.size(38.dp),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = accentColor,
                                    border = androidx.compose.foundation.BorderStroke(
                                        2.dp,
                                        if (settings.accent == accent)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.outline
                                    )
                                ) {}
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showSettingsSheet = false }
                    ) {
                        Text("Cerrar ajustes")
                    }
                }
            }
        }

        if (showDiagnostics) {
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                title = { Text("Diagnóstico de C-Browxr") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "El registro se guarda solo en este dispositivo. Revísalo antes de compartirlo."
                        )
                        diagnosticsError?.let {
                            Text(
                                text = "No se pudo leer: $it",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            SelectionContainer {
                                Text(
                                    text = diagnosticsText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = diagnosticsError == null && diagnosticsText.isNotBlank(),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Diagnóstico C-Browxr", diagnosticsText)
                            )
                            Toast.makeText(context, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copiar")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            enabled = diagnosticsError == null && diagnosticsText != "No hay errores registrados.",
                            onClick = {
                                val app = context.applicationContext as CBrowxrApp
                                app.clearDiagnostics().fold(
                                    onSuccess = {
                                        diagnosticsText = "No hay errores registrados."
                                        diagnosticsError = null
                                    },
                                    onFailure = {
                                        diagnosticsError = it.message ?: "Error borrando el diagnóstico"
                                    }
                                )
                            }
                        ) {
                            Text("Borrar")
                        }
                        TextButton(onClick = { showDiagnostics = false }) {
                            Text("Cerrar")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SnifferSheetContent(
    items: List<SniffedMedia>,
    defaultDownloadEngine: DownloadEngine,
    idmThreads: Int,
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
                            if (defaultDownloadEngine == DownloadEngine.YTDLP) {
                                Button(onClick = { onDownloadYtDlp(item) }) {
                                    Text("yt-dlp")
                                }
                                OutlinedButton(onClick = { onDownloadIdm(item) }) {
                                    Text("IDM ${idmThreads}x")
                                }
                            } else {
                                Button(onClick = { onDownloadIdm(item) }) {
                                    Text("IDM ${idmThreads}x")
                                }
                                OutlinedButton(onClick = { onDownloadYtDlp(item) }) {
                                    Text("yt-dlp")
                                }
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

private data class BrowserTabState(
    val id: Int,
    val title: String,
    val url: String
)

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun tabTitle(rawUrl: String): String {
    val host = Uri.parse(rawUrl).host?.removePrefix("www.")
    return host?.takeIf { it.isNotBlank() } ?: "Nueva pestaña"
}

private fun formatAddress(raw: String, searchEngine: SearchEngine): String {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> searchEngine.searchUrl(trimmed)
    }
}

private fun startBgDownload(context: Context, url: String, mode: String, threads: Int = 8) {
    val intent = Intent(context, DownloadForegroundService::class.java).apply {
        putExtra("url", url)
        putExtra("mode", mode)
        putExtra("threads", threads.coerceIn(2, 16))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Toast.makeText(context, "Descarga iniciada en segundo plano", Toast.LENGTH_SHORT).show()
}
