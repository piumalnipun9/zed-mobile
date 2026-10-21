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
                            Page.SETTINGS -> vm.showSettings()
                            else -> Unit
                        }
                    }
                },
                onOpenRecent = { recent ->
                    drawerScope.launch {
                        drawerState.close()
                        vm.openThread(recent.thread, recent.project)
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

private const val DEBUG_PAIRING_QR_EXTRA = "dev.zed.mobile.DEBUG_PAIRING_QR"
