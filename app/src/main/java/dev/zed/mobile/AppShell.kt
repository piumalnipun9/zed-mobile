package dev.zed.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The visible app frame for one [state]. It has no side effects of its own, so screenshot tests
 * can render any page from a fixed state without a desktop, DataStore or the event service.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ZedColors.Background,
            topBar = {
                if (state.page != Page.PAIR) ZedTopBar(state = state, vm = vm, onOpenDrawer = onOpenDrawer)
            },
            floatingActionButton = {
                if (state.page == Page.PROJECTS && !state.gatewayUnavailable && state.projects.any { it.isOpen }) {
                    ExtendedFloatingActionButton(
                        onClick = { vm.startNewThread() },
                        containerColor = ZedColors.Accent,
                        contentColor = Color.White,
                        shape = CircleShape,
                        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .semantics { contentDescription = "New thread" },
                        icon = { Icon(Symbols.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        text = { Text("New thread", style = MaterialTheme.typography.labelLarge) },
                    )
                }
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
                state.error?.let { InlineNotice(it, error = true) }
                PullToRefreshPage(state = state, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                    when (state.page) {
                        Page.PAIR -> PairScreen(
                            busy = state.busy,
                            scanning = scanning,
                            scannerFeedback = scannerFeedback ?: state.notice,
                            onScanQr = onScanQr,
                        )

                        Page.PROJECTS -> HomeScreen(state = state, vm = vm)
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
        state.newThread?.let { NewThreadSheet(state, it, vm) }
        Toast(message = state.toast, onDismiss = vm::clearToast, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** A dark pill near the bottom that explains an action that did not stick, then goes away. */
@Composable
private fun Toast(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(4_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(ZedColors.Text)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Symbols.Error, contentDescription = null, tint = Color(0xFFFFB3B3), modifier = Modifier.size(18.dp))
            Text(shown.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

/**
 * Pulling down asks desktop again. The spinner appears only for a pull the user started; the
 * background refreshes that follow navigation stay invisible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefreshPage(
    state: MobileState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val refreshable = state.page == Page.PROJECTS || state.page == Page.CHANGES
    if (!refreshable) {
        Box(modifier = modifier) { content() }
        return
    }
    val loading = state.refreshing || state.busy
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { if (!loading) pulled = false }
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = pulled && loading,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = pulled && loading,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = ZedColors.Surface,
                color = ZedColors.Accent,
            )
        },
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZedTopBar(state: MobileState, vm: ZedViewModel, onOpenDrawer: () -> Unit) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = ZedColors.Background,
        scrolledContainerColor = ZedColors.Background,
        titleContentColor = ZedColors.Text,
        navigationIconContentColor = ZedColors.Text,
        actionIconContentColor = ZedColors.Text,
    )
    val back: @Composable () -> Unit = {
        IconButton(onClick = vm::back) {
            Icon(Symbols.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
        }
    }
    when (state.page) {
        Page.PROJECTS -> CenterAlignedTopAppBar(
            title = { Text("Zed", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Symbols.Menu, contentDescription = "Open navigation menu", modifier = Modifier.size(22.dp))
                }
            },
            colors = colors,
        )

        Page.CHAT -> CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.snapshot?.title?.takeIf(String::isNotBlank)
                            ?: state.selectedThread?.title?.takeIf(String::isNotBlank)
                            ?: "New thread",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ChatSubtitle(state)
                }
            },
            navigationIcon = back,
            colors = colors,
        )

        else -> CenterAlignedTopAppBar(
            title = {
                Text(
                    when (state.page) {
                        Page.CHANGES -> "Changes"
                        Page.SETTINGS -> "Settings"
                        else -> "Zed"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            },
            navigationIcon = back,
            colors = colors,
        )
    }
}

/** Project and live turn state under the chat title, e.g. "zed · Working". */
@Composable
private fun ChatSubtitle(state: MobileState) {
    // The live snapshot status wins; the summary's status is only as fresh as when it was listed.
    val status = state.snapshot?.status ?: state.selectedThread?.status
    val working = state.turn.isActive || status == "initializing"
    val (label, color) = when {
        state.turnPhase == TurnPhase.AWAITING_APPROVAL -> "Waiting for you" to ZedColors.Warning
        working -> "Working" to ZedColors.AccentText
        else -> null to ZedColors.Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.project?.let {
            Text(it.name, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (label != null) {
            StatusDot(color, size = 5.dp)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
        }
    }
}

/** Claude-style drawer: destinations, then recent threads with their project, then connection. */
@Composable
internal fun MobileNavigationDrawer(
    state: MobileState,
    onDestination: (Page) -> Unit,
    onOpenRecent: (RecentThread) -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(304.dp)
            .fillMaxHeight(),
        drawerContainerColor = ZedColors.Background,
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 28.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Mascot(size = 30.dp)
                Text("Zed", style = MaterialTheme.typography.headlineSmall)
            }
            DrawerItem(Symbols.Projects, "Projects", state.page == Page.PROJECTS) { onDestination(Page.PROJECTS) }
            DrawerItem(Symbols.Settings, "Settings", state.page == Page.SETTINGS) { onDestination(Page.SETTINGS) }
            Hairline(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            val recents = state.recents
            if (recents.isNotEmpty()) {
                Text(
                    "Recents",
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZedColors.Muted,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(recents.take(30), key = { it.thread.id }) { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (state.selectedThread?.id == recent.thread.id) ZedColors.Subtle else Color.Transparent)
                            .clickable { onOpenRecent(recent) }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .semantics { contentDescription = "Open thread ${recent.thread.title.ifBlank { "New thread" }} in ${recent.project.name}" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Symbols.ChatBubble, contentDescription = null, tint = ZedColors.Muted, modifier = Modifier.size(16.dp))
                        Text(
                            recent.thread.title.ifBlank { "New thread" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A fixed column so every tag starts at the same point, whatever the title length.
                        Box(modifier = Modifier.width(76.dp)) {
                            ProjectTag(recent.project.name)
                        }
                    }
                }
            }
            Hairline()
            ConnectionLine(state, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) ZedColors.Subtle else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ZedColors.Text, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Laptop icon plus "Connected" in green, or a crossed-out laptop and "Disconnected". */
@Composable
internal fun ConnectionLine(state: MobileState, modifier: Modifier = Modifier, showHost: Boolean = true) {
    val connected = !state.gatewayUnavailable && state.host.isNotBlank()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (connected) Symbols.Laptop else Symbols.LaptopOff,
            contentDescription = null,
            tint = if (connected) ZedColors.Success else ZedColors.Faint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (connected) "Connected" else "Disconnected",
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) ZedColors.Success else ZedColors.Muted,
        )
        if (!showHost) return@Row
        Spacer(Modifier.width(2.dp))
        Text(
            desktopLabel(state.host),
            style = MaterialTheme.typography.bodySmall,
            color = ZedColors.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "100.99.221.90:7331" rather than the full origin; the scheme means nothing to people. */
internal fun desktopLabel(host: String): String =
    host.removePrefix("http://").removePrefix("https://").ifBlank { "Zed Desktop" }
