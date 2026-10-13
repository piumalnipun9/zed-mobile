package dev.zed.mobile

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
import androidx.compose.material3.Icon
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
        ZedBrandMark(size = 36.dp, contentDescription = "Zed")
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        SectionLabel("Connection")
        SettingRow("Desktop", state.host.ifBlank { "Not connected" }, monospace = true)
        Hairline(inset = 16.dp)
        SettingRow("Project", state.project?.name ?: "None selected")
        state.selectedThread?.let {
            Hairline(inset = 16.dp)
            SettingRow("Thread", it.title.ifBlank { "Untitled thread" })
        }

        SectionLabel("Notifications")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onStayConnectedChange(!stayConnected) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = "Stay connected for Agent notifications" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Stay connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Get notified when an Agent task finishes or fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Muted,
                )
            }
            Switch(
                checked = stayConnected,
                onCheckedChange = onStayConnectedChange,
                colors = quietSwitchColors(),
                modifier = Modifier.scale(0.8f),
            )
        }

        SectionLabel("Privacy")
        SettingRow("Credential", "Encrypted with Android Keystore")
        Hairline(inset = 16.dp)
        SettingRow("Screenshots", "Blocked while the app is open")
        Hairline(inset = 16.dp)
        SettingRow("Desktop access", "Open and recently opened projects only")

        Spacer(Modifier.height(24.dp))
        Hairline()
        Text(
            "Disconnect this phone",
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !state.busy, onClick = vm::disconnect)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = ZedColors.Danger,
        )
        Hairline()
        Text(
            "Removes this phone's credential. Remove the device in desktop settings to revoke it there too.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ZedColors.Faint,
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
    checkedTrackColor = ZedColors.Text,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = ZedColors.Subtle,
    uncheckedBorderColor = ZedColors.Hairline,
    uncheckedThumbColor = ZedColors.Faint,
)

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(ZedColors.Subtle)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Symbols.Search, contentDescription = null, tint = ZedColors.Faint, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ZedColors.Faint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ZedColors.Text),
                cursorBrush = SolidColor(ZedColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder },
            )
        }
    }
}

@Composable
internal fun ProjectScreen(state: MobileState, vm: ZedViewModel) {
    var query by remember { mutableStateOf("") }
    // The desktop catalog is already ordered by last opened time; keep that order.
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
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "connection") {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(if (desktopAvailable) ZedColors.Success else ZedColors.Warning)
                Text(
                    if (desktopAvailable) "Connected" else "Desktop unreachable · showing last known projects",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Muted,
                )
                if (desktopAvailable && state.host.isNotBlank()) {
                    Text(state.host, style = CodeTextStyle.copy(color = ZedColors.Faint), maxLines = 1)
                }
            }
        }
        state.notice?.let { notice -> item(key = "notice") { InlineNotice(notice) } }
        item(key = "search") { SearchField(query, { query = it }, "Search projects") }
        if (projects.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = if (state.projects.isEmpty()) "No projects yet" else "No matches",
                    detail = if (state.projects.isEmpty()) {
                        "Open a project in desktop Zed once and it will appear here."
                    } else {
                        "Try another name or path."
                    },
                )
            }
        } else {
            item(key = "label") { SectionLabel(if (desktopAvailable) "Recent" else "Last known") }
        }
        itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
            ProjectRow(
                project = project,
                enabled = !state.busy && desktopAvailable,
                onClick = { vm.openProject(project) },
            )
            if (index < projects.lastIndex) Hairline(inset = 52.dp)
        }
    }
}

@Composable
private fun ProjectRow(project: Project, enabled: Boolean, onClick: () -> Unit) {
    val remote = project.kind != PROJECT_KIND_LOCAL
    val status = when {
        project.opening -> "Connecting…"
        project.isOpen -> null
        else -> "Not open"
    }
    ListRow(
        title = project.name,
        subtitle = if (remote) "${project.sourceLabel()} · ${project.path}" else project.path,
        subtitleMonospace = !remote,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = when {
                project.opening -> "Project ${project.name} is connecting on desktop"
                project.isOpen -> "Open project ${project.name}"
                else -> "Open ${project.name} on desktop"
            }
        },
        leading = {
            Icon(
                if (remote) Symbols.Dns else Symbols.Folder,
                contentDescription = null,
                tint = if (project.isOpen) ZedColors.Text else ZedColors.Faint,
                modifier = Modifier.size(22.dp),
            )
        },
        trailing = {
            when {
                status != null -> Text(status, style = MaterialTheme.typography.bodySmall, color = ZedColors.Faint)
                else -> StatusDot(ZedColors.Success, size = 6.dp)
            }
        },
    )
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
internal fun ThreadScreen(state: MobileState, vm: ZedViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        state.notice?.let { notice -> item(key = "notice") { InlineNotice(notice) } }
        if (state.threads.isEmpty()) {
            item(key = "empty") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "No threads yet",
                        detail = "Start a thread to send your first prompt to ${state.project?.name ?: "this project"}.",
                    )
                    SmallButton("New thread", onClick = vm::requestNewThread, enabled = !state.busy, tone = ButtonTone.Primary)
                }
            }
        } else {
            item(key = "label") { SectionLabel("${state.threads.size} ${if (state.threads.size == 1) "thread" else "threads"}") }
        }
        itemsIndexed(state.threads, key = { _, thread -> thread.id }) { index, thread ->
            ListRow(
                title = thread.title.ifBlank { "Untitled thread" },
                subtitle = listOfNotNull(
                    thread.agent?.takeIf { !it.isNative }?.name,
                    humanStatus(thread.status.ifBlank { "idle" }),
                ).joinToString(" · "),
                enabled = !state.busy,
                onClick = { vm.openThread(thread) },
                modifier = Modifier.semantics { contentDescription = "Open thread ${thread.title.ifBlank { "Untitled thread" }}" },
                leading = { StatusDot(statusColor(thread.status)) },
            )
            if (index < state.threads.lastIndex) Hairline(inset = 35.dp)
        }
    }
    if (state.agentPickerVisible) {
        AgentPickerSheet(state.agents, enabled = !state.busy, onPick = vm::createThread, onDismiss = vm::dismissAgentPicker)
    }
}

@Composable
private fun AgentPickerSheet(
    agents: List<AgentInfo>,
    enabled: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    QuietSheet(onDismiss, "New thread") {
        Text(
            "External agents use their own sign-in and models on desktop.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = ZedColors.Muted,
        )
        agents.forEach { agent ->
            ListRow(
                title = agent.name,
                subtitle = if (agent.isNative) "Built-in" else "External agent",
                enabled = enabled,
                onClick = { onPick(if (agent.isNative) null else agent.id) },
                modifier = Modifier.semantics { contentDescription = "Start a thread with ${agent.name}" },
            )
        }
    }
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
    val directory = file.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
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
