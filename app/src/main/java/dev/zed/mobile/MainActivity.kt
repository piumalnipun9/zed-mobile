package dev.zed.mobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zeddesktop.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            LaunchedEffect(Unit) {
                consumeDebugPairingQr()?.let { vm.pairQr(it) }
            }
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

    /**
     * Debug builds only: emulators without Play services cannot run the QR scanner, so a test can
     * pass the same one-time `ZEDM2:` value with `adb shell am start --es`. It is validated exactly
     * like a scanned code.
     */
    private fun consumeDebugPairingQr(): String? {
        if (!BuildConfig.DEBUG) return null
        val value = intent?.getStringExtra(DEBUG_PAIRING_QR_EXTRA) ?: return null
        intent.removeExtra(DEBUG_PAIRING_QR_EXTRA)
        return value
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
    var allowScreenshots by remember(connectionSettings) { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val settingsScope = rememberCoroutineScope()

    LaunchedEffect(connectionSettings) {
        connectionSettings.stayConnected.collect { stayConnected = it }
    }
    LaunchedEffect(connectionSettings) {
        connectionSettings.allowScreenshots.collect { allowScreenshots = it }
    }
    // onCreate sets FLAG_SECURE before anything is drawn; it is lifted only after the stored
    // preference says the user opted in.
    LaunchedEffect(activity, allowScreenshots) {
        val window = activity?.window ?: return@LaunchedEffect
        if (allowScreenshots) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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
            // Always compose the sheet: the drawer measures its closed offset from the sheet's
            // width, so an empty sheet on the pairing page would leave it stuck open afterwards.
            // Gestures are disabled and no menu button is shown while pairing.
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
        },
    ) {
        ZedAppContent(
            state = state,
            vm = vm,
            scanning = scanning,
            scannerFeedback = scannerFeedback,
            onScanQr = onScanQr,
            stayConnected = stayConnected ?: true,
            onStayConnectedChange = { enabled ->
                settingsScope.launch {
                    connectionSettings.setStayConnected(enabled)
                    if (!enabled) GatewayEventService.stop(appContext)
                }
            },
            allowScreenshots = allowScreenshots,
            onAllowScreenshotsChange = { allowed ->
                settingsScope.launch { connectionSettings.setAllowScreenshots(allowed) }
            },
            onOpenDrawer = { drawerScope.launch { drawerState.open() } },
        )
    }
}

/**
 * The visible app frame for one [state]. It has no side effects of its own, so screenshot tests
 * can render any page from a fixed state without a desktop, DataStore or the event service.
 */
@Composable
internal fun ZedAppContent(
    state: MobileState,
    vm: ZedViewModel,
    scanning: Boolean = false,
    scannerFeedback: String? = null,
    onScanQr: () -> Unit = {},
    stayConnected: Boolean = true,
    onStayConnectedChange: (Boolean) -> Unit = {},
    allowScreenshots: Boolean = false,
    onAllowScreenshotsChange: (Boolean) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
) {
    Scaffold(
        containerColor = ZedColors.Background,
        topBar = {
            if (state.page != Page.PAIR) ZedTopBar(state = state, vm = vm, onOpenDrawer = onOpenDrawer)
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                // The keyboard inset already includes the navigation bar that contentPadding
                // reserved; consume it so the composer sits just above the keyboard.
                .consumeWindowInsets(contentPadding)
                .imePadding(),
        ) {
            // A hairline progress track replaces the divider while a request is in flight.
            if (state.page != Page.PAIR) {
                if (state.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .semantics { contentDescription = "Working" },
                        color = ZedColors.Text,
                        trackColor = ZedColors.Hairline,
                    )
                } else {
                    Hairline()
                }
            }
            state.error?.let { InlineNotice(it, error = true) }
            PullToRefreshPage(state = state, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                when (state.page) {
                    Page.PAIR -> PairScreen(
                        busy = state.busy,
                        scanning = scanning,
                        scannerFeedback = scannerFeedback ?: state.notice,
                        onScanQr = onScanQr,
                    )

                    Page.PROJECTS -> ProjectScreen(state = state, vm = vm)
                    Page.THREADS -> ThreadScreen(state = state, vm = vm)
                    Page.CHAT -> ChatScreen(state = state, vm = vm)
                    Page.CHANGES -> ChangesScreen(state = state, vm = vm)
                    Page.SETTINGS -> SettingsScreen(
                        state = state,
                        vm = vm,
                        stayConnected = stayConnected,
                        onStayConnectedChange = onStayConnectedChange,
                        allowScreenshots = allowScreenshots,
                        onAllowScreenshotsChange = onAllowScreenshotsChange,
                    )
                }
            }
        }
    }
}

/**
 * Lists update when a page opens; pulling down asks desktop again. Chat polls on its own, so it
 * is the one page without the gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefreshPage(
    state: MobileState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val refreshable = state.page == Page.PROJECTS || state.page == Page.THREADS || state.page == Page.CHANGES
    if (!refreshable) {
        Box(modifier = modifier) { content() }
        return
    }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) { if (!state.busy) pulled = false }
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = pulled && state.busy,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = pulled && state.busy,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = ZedColors.Surface,
                color = ZedColors.Text,
            )
        },
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZedTopBar(state: MobileState, vm: ZedViewModel, onOpenDrawer: () -> Unit) {
    val nested = state.page == Page.CHAT || state.page == Page.CHANGES
    val title = when (state.page) {
        Page.PAIR -> "Zed"
        Page.PROJECTS -> "Projects"
        Page.THREADS -> state.project?.name ?: "Threads"
        Page.CHAT -> state.snapshot?.title ?: state.selectedThread?.title.orEmpty().ifBlank { "New thread" }
        Page.CHANGES -> "Changes"
        Page.SETTINGS -> "Settings"
    }
    val subtitle = when (state.page) {
        Page.THREADS -> "Threads"
        Page.CHAT -> state.project?.name
        Page.CHANGES -> state.selectedThread?.title?.takeIf(String::isNotBlank)
        else -> null
    }
    val chatStatus = if (state.page == Page.CHAT) {
        when {
            state.turnPhase == TurnPhase.AWAITING_APPROVAL -> ZedColors.Warning
            state.turn.isActive -> ZedColors.Accent
            else -> null
        }
    } else {
        null
    }
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null || chatStatus != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chatStatus?.let { StatusDot(it, size = 6.dp) }
                        Text(
                            listOfNotNull(
                                subtitle,
                                chatStatus?.let { turnPhaseLabel(state.turnPhase) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = ZedColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            when {
                nested || state.page == Page.THREADS || state.page == Page.SETTINGS -> IconButton(onClick = vm::back, enabled = !state.busy) {
                    Icon(Symbols.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
                }

                state.page != Page.PAIR -> IconButton(onClick = onOpenDrawer, enabled = !state.busy) {
                    Icon(Symbols.Menu, contentDescription = "Open navigation menu", modifier = Modifier.size(22.dp))
                }
            }
        },
        actions = {
            when (state.page) {
                Page.THREADS -> IconButton(onClick = vm::requestNewThread, enabled = !state.busy) {
                    Icon(Symbols.NewThread, contentDescription = "New thread", modifier = Modifier.size(20.dp))
                }

                Page.CHAT -> IconButton(onClick = onOpenDrawer, enabled = !state.busy) {
                    Icon(Symbols.MoreHoriz, contentDescription = "Open navigation menu", modifier = Modifier.size(22.dp))
                }

                else -> Unit
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ZedColors.Background,
            scrolledContainerColor = ZedColors.Background,
            titleContentColor = ZedColors.Text,
            navigationIconContentColor = ZedColors.Text,
            actionIconContentColor = ZedColors.Text,
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
            .width(300.dp)
            .fillMaxHeight(),
        drawerContainerColor = ZedColors.Background,
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZedBrandMark(size = 22.dp)
                Column {
                    Text("Zed Mobile", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.host.ifBlank { "Paired desktop" },
                        style = CodeTextStyle.copy(color = ZedColors.Faint),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DrawerItem(Symbols.Folder, "Projects", state.page == Page.PROJECTS, true) { onDestination(Page.PROJECTS) }
            DrawerItem(
                Symbols.List,
                state.project?.name?.let { "Threads · $it" } ?: "Threads",
                state.page == Page.THREADS,
                state.project != null,
            ) { onDestination(Page.THREADS) }
            DrawerItem(
                Symbols.ChatBubble,
                state.selectedThread?.title?.takeIf(String::isNotBlank) ?: "Current thread",
                state.page == Page.CHAT,
                state.selectedThread != null,
            ) { onDestination(Page.CHAT) }
            Spacer(Modifier.weight(1f))
            DrawerItem(Symbols.Settings, "Settings", state.page == Page.SETTINGS, true) { onDestination(Page.SETTINGS) }
            DrawerItem(Symbols.Logout, "Disconnect", selected = false, enabled = true, tint = ZedColors.Danger, onClick = onDisconnect)
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tint: Color = ZedColors.Text,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) tint else ZedColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = { Icon(icon, contentDescription = null, tint = if (enabled) tint else ZedColors.Faint, modifier = Modifier.size(20.dp)) },
        selected = selected,
        onClick = { if (enabled) onClick() },
        shape = MaterialTheme.shapes.small,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = ZedColors.Subtle,
            unselectedContainerColor = Color.Transparent,
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .height(44.dp)
            .semantics { contentDescription = if (enabled) label else "$label requires a project or thread" },
    )
}

private const val DEBUG_PAIRING_QR_EXTRA = "dev.zed.mobile.DEBUG_PAIRING_QR"
