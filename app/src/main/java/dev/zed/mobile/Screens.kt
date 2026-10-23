package dev.zed.mobile

import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PairScreen(
    busy: Boolean,
    scanning: Boolean,
    scannerFeedback: String?,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        Mascot(size = 72.dp)
        Spacer(Modifier.height(28.dp))
        Text("Connect to Zed Desktop", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Continue Agent threads from your phone over your private Tailscale network.",
            style = MaterialTheme.typography.bodyLarge,
            color = ZedColors.Muted,
        )
        Spacer(Modifier.height(32.dp))
        PairingStep("1", "On desktop, open Settings → Mobile Gateway.")
        PairingStep("2", "Choose Connect mobile device to show a QR code.")
        PairingStep("3", "Scan it here. The code works once and expires in two minutes.")
        Spacer(Modifier.height(28.dp))
        SmallButton(
            text = if (scanning) "Opening scanner…" else "Scan pairing code",
            onClick = onScanQr,
            enabled = !busy && !scanning,
            tone = ButtonTone.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
        scannerFeedback?.let {
            Spacer(Modifier.height(4.dp))
            InlineNotice(it, modifier = Modifier.padding(horizontal = 0.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Only this phone receives a credential. Nothing is typed or shared, and you can revoke it from desktop at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = ZedColors.Faint,
        )
    }
}

@Composable
private fun PairingStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(number, style = MaterialTheme.typography.bodyLarge, color = ZedColors.Faint, modifier = Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ZedColors.Text)
    }
}

@Composable
internal fun SettingsScreen(
    state: MobileState,
    vm: ZedViewModel,
    stayConnected: Boolean,
    onStayConnectedChange: (Boolean) -> Unit,
    allowScreenshots: Boolean = false,
    onAllowScreenshotsChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 32.dp),
    ) {
        SettingsLabel("Desktop")
        CardGroup {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Zed Desktop", style = MaterialTheme.typography.titleMedium)
                ConnectionLine(state)
            }
        }

        SettingsLabel("Notifications")
        CardGroup {
            SwitchRow(
                title = "Stay connected",
                detail = "Get notified when an agent task finishes or fails.",
                checked = stayConnected,
                onCheckedChange = onStayConnectedChange,
                description = "Stay connected for Agent notifications",
            )
        }

        SettingsLabel("Privacy")
        CardGroup {
            SwitchRow(
                title = "Allow screenshots",
                detail = "Off hides this app from screenshots, screen recording, and the app switcher.",
                checked = allowScreenshots,
                onCheckedChange = onAllowScreenshotsChange,
            )
            Hairline(inset = 16.dp)
            SettingRow("Credential", "Encrypted on this phone")
            Hairline(inset = 16.dp)
            SettingRow("Desktop access", "Open and recent projects")
        }

        Spacer(Modifier.height(28.dp))
        CardGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = vm::disconnect)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Symbols.Logout, contentDescription = null, tint = ZedColors.Danger, modifier = Modifier.size(20.dp))
                Text("Disconnect this phone", style = MaterialTheme.typography.titleMedium, color = ZedColors.Danger)
            }
        }
        Text(
            "Removes this phone's credential. Remove the device in desktop settings to revoke it there too.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ZedColors.Faint,
        )
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Mascot(size = 32.dp, mood = MascotMood.Sleeping)
            Text("Zed Mobile", style = MaterialTheme.typography.labelMedium, color = ZedColors.Faint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ZedColors.Muted,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String = title,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = quietSwitchColors(),
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (monospace) CodeTextStyle.copy(color = ZedColors.Muted) else MaterialTheme.typography.bodyMedium,
            color = ZedColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun quietSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = ZedColors.Accent,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = ZedColors.Subtle,
    uncheckedBorderColor = ZedColors.Hairline,
    uncheckedThumbColor = ZedColors.Faint,
)

@Composable
internal fun HomeScreen(state: MobileState, vm: ZedViewModel) {
    val recents = state.recents.take(4)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
        item(key = "desktop") { DesktopCard(state, onReconnect = vm::refresh) }
        state.notice?.let { notice -> item(key = "notice") { InlineNotice(notice) } }
        if (recents.isNotEmpty()) {
            item(key = "recents-label") { HomeSectionLabel("Recents") }
            item(key = "recents") {
                CardGroup {
                    recents.forEachIndexed { index, recent ->
                        ThreadRow(
                            thread = recent.thread,
                            projectName = recent.project.name,
                            onClick = { vm.openThread(recent.thread, recent.project) },
                        )
                        if (index < recents.lastIndex) Hairline(inset = 48.dp)
                    }
                }
            }
        }
        item(key = "projects-label") { HomeSectionLabel("Projects") }
        if (state.projects.isEmpty()) {
            item(key = "empty") {
                MascotMessage(
                    title = "No projects yet",
                    detail = "Open a project in Zed on your desktop and it will show up here.",
                )
            }
        } else {
            item(key = "projects") {
                CardGroup {
                    state.projects.forEachIndexed { index, project ->
                        ProjectEntry(state, project, vm)
                        if (index < state.projects.lastIndex) Hairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ZedColors.Muted,
    )
}

/** The paired desktop, with Claude-style connection status: a laptop icon and a clear word. */
@Composable
private fun DesktopCard(state: MobileState, onReconnect: () -> Unit) {
    val connected = !state.gatewayUnavailable
    CardGroup(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (connected) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(ZedColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) { Mascot(size = 28.dp) }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(ZedColors.Subtle),
                    contentAlignment = Alignment.Center,
                ) { Mascot(size = 28.dp, mood = MascotMood.Sleeping) }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Zed Desktop", style = MaterialTheme.typography.titleMedium)
                ConnectionLine(state, showHost = connected)
            }
            if (!connected) SmallButton("Reconnect", onClick = onReconnect, tone = ButtonTone.Secondary)
        }
        if (!connected) {
            Text(
                "Showing the last projects this phone saw. Check that Zed is running and Tailscale is on.",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = ZedColors.Muted,
            )
        }
    }
}

@Composable
private fun ProjectEntry(state: MobileState, project: Project, vm: ZedViewModel) {
    val expanded = project.id in state.expandedProjectIds
    val opening = project.id in state.openingProjectIds || project.opening
    val remote = project.kind != PROJECT_KIND_LOCAL
    val available = !state.gatewayUnavailable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available && !opening) { vm.toggleProject(project) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics {
                contentDescription = when {
                    opening -> "Project ${project.name} is opening on desktop"
                    !project.isOpen -> "Open ${project.name} on desktop"
                    expanded -> "Collapse project ${project.name}"
                    else -> "Open project ${project.name}"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            when {
                remote -> Symbols.Dns
                expanded -> Symbols.FolderOpen
                else -> Symbols.Folder
            },
            contentDescription = null,
            tint = ZedColors.Muted,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    opening -> "Opening on desktop…"
                    !project.isOpen && remote -> "${project.sourceLabel()} · Tap to open on desktop"
                    !project.isOpen -> "Not open · Tap to open on desktop"
                    remote -> "${project.sourceLabel()} · ${shortPath(project.path)}"
                    else -> shortPath(project.path)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (opening) ZedColors.AccentText else ZedColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            opening -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = ZedColors.Accent,
                strokeWidth = 1.75.dp,
            )
            project.isOpen -> {
                val count = state.projectThreads[project.id]?.size
                if (count != null && count > 0 && !expanded) {
                    Text("$count", style = MaterialTheme.typography.labelMedium, color = ZedColors.Faint)
                }
                Icon(
                    if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
                    contentDescription = null,
                    tint = ZedColors.Faint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    AnimatedVisibility(visible = expanded && project.isOpen) {
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            val threads = state.projectThreads[project.id]
            when {
                threads == null -> Text(
                    "Loading threads…",
                    modifier = Modifier.padding(start = 66.dp, top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Faint,
                )
                threads.isEmpty() -> Text(
                    "No threads yet",
                    modifier = Modifier.padding(start = 66.dp, top = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Faint,
                )
                else -> threads.forEach { thread ->
                    ThreadRow(thread = thread, projectName = null, indent = 50.dp, onClick = { vm.openThread(thread, project) })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.startNewThread(project.id) }
                    .padding(start = 66.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                    .semantics { contentDescription = "New thread in ${project.name}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Symbols.Add, contentDescription = null, tint = ZedColors.Text, modifier = Modifier.size(16.dp))
                Text("New thread", style = MaterialTheme.typography.labelLarge, color = ZedColors.AccentText)
            }
        }
    }
}

/** One thread: a chat mark, its title, and a quiet line with project, agent and status. */
@Composable
internal fun ThreadRow(
    thread: ThreadSummary,
    projectName: String?,
    onClick: () -> Unit,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val status = threadStatusLabel(thread.status)
    val agent = thread.agent?.takeIf { !it.isNative }?.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .semantics { contentDescription = "Open thread ${thread.title.ifBlank { "New thread" }}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Symbols.ChatBubble, contentDescription = null, tint = ZedColors.Muted, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                thread.title.ifBlank { "New thread" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (projectName != null || status != null || agent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projectName?.let { ProjectTag(it, modifier = Modifier.widthIn(max = 140.dp)) }
                    val detail = listOfNotNull(agent, status).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status != null) threadStatusColor(thread.status) else ZedColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (isActiveThreadStatus(thread.status)) StatusDot(threadStatusColor(thread.status), size = 7.dp)
    }
}

/** Asks only what is ambiguous: the project when several are open, then the agent. */
@Composable
internal fun NewThreadSheet(state: MobileState, request: NewThreadRequest, vm: ZedViewModel) {
    val project = request.projectId?.let { id -> state.projects.firstOrNull { it.id == id } }
    QuietSheet(onDismiss = vm::dismissNewThread, title = "New thread") {
        if (project == null) {
            Text(
                "Choose a project",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Muted,
            )
            val open = state.projects.filter { it.isOpen }
            if (open.isEmpty()) {
                InlineNotice("Open a project in Zed on your desktop first.")
            }
            open.forEach { candidate ->
                SheetChoice(
                    icon = if (candidate.kind != PROJECT_KIND_LOCAL) Symbols.Dns else Symbols.Folder,
                    title = candidate.name,
                    subtitle = shortPath(candidate.path),
                    onClick = { vm.startNewThread(candidate.id) },
                )
            }
        } else {
            Text(
                "Choose an agent for ${project.name}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Muted,
            )
            val agents = state.projectAgents[project.id].orEmpty().ifEmpty {
                listOf(AgentInfo("Zed Agent", "Zed Agent", isNative = true))
            }
            agents.forEach { agent ->
                SheetChoice(
                    icon = if (agent.isNative) Symbols.Sparkles else Symbols.Bot,
                    title = agent.name,
                    subtitle = if (agent.isNative) "Built into Zed" else "External agent · signs in on desktop",
                    onClick = { vm.createThread(project.id, if (agent.isNative) null else agent.id) },
                    description = "Start a thread with ${agent.name}",
                )
            }
        }
    }
}

@Composable
private fun SheetChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    description: String = title,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(ZedColors.Subtle),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = ZedColors.Text, modifier = Modifier.size(20.dp)) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun Project.sourceLabel(): String {
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
internal fun ChangesScreen(state: MobileState, vm: ZedViewModel) {
    var expanded by remember(state.selectedThread?.id) { mutableStateOf(setOf<String>()) }
    var confirmReject by remember { mutableStateOf<RejectRequest?>(null) }
    val horizontalScroll = rememberScrollState()
    val review = state.review
    val unreviewed = review.unreviewedFiles
    val actionsEnabled = !state.busy && !review.reviewing
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (unreviewed.size) {
                    0 -> if (state.files.isEmpty()) "No changes" else "All edits reviewed"
                    1 -> "1 file to review"
                    else -> "${unreviewed.size} files to review"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Muted,
            )
            if (unreviewed.isNotEmpty()) {
                SmallButton(
                    "Reject all",
                    onClick = { confirmReject = RejectRequest(paths = null) },
                    tone = ButtonTone.Danger,
                    enabled = actionsEnabled,
                )
                SmallButton(
                    "Keep all",
                    onClick = { vm.reviewChanges(keep = true) },
                    tone = ButtonTone.Primary,
                    enabled = actionsEnabled,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                )
            }
        }
        if (review.reviewing) androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
            color = ZedColors.Text,
            trackColor = ZedColors.Hairline,
        ) else Hairline()
        if (review.canUndoReject) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    review.notice ?: "Edits rejected.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Muted,
                )
                SmallButton("Undo", onClick = vm::undoReject, tone = ButtonTone.Quiet, enabled = actionsEnabled)
            }
            Hairline()
        } else {
            review.notice?.let { InlineNotice(it) }
        }
        review.error?.let { InlineNotice(it, error = true) }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.files.isEmpty()) {
                item {
                    EmptyState(
                        title = "No changed files",
                        detail = "Files the Agent edits in this thread appear here.",
                    )
                }
            }
            state.files.forEach { file ->
                val isExpanded = file.path in expanded
                val page = review.diffPages[file.path]
                item(key = "file:${file.path}") {
                    ChangedFileRow(
                        file = file,
                        expanded = isExpanded,
                        onClick = {
                            expanded = if (isExpanded) expanded - file.path else expanded + file.path
                            if (!isExpanded && file.truncated && page == null) vm.loadDiffPage(file.path)
                        },
                    )
                }
                if (isExpanded) {
                    val diffText = if (file.truncated) page?.diff.orEmpty() else file.diff
                    val lines = diffText.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
                    if (diffText.isEmpty() && !file.truncated) {
                        item(key = "empty:${file.path}") {
                            InlineNotice("No textual diff for this file.")
                        }
                    }
                    itemsIndexed(lines, key = { lineIndex, _ -> "line:${file.path}:$lineIndex" }) { _, line ->
                        DiffLine(line = line, horizontalScroll = horizontalScroll)
                    }
                    item(key = "actions:${file.path}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (file.truncated && (page == null || page.nextOffset != null)) {
                                val loading = file.path in review.loadingDiffPaths
                                SmallButton(
                                    when {
                                        loading -> "Loading…"
                                        page == null -> "Load diff"
                                        else -> "Load more · ${page.nextOffset}/${page.totalLines} lines"
                                    },
                                    onClick = { vm.loadDiffPage(file.path) },
                                    tone = ButtonTone.Quiet,
                                    enabled = !loading,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (file.unreviewed) {
                                SmallButton(
                                    "Reject",
                                    onClick = { confirmReject = RejectRequest(listOf(file.path)) },
                                    tone = ButtonTone.Danger,
                                    enabled = actionsEnabled,
                                )
                                SmallButton(
                                    "Keep",
                                    onClick = { vm.reviewChanges(keep = true, paths = listOf(file.path)) },
                                    tone = ButtonTone.Secondary,
                                    enabled = actionsEnabled,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "divider:${file.path}") { Hairline() }
            }
        }
    }

    confirmReject?.let { request ->
        AlertDialog(
            onDismissRequest = { confirmReject = null },
            containerColor = ZedColors.Surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    if (request.paths == null) "Reject all edits?" else "Reject edits to ${request.paths.first().substringAfterLast('/')}?",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    "Desktop Zed reverts these Agent edits. You can undo right after.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZedColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.reviewChanges(keep = false, paths = request.paths)
                    confirmReject = null
                }) { Text("Reject", color = ZedColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReject = null }) { Text("Cancel", color = ZedColors.Muted) }
            },
        )
    }
}

private data class RejectRequest(val paths: List<String>?)

@Composable
private fun ChangedFileRow(file: ChangedFile, expanded: Boolean, onClick: () -> Unit) {
    val name = file.path.substringAfterLast('/')
    val directory = shortPath(file.path).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "${if (expanded) "Hide" else "Show"} diff for ${file.path}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (file.unreviewed) StatusDot(ZedColors.Accent, size = 6.dp)
            }
            if (directory.isNotEmpty()) {
                Text(
                    directory,
                    style = CodeTextStyle.copy(color = ZedColors.Faint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("+${file.additions}", style = CodeTextStyle.copy(color = ZedColors.Success))
        Text("−${file.deletions}", style = CodeTextStyle.copy(color = ZedColors.Danger))
        Icon(
            if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
            contentDescription = null,
            tint = ZedColors.Faint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DiffLine(line: String, horizontalScroll: androidx.compose.foundation.ScrollState) {
    val (background, foreground) = when {
        line.startsWith("@@") -> ZedColors.Subtle to ZedColors.Muted
        line.startsWith("+++") || line.startsWith("---") -> Color.Transparent to ZedColors.Faint
        line.startsWith("+") -> ZedColors.AddedBackground to ZedColors.Text
        line.startsWith("-") -> ZedColors.RemovedBackground to ZedColors.Text
        else -> Color.Transparent to ZedColors.Text
    }
    SelectionContainer {
        Text(
            text = line.ifEmpty { " " },
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 16.dp, vertical = 1.dp),
            style = CodeTextStyle.copy(color = foreground),
            softWrap = false,
        )
    }
}
