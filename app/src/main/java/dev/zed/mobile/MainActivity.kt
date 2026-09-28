package dev.zed.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val ZedPrimary = Color(0xFF884C39)
private val ZedSurface = Color(0xFFFFF8F6)
private val ZedSurfaceLow = Color(0xFFFEF1ED)
private val ZedSurfaceLowest = Color(0xFFFFFFFF)
private val ZedSurfaceHigh = Color(0xFFF3E5E2)
private val ZedOutlineVariant = Color(0xFFD8C2BC)
private val ZedTertiary = Color(0xFF2E6463)

class MainActivity : ComponentActivity() {
    private var notificationPermissionRequested = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied permission leaves the foreground connection active but suppresses event notifications.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val vm: ZedViewModel = viewModel()
            val scanner = remember { AndroidQrCodeScanner(this@MainActivity) }
            val scope = rememberCoroutineScope()
            var scanning by remember { mutableStateOf(false) }
            var scannerFeedback by remember { mutableStateOf<String?>(null) }
            val currentState by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(currentState.page) {
                if (currentState.page != Page.PAIR) scannerFeedback = null
            }

            ZedTheme {
                ZedApp(
                    vm = vm,
                    scanning = scanning,
                    scannerFeedback = scannerFeedback,
                    onNotificationPermissionNeeded = ::requestNotificationPermissionIfNeeded,
                    onScanQr = {
                        if (!scanning) {
                            scope.launch {
                                scannerFeedback = null
                                scanning = true
                                try {
                                    val qrValue = scanner.scan()
                                    scannerFeedback = "QR code scanned. Pairing this device…"
                                    vm.pairQr(qrValue)
                                } catch (_: CancellationException) {
                                    scannerFeedback =
                                        "Scan cancelled. You can scan the pairing code when you are ready."
                                } catch (error: Exception) {
                                    scannerFeedback = error.message
                                        ?: "The QR scanner could not read that pairing code. Try again."
                                } finally {
                                    scanning = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ZedTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = ZedPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA56450),
        onPrimaryContainer = Color(0xFFFFFBFF),
        secondary = Color(0xFF77574D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFD3C7),
        onSecondaryContainer = Color(0xFF2C150F),
        tertiary = ZedTertiary,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFB5EDEB),
        onTertiaryContainer = Color(0xFF00201F),
        background = ZedSurface,
        onBackground = Color(0xFF211A18),
        surface = ZedSurface,
        onSurface = Color(0xFF211A18),
        surfaceVariant = Color(0xFFEDE0DC),
        onSurfaceVariant = Color(0xFF53433F),
        surfaceTint = ZedPrimary,
        inverseSurface = Color(0xFF362F2C),
        inverseOnSurface = Color(0xFFFCEEEA),
        inversePrimary = Color(0xFFFFB59F),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF85736E),
        outlineVariant = ZedOutlineVariant,
    )
    val baseType = Typography()
    val typography = baseType.copy(
        headlineMedium = baseType.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = baseType.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = baseType.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = baseType.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = baseType.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = baseType.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = baseType.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = baseType.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = baseType.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = androidx.compose.material3.Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZedApp(
    vm: ZedViewModel = viewModel(),
    scanning: Boolean = false,
    scannerFeedback: String? = null,
    onNotificationPermissionNeeded: () -> Unit = {},
    onScanQr: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current.applicationContext
    val connectionSettings = remember(appContext) { AndroidConnectionSettingsStore(appContext) }
    var stayConnected by remember(connectionSettings) { mutableStateOf<Boolean?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val settingsScope = rememberCoroutineScope()

    LaunchedEffect(connectionSettings) {
        connectionSettings.stayConnected.collect { stayConnected = it }
    }
    LaunchedEffect(state.hasPersistentPairing, stayConnected) {
        when {
            state.hasPersistentPairing && stayConnected == true -> {
                GatewayEventService.start(appContext)
                onNotificationPermissionNeeded()
            }

            stayConnected != null -> GatewayEventService.stop(appContext)
        }
    }
    LaunchedEffect(vm) {
        vm.restorePersistentPairing()
    }
    LaunchedEffect(lifecycleOwner, state.page, state.selectedThread?.id) {
        if (state.page == Page.CHAT) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.pollThread()
                    delay(2_000)
                }
            }
        }
    }

    BackHandler(
        enabled = drawerState.isOpen || state.page in listOf(
            Page.THREADS,
            Page.CHAT,
            Page.CHANGES,
            Page.SETTINGS
        )
    ) {
        if (drawerState.isOpen) {
            drawerScope.launch { drawerState.close() }
        } else {
            vm.back()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.page != Page.PAIR,
        drawerContent = {
            if (state.page != Page.PAIR) {
                MobileNavigationDrawer(
                    state = state,
                    onDestination = { page ->
                        drawerScope.launch {
                            drawerState.close()
                            when (page) {
                                Page.PROJECTS -> vm.showProjects()
                                Page.THREADS -> vm.showThreads()
                                Page.CHAT -> vm.showChat()
                                Page.SETTINGS -> vm.showSettings()
                                else -> Unit
                            }
                        }
                    },
                    onDisconnect = {
                        drawerScope.launch {
                            drawerState.close()
                            vm.disconnect()
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                ZedTopBar(
                    state = state,
                    vm = vm,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .imePadding(),
            ) {
                if (state.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Working" },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                state.error?.let { Feedback(text = it, error = true) }
                Box(modifier = Modifier.weight(1f)) {
                    when (state.page) {
                        Page.PAIR -> PairScreen(
                            busy = state.busy,
                            scanning = scanning,
                            scannerFeedback = scannerFeedback,
                            onScanQr = onScanQr,
                        )

                        Page.PROJECTS -> ProjectScreen(state = state, vm = vm)
                        Page.THREADS -> ThreadScreen(state = state, vm = vm)
                        Page.CHAT -> ChatScreen(state = state, vm = vm)
                        Page.CHANGES -> ChangesScreen(state = state, refresh = vm::refresh)
                        Page.SETTINGS -> SettingsScreen(
                            state = state,
                            vm = vm,
                            stayConnected = stayConnected ?: true,
                            onStayConnectedChange = { enabled ->
                                settingsScope.launch {
                                    connectionSettings.setStayConnected(enabled)
                                    if (!enabled) GatewayEventService.stop(appContext)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZedTopBar(state: MobileState, vm: ZedViewModel, onOpenDrawer: () -> Unit) {
    val title = when (state.page) {
        Page.PAIR -> "Zed Mobile"
        Page.PROJECTS -> "Projects"
        Page.THREADS -> "Threads"
        Page.CHAT -> "Agent"
        Page.CHANGES -> "Changes"
        Page.SETTINGS -> "Settings"
    }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.page == Page.PAIR) {
                    ZedMark(size = 32.dp)
                }
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (state.page != Page.PAIR) {
                ToolbarIconButton(
                    label = "Open navigation menu",
                    glyph = "☰",
                    onClick = onOpenDrawer,
                    enabled = !state.busy,
                )
            }
        },
        actions = {
            if (state.page != Page.PAIR) {
                ToolbarIconButton(
                    label = "Refresh",
                    glyph = "↻",
                    onClick = vm::refresh,
                    enabled = !state.busy,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun MobileNavigationDrawer(
    state: MobileState,
    onDestination: (Page) -> Unit,
    onDisconnect: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight(),
        drawerContainerColor = ZedSurfaceLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Z", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
                Column {
                    Text("Zed Mobile", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paired desktop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 20.dp),
                shape = MaterialTheme.shapes.medium,
                color = ZedSurfaceLow,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "CURRENT PROJECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        state.project?.name ?: "Choose a project",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DrawerDestination("□", "Projects", state.page == Page.PROJECTS, true) { onDestination(Page.PROJECTS) }
            DrawerDestination(
                "◌",
                "Threads",
                state.page == Page.THREADS,
                state.project != null
            ) { onDestination(Page.THREADS) }
            DrawerDestination(
                "✦",
                "Agent",
                state.page == Page.CHAT,
                state.selectedThread != null
            ) { onDestination(Page.CHAT) }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.55f))
            DrawerDestination("⚙", "Settings", state.page == Page.SETTINGS, true) { onDestination(Page.SETTINGS) }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text("Disconnect this phone")
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    glyph: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = { if (enabled) onClick() },
        icon = { Text(glyph, fontSize = 20.sp) },
        modifier = Modifier
            .padding(vertical = 2.dp)
            .semantics { contentDescription = if (enabled) label else "$label requires a project or thread" },
    )
}

@Composable
private fun PairScreen(
    busy: Boolean,
    scanning: Boolean,
    scannerFeedback: String?,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "Z",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text("Your desktop,\nwithin reach.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Scan the pairing QR code shown by Zed on your desktop to securely connect this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = ZedSurfaceLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PresenceDot(color = ZedTertiary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Private pairing", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "The QR code creates a per-device credential. Its secret is never displayed or saved in a screenshot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        scannerFeedback?.let { Feedback(text = it) }
        Button(
            onClick = onScanQr,
            enabled = !busy && !scanning,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (scanning) "Opening scanner…" else "Scan pairing QR code")
        }
        Text(
            "Use the Google Code Scanner when the pairing code is visible in desktop Zed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "No desktop URL or token is required on mobile.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsScreen(
    state: MobileState,
    vm: ZedViewModel,
    stayConnected: Boolean,
    onStayConnectedChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This phone connects only to the desktop you paired with its QR code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCard {
            SettingsDetail("Desktop", state.host.ifBlank { "Not connected" })
            HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.45f))
            SettingsDetail("Current project", state.project?.name ?: "No project selected")
            state.selectedThread?.let { thread ->
                HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.45f))
                SettingsDetail("Current thread", thread.title.ifBlank { "Untitled thread" })
            }
        }
        SettingsCard {
            StayConnectedSetting(
                checked = stayConnected,
                onCheckedChange = onStayConnectedChange,
            )
        }

        Text("Privacy", style = MaterialTheme.typography.headlineSmall)
        SettingsCard {
            SettingsDetail("Credential storage", "Encrypted with Android Keystore")
            HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.45f))
            SettingsDetail("Screenshots", "Blocked while Zed Mobile is open")
            HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.45f))
            SettingsDetail("Desktop access", "Open projects and recently opened local projects")
        }

        Text("Manage pairing", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Disconnecting removes this phone's local credential. Remove the device in desktop Zed's Mobile Gateway settings to revoke it from the desktop too.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = vm::disconnect,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Disconnect this phone")
        }
    }
}

@Composable
private fun StayConnectedSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics {
                contentDescription = "Stay connected for Agent notifications"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Stay connected for Agent notifications", style = MaterialTheme.typography.labelLarge)
            Text(
                "Keep a secure direct connection to your paired Zed-Desktop while this phone is in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
        border = CardDefaults.outlinedCardBorder()
            .copy(brush = androidx.compose.ui.graphics.SolidColor(ZedOutlineVariant.copy(alpha = 0.65f))),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDetail(label: String, value: String) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProjectScreen(state: MobileState, vm: ZedViewModel) {
    var query by remember { mutableStateOf("") }
    // The authenticated desktop catalog is already ordered by last opened time. Preserve that
    // order in the cache and UI so the most recent project remains at the top.
    val projects = remember(state.projects, query) {
        state.projects.filter {
            query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.path.contains(query, ignoreCase = true) ||
                    it.kind.contains(query, ignoreCase = true) ||
                    it.connectionName?.contains(query, ignoreCase = true) == true
        }
    }
    val desktopAvailable = !state.gatewayUnavailable
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionHeader(
            host = state.host,
            detail = if (desktopAvailable) "Workspace active" else "Gateway unavailable",
        )
        state.notice?.let { Feedback(text = it) }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Search projects") },
            placeholder = { Text("Projects or repositories") },
            leadingIcon = { Text("⌕", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            colors = warmTextFieldColors(),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (projects.isEmpty()) {
                item {
                    EmptyCard(
                        title = if (state.projects.isEmpty()) "No projects yet" else "No matching projects",
                        detail = if (state.projects.isEmpty()) {
                            "Open a project in desktop Zed once. It will then be available here."
                        } else {
                            "Try a different project name or path."
                        },
                    )
                }
            }
            if (projects.isNotEmpty()) {
                item {
                    ProjectSectionLabel(
                        if (desktopAvailable) "Recent projects" else "Last known projects",
                    )
                }
            }
            itemsIndexed(projects, key = { _, project -> project.id }) { _, project ->
                ProjectCard(
                    project = project,
                    enabled = !state.busy && desktopAvailable,
                    desktopAvailable = desktopAvailable,
                    onClick = { vm.openProject(project) },
                )
            }
        }
    }
}

@Composable
private fun ProjectSectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProjectCard(
    project: Project,
    enabled: Boolean,
    desktopAvailable: Boolean,
    onClick: () -> Unit,
) {
    val source = project.sourceLabel()
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = when {
                    !desktopAvailable -> "Desktop gateway unavailable for project ${project.name}"
                    project.opening -> "Project ${project.name} is connecting on Desktop from $source"
                    project.isOpen -> "Open project ${project.name} from $source"
                    else -> "Open $project.name on Desktop from $source"
                }
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = CardDefaults.outlinedCardBorder()
            .copy(brush = androidx.compose.ui.graphics.SolidColor(ZedOutlineVariant.copy(alpha = 0.6f))),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("□", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        project.path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Source · $source",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "•••",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            HorizontalDivider(color = ZedOutlineVariant.copy(alpha = 0.45f))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = when {
                        !desktopAvailable -> "Gateway unavailable"
                        project.opening -> "Connecting on Desktop"
                        project.isOpen -> "Open on Desktop"
                        else -> "Not open"
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !desktopAvailable -> "Reconnect to desktop"
                        project.opening -> "Connecting…"
                        project.isOpen -> "View threads  →"
                        else -> "Open on Desktop  →"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun Project.sourceLabel(): String {
    val source = when (kind) {
        PROJECT_KIND_SSH -> "SSH"
        PROJECT_KIND_WSL -> "WSL"
        PROJECT_KIND_DOCKER -> "Docker"
        PROJECT_KIND_PODMAN -> "Podman"
        else -> "Local"
    }
    return connectionName?.takeIf(String::isNotBlank)?.let { "$source · $it" } ?: source
}

@Composable
private fun ThreadScreen(state: MobileState, vm: ZedViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                color = ZedSurfaceLowest,
                border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("□", color = ZedTertiary, fontSize = 18.sp)
                    Text(
                        state.project?.name ?: "Project",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = vm::createThread,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("+  New")
            }
        }
        Text(
            "THREADS · ${state.threads.size}",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.threads.isEmpty()) {
                item {
                    EmptyCard(
                        title = "A fresh start",
                        detail = "Create a thread to send your first prompt to this project.",
                    )
                }
            }
            itemsIndexed(state.threads, key = { _, thread -> thread.id }) { _, thread ->
                ThreadCard(thread = thread, enabled = !state.busy, onClick = { vm.openThread(thread) })
            }
        }
    }
}

@Composable
private fun ThreadCard(thread: ThreadSummary, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Open thread ${thread.title.ifBlank { "Untitled thread" }}" },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = CardDefaults.outlinedCardBorder()
            .copy(brush = androidx.compose.ui.graphics.SolidColor(ZedOutlineVariant.copy(alpha = 0.65f))),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresenceDot(color = statusColor(thread.status))
                Text(
                    thread.title.ifBlank { "Untitled thread" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label = thread.status.ifBlank { "Unknown status" })
                Spacer(Modifier.weight(1f))
                Text(
                    "Open agent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(state: MobileState, vm: ZedViewModel) {
    val messages = state.snapshot?.messages.orEmpty()
    val transcript = state.snapshot?.transcript.orEmpty()
    val hasRichTranscript = transcript.isNotEmpty()
    val listState = rememberLazyListState()
    val nearBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount == 0 ||
                    (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: 0) >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    var configurationRequested by remember(state.selectedThread?.id) { mutableStateOf(false) }
    var showConfiguration by remember(state.selectedThread?.id) { mutableStateOf(false) }

    LaunchedEffect(configurationRequested, state.busy) {
        if (configurationRequested && !state.busy) {
            showConfiguration = true
            configurationRequested = false
        }
    }
    LaunchedEffect(transcript.size, messages.size, state.approvals.size, state.snapshot?.id) {
        if (nearBottom && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ThreadContextHeader(
            state = state,
            onConfigure = {
                if (!state.busy) {
                    vm.loadModels()
                    configurationRequested = true
                }
            },
        )
        state.pollError?.let { Feedback(text = "Snapshot unavailable · $it", error = true) }
        state.notice?.let { Feedback(text = it) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasRichTranscript) {
                itemsIndexed(transcript, key = { itemIndex, entry -> "${entry.index}:$itemIndex" }) { _, entry ->
                    TranscriptEntryItem(entry = entry)
                }
            } else if (messages.isEmpty() && state.approvals.isEmpty()) {
                item {
                    EmptyCard(
                        title = if (state.snapshot == null) "Connecting to thread" else "What should Zed work on?",
                        detail = if (state.snapshot == null) {
                            "Messages will appear here when desktop Zed responds."
                        } else {
                            "Send a prompt below to begin this thread."
                        },
                    )
                }
            } else {
                itemsIndexed(messages) { _, message ->
                    MessageBubble(message = message)
                }
            }
            if (state.approvals.isNotEmpty()) {
                item(key = "approval-heading") {
                    Text(
                        "PENDING APPROVALS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                itemsIndexed(state.approvals, key = { _, approval -> approval.toolCallId }) { _, approval ->
                    ApprovalCard(approval = approval, busy = state.busy, vm = vm)
                }
            }
        }
        Composer(state = state, vm = vm)
    }

    if (showConfiguration) {
        ThreadConfigurationSheet(
            state = state,
            vm = vm,
            onDismiss = { showConfiguration = false },
        )
    }
}

@Composable
private fun ThreadContextHeader(state: MobileState, onConfigure: () -> Unit) {
    val title = state.snapshot?.title ?: state.selectedThread?.title.orEmpty().ifBlank { "Untitled thread" }
    val status = state.snapshot?.status ?: state.selectedThread?.status.orEmpty()
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label = state.project?.name ?: "Project")
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onConfigure,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Configure")
                }
            }
            state.configuration?.model?.let { model ->
                Text(
                    "${model.providerName} · ${model.modelName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresenceDot(color = statusColor(status))
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.lastUpdated?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                        ?: "Syncing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TranscriptEntryItem(entry: TranscriptEntry) {
    val kind = entry.kind.lowercase()
    when {
        entry.planEntries.isNotEmpty() || kind.contains("plan") -> PlanTranscriptCard(entry)
        kind.contains("compact") -> CompactionTranscriptCard(entry)
        entry.toolCallId != null || entry.toolName != null || kind.contains("tool") ||
                entry.rawInput != null || entry.rawOutput != null -> ToolTranscriptCard(entry)

        isThoughtKind(kind) -> ThoughtBlock(text = transcriptTextBlocks(entry).joinToString("\n"))
        kind.contains("user") -> {
            transcriptTextBlocks(entry).forEach { text ->
                TranscriptMessageBubble(role = "user", text = text)
            }
        }

        else -> AssistantTranscriptEntry(entry)
    }
}

@Composable
private fun AssistantTranscriptEntry(entry: TranscriptEntry) {
    entry.text?.takeIf { it.isNotEmpty() }?.let { text ->
        TranscriptMessageBubble(role = "assistant", text = text)
    }
    entry.chunks.forEach { chunk ->
        if (isThoughtKind(chunk.kind.lowercase())) {
            ThoughtBlock(text = chunk.text)
        } else if (chunk.text.isNotEmpty()) {
            TranscriptMessageBubble(role = "assistant", text = chunk.text)
        }
    }
}

@Composable
private fun PlanTranscriptCard(entry: TranscriptEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.titleMedium)
                entry.status?.let {
                    Spacer(Modifier.weight(1f))
                    StatusPill(label = it)
                }
            }
            entry.text?.takeIf { it.isNotEmpty() }?.let { text ->
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
            entry.planEntries.forEach { plan ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PresenceDot(color = statusColor(plan.status))
                    SelectionContainer {
                        Text(plan.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusPill(label = plan.status)
                }
            }
        }
    }
}

@Composable
private fun CompactionTranscriptCard(entry: TranscriptEntry) {
    val text = transcriptTextBlocks(entry).joinToString("\n")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = ZedSurfaceHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Context compacted", style = MaterialTheme.typography.titleMedium)
            if (text.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTranscriptCard(entry: TranscriptEntry) {
    val title = entry.title ?: entry.toolName ?: "Tool"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "Tool",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                entry.status?.let { StatusPill(label = it) }
            }
            entry.toolName?.takeIf { it.isNotEmpty() }?.let { toolName ->
                Text(
                    toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            entry.content.forEach { content ->
                SelectionContainer { Text(content, style = MaterialTheme.typography.bodyMedium) }
            }
            entry.rawInput?.let { RawPayload(label = "Raw input", value = it) }
            entry.rawOutput?.let { RawPayload(label = "Raw output", value = it) }
        }
    }
}

@Composable
private fun ThoughtBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = if (expanded) "Hide assistant thought" else "Show assistant thought" },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Thought",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = ZedTertiary
                )
                Text(
                    if (expanded) "Hide  ↑" else "Show  ↓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded && text.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ToolApproval, busy: Boolean, vm: ZedViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Approval needed", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StatusPill(label = "Pending")
            }
            Text(approval.title, style = MaterialTheme.typography.bodyLarge)
            approval.toolName?.takeIf { it.isNotEmpty() }?.let { toolName ->
                Text(
                    toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            approval.content.forEach { content ->
                SelectionContainer { Text(content, style = MaterialTheme.typography.bodyMedium) }
            }
            approval.rawInput?.let { RawPayload(label = "Raw input", value = it) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.resolveApproval(approval.toolCallId, allow = false) },
                    enabled = !busy && approval.denyOnceAvailable,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = { vm.resolveApproval(approval.toolCallId, allow = true) },
                    enabled = !busy && approval.allowOnceAvailable,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun RawPayload(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            SelectionContainer {
                Text(
                    value,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadConfigurationSheet(state: MobileState, vm: ZedViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = state.configuration
    val selectedModel = configuration?.model
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZedSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Thread configuration", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose the desktop model and its supported thinking controls for this thread.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text("Model", style = MaterialTheme.typography.titleMedium)
            if (state.models.isEmpty()) {
                EmptyCard(
                    title = "No models available",
                    detail = "No authenticated desktop models are available for this project.",
                )
            } else {
                state.models.forEach { model ->
                    ModelChoiceCard(
                        model = model,
                        selected = selectedModel?.providerId == model.providerId && selectedModel.modelId == model.modelId,
                        enabled = !state.busy,
                        onSelect = { vm.updateConfiguration(model = model) },
                    )
                }
            }
            ThinkingControls(
                model = selectedModel,
                configuration = configuration,
                busy = state.busy,
                onThinkingEnabledChange = { enabled -> vm.updateConfiguration(thinkingEnabled = enabled) },
                onEffortSelected = { effort -> vm.updateConfiguration(thinkingEffort = effort) },
                onClearEffort = { vm.updateConfiguration(clearThinkingEffort = true) },
            )
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun ModelChoiceCard(model: ModelCapability, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Select model ${model.providerName} ${model.modelName}" },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else ZedSurfaceLowest,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else ZedOutlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.modelName, style = MaterialTheme.typography.titleMedium)
                Text(
                    model.providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (model.supportsThinking) StatusPill(label = "Thinking")
        }
    }
}

@Composable
private fun ThinkingControls(
    model: ModelCapability?,
    configuration: ThreadConfiguration?,
    busy: Boolean,
    onThinkingEnabledChange: (Boolean) -> Unit,
    onEffortSelected: (String) -> Unit,
    onClearEffort: () -> Unit,
) {
    Text("Thinking", style = MaterialTheme.typography.titleMedium)
    when {
        model == null -> Text(
            "Choose a model to configure thinking.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        !model.supportsThinking -> Text(
            "This model does not support thinking controls.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {
            val thinkingEnabled = configuration?.thinkingEnabled ?: true
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, ZedOutlineVariant.copy(alpha = 0.65f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Enable thinking", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (model.supportsDisablingThinking) {
                                "Let the model reason before it responds."
                            } else {
                                "This model keeps thinking enabled."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = onThinkingEnabledChange,
                        enabled = !busy && (!thinkingEnabled || model.supportsDisablingThinking),
                    )
                }
            }
            if (thinkingEnabled && model.effortLevels.isNotEmpty()) {
                Text("Thinking effort", style = MaterialTheme.typography.labelLarge)
                ThinkingEffortChoice(
                    title = "Model default",
                    selected = configuration?.thinkingEffort == null,
                    enabled = !busy,
                    onClick = onClearEffort,
                )
                model.effortLevels.forEach { effort ->
                    ThinkingEffortChoice(
                        title = effort.name,
                        selected = configuration?.thinkingEffort == effort.value,
                        enabled = !busy,
                        onClick = { onEffortSelected(effort.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingEffortChoice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Select thinking effort $title" },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else ZedSurfaceLowest,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else ZedOutlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    TranscriptMessageBubble(role = message.role, text = message.text)
}

@Composable
private fun TranscriptMessageBubble(role: String, text: String) {
    val fromUser = role.equals("user", ignoreCase = true)
    val label = if (fromUser) "You" else "Zed"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = if (fromUser) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
            },
            color = if (fromUser) MaterialTheme.colorScheme.secondaryContainer else ZedSurfaceLowest,
            shadowElevation = if (fromUser) 0.dp else 1.dp,
            border = if (fromUser) null else androidx.compose.foundation.BorderStroke(
                1.dp,
                ZedOutlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fromUser) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.tertiary,
                )
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun Composer(state: MobileState, vm: ZedViewModel) {
    Surface(
        color = ZedSurfaceLowest,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = vm::showChanges,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Changes", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = vm::cancel,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Cancel run", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextField(
                value = state.draft,
                onValueChange = vm::editDraft,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                label = { Text("Message Zed") },
                placeholder = { Text("Ask about this thread") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = warmTextFieldColors(),
            )
            Button(
                onClick = vm::send,
                enabled = !state.busy && state.draft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Text("Send prompt")
            }
        }
    }
}

private fun isThoughtKind(kind: String): Boolean =
    kind.contains("thought") || kind.contains("think") || kind.contains("reason")

private fun transcriptTextBlocks(entry: TranscriptEntry): List<String> {
    val blocks = mutableListOf<String>()
    entry.text?.takeIf { it.isNotEmpty() }?.let(blocks::add)
    entry.chunks.forEach { chunk ->
        if (chunk.text.isNotEmpty()) blocks += chunk.text
    }
    return blocks
}

@Composable
private fun ChangesScreen(state: MobileState, refresh: () -> Unit) {
    var expanded by remember(state.selectedThread?.id) { mutableStateOf(setOf<Int>()) }
    val horizontalScroll = rememberScrollState()
    val lines = remember(state.files) { state.files.map { it.diff.lines() } }
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Read-only review", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Changes reported by desktop Zed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = refresh,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Refresh")
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.files.isEmpty()) {
                item {
                    EmptyCard(
                        title = "No changed files",
                        detail = "Refresh after desktop Zed makes edits in this thread.",
                    )
                }
            }
            state.files.forEachIndexed { fileIndex, file ->
                item(key = "file:$fileIndex") {
                    ChangedFileCard(
                        file = file,
                        expanded = fileIndex in expanded,
                        onClick = {
                            expanded = if (fileIndex in expanded) expanded - fileIndex else expanded + fileIndex
                        },
                    )
                }
                if (fileIndex in expanded) {
                    if (file.diff.isEmpty()) {
                        item(key = "empty:$fileIndex") {
                            Text(
                                "No textual diff was supplied for this file.",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        itemsIndexed(lines[fileIndex]) { _, line ->
                            SelectionContainer {
                                Text(
                                    text = line.ifEmpty { " " },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(horizontalScroll)
                                        .background(diffBackground(line))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = diffForeground(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFileCard(file: ChangedFile, expanded: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${if (expanded) "Hide" else "Show"} diff for ${file.path}" },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZedSurfaceLowest),
        border = CardDefaults.outlinedCardBorder()
            .copy(brush = androidx.compose.ui.graphics.SolidColor(ZedOutlineVariant.copy(alpha = 0.65f))),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                file.path,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = ZedTertiary.copy(alpha = 0.12f)) {
                    Text(
                        "+${file.additions}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZedTertiary,
                    )
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "−${file.deletions}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Hide diff  ↑" else "Read diff  ↓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ConnectionHeader(host: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceDot(color = ZedPrimary)
        Text(
            detail.uppercase(),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            host,
            modifier = Modifier.widthIn(max = 164.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        shape = CircleShape,
        color = statusColor(label).copy(alpha = 0.12f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PresenceDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun ToolbarIconButton(label: String, glyph: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label },
    ) {
        Text(glyph, fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ZedMark(size: androidx.compose.ui.unit.Dp) {
    Surface(modifier = Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(contentAlignment = Alignment.Center) {
            Text("Z", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Feedback(text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = ZedSurfaceLow),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun warmTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = ZedSurfaceLowest,
    unfocusedContainerColor = ZedSurfaceLowest,
    disabledContainerColor = ZedSurfaceHigh,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = ZedOutlineVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

private fun statusColor(status: String): Color = when {
    status.contains("error", ignoreCase = true) || status.contains("failed", ignoreCase = true) -> Color(0xFFBA1A1A)
    status.contains("run", ignoreCase = true) || status.contains("work", ignoreCase = true) || status.contains(
        "active",
        ignoreCase = true
    ) -> ZedTertiary

    else -> ZedPrimary
}

@Composable
private fun diffForeground(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> MaterialTheme.colorScheme.primary
    line.startsWith("+") -> Color(0xFF216739)
    line.startsWith("-") -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun diffBackground(line: String): Color = when {
    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFFE6F4EA)
    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFFEDEC)
    else -> Color.Transparent
}
