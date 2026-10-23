package dev.zed.mobile

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Approval callbacks. [onResolve] is the one-time allow/deny shorthand for older desktops. */
class ApprovalHandlers(
    val onResolve: (String, Boolean) -> Unit,
    val onDecide: (String, ApprovalDecision) -> Unit,
)

class QuestionHandlers(
    val answeringIds: Set<String>,
    val errorsById: Map<String, String>,
    val onAnswer: (String, String, Map<String, ElicitationValue>) -> Unit,
)

@Composable
internal fun ChatScreen(state: MobileState, vm: ZedViewModel) {
    val messages = state.snapshot?.messages.orEmpty()
    val transcript = state.fullTranscript
    val threadId = state.selectedThread?.id
    val approvalHandlers = remember(vm) { ApprovalHandlers(vm::resolveApproval, vm::resolveApproval) }
    val questionHandlers = remember(vm, state.questions) {
        QuestionHandlers(state.questions.answeringIds, state.questions.errorsById, vm::answerElicitation)
    }
    val questionsById = remember(state.questions.elicitations) { state.questions.elicitations.associateBy { it.id } }
    val inlineQuestionIds = remember(transcript) { transcript.mapNotNullTo(mutableSetOf()) { it.elicitationId } }
    val fallbackQuestions = remember(state.questions.elicitations, inlineQuestionIds) {
        state.questions.elicitations.filter { it.id !in inlineQuestionIds }
    }
    val hasRichTranscript = transcript.isNotEmpty()
    val approvalsByToolCallId = remember(state.approvals) { state.approvals.associateBy { it.toolCallId } }
    val inlineApprovalIndexes = remember(transcript) {
        transcript.mapIndexedNotNull { index, entry -> entry.toolCallId?.let { it to index } }.toMap()
    }
    val fallbackApprovals = remember(state.approvals, inlineApprovalIndexes) {
        state.approvals.filter { it.toolCallId !in inlineApprovalIndexes }
    }
    val latestThought = remember(transcript, state.turnPhase, state.pendingMessage?.localId) {
        if (state.turnPhase == TurnPhase.RUNNING && state.pendingMessage == null) latestThoughtLocation(transcript) else null
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Rows before the transcript (the "load earlier" row) shift item positions in the list.
    val leadingItems = if (hasRichTranscript && state.olderTranscriptEntries > 0) 1 else 0
    val offscreenApproval by remember(state.approvals, inlineApprovalIndexes, listState, leadingItems) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index }
            state.approvals.firstOrNull { approval ->
                inlineApprovalIndexes[approval.toolCallId]?.let { it + leadingItems }?.let { index ->
                    listState.layoutInfo.totalItemsCount > index && index !in visible
                } == true
            }
        }
    }
    var showConfiguration by rememberSaveable(threadId) { mutableStateOf(false) }
    val openConfiguration = {
        if (!usesExternalAgent(state)) vm.loadModels()
        showConfiguration = true
    }

    // Opening a thread starts at its newest message. Wait until the list has laid out the loaded
    // entries (this effect runs in the same frame they arrive) and land at the very bottom of the
    // last item, so a long final reply is not cut off.
    val loadedItems = if (hasRichTranscript) transcript.size + leadingItems else messages.size
    // The transcript stays invisible until it is already positioned at its end, so opening a long
    // thread never shows the top first and then a fast scroll.
    var openedAtEnd by remember(threadId) { mutableStateOf(false) }
    LaunchedEffect(threadId, loadedItems > 0) {
        if (openedAtEnd || loadedItems == 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= loadedItems }
        listState.scrollToEnd(animated = false)
        openedAtEnd = true
    }
    // Follow new output while the reader is at the end; scrolling up pauses following.
    var followEnd by remember(threadId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (scrolling) followEnd = !canScrollForward
        }
    }
    LaunchedEffect(transcript.size, transcript.lastOrNull()?.chunks?.sumOf { it.text.length }, messages.size, state.turnPhase, state.approvals.size) {
        if (openedAtEnd && followEnd) {
            withFrameNanos { }
            // Only animate short steps. A long way from the end (for example when the full
            // transcript replaces the first partial one) an animated scroll would flash through the
            // whole thread, so land at the end directly.
            listState.scrollToEnd(animated = listState.isNearEnd())
        }
    }
    // Sending always returns to the end, where the new message appears.
    LaunchedEffect(state.pendingMessage?.localId) {
        if (state.pendingMessage == null) return@LaunchedEffect
        followEnd = true
        withFrameNanos { }
        listState.scrollToEnd()
    }
    val showJumpToEnd by remember { derivedStateOf { listState.canScrollForward } }

    Column(modifier = Modifier.fillMaxSize()) {
        state.pollError?.let { InlineNotice("Can't refresh this thread · $it", error = true) }
        when (state.snapshot?.status) {
            THREAD_STATUS_NEEDS_AUTHENTICATION -> InlineNotice(
                "${agentName(state)} needs a one-time sign-in. Open this thread in Zed Desktop, sign in there, then come back.",
                error = true,
            )
            "error" -> state.snapshot?.errorMessage?.let { InlineNotice(it, error = true) }
        }
        Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (openedAtEnd || loadedItems == 0) 1f else 0f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leadingItems > 0) {
                item(key = "load-older") {
                    LoadOlderRow(state.olderTranscriptEntries, state.history.loading, state.history.error, vm::loadOlderTranscript)
                }
            }
            if (hasRichTranscript) {
                itemsIndexed(transcript, key = { itemIndex, entry -> "entry:${threadId ?: "none"}:${entry.index}:$itemIndex" }) { itemIndex, entry ->
                    val toolDetailsKey = threadId?.let { id -> entry.toolCallId?.let { ToolDetailsKey(id, it) } }
                    TranscriptEntryItem(
                        entry = entry,
                        entryPosition = itemIndex,
                        cardKey = "entry:${threadId ?: "none"}:${entry.index}:$itemIndex",
                        toolCardKey = "tool:${threadId ?: "none"}:${entry.toolCallId ?: "entry:${entry.index}:$itemIndex"}",
                        approval = entry.toolCallId?.let(approvalsByToolCallId::get),
                        toolDetails = toolDetailsKey?.let(state.toolDetails.cache::get),
                        toolDetailsLoading = toolDetailsKey in state.toolDetails.loading,
                        toolDetailsError = toolDetailsKey?.let(state.toolDetails.errors::get),
                        isTurnActive = state.turnPhase == TurnPhase.RUNNING,
                        isLatestThought = latestThought,
                        resolvingToolCallIds = state.resolvingToolCallIds,
                        approvalErrors = state.approval.errorsByToolCallId,
                        approvalHandlers = approvalHandlers,
                        onLoadToolDetails = vm::loadToolDetails,
                        question = entry.elicitationId?.let(questionsById::get),
                        questionHandlers = questionHandlers,
                    )
                }
            } else if (messages.isEmpty() && state.approvals.isEmpty() && state.pendingMessage == null) {
                // The mascot overlay below covers the loading and empty states.
            } else {
                itemsIndexed(messages, key = { index, message -> "message:$index:${message.role}:${message.text.hashCode()}" }) { _, message ->
                    if (message.role.equals("user", ignoreCase = true)) UserMessage(message.text) else AssistantText(message.text)
                }
            }
            state.pendingMessage?.let { pending -> item(key = "pending:${pending.localId}") { PendingMessage(pending) } }
            // While waiting on the user, the pending approval or question itself is the indicator.
            val thinking = state.pendingMessage?.status.let { it == PendingMessageStatus.SENDING || it == PendingMessageStatus.SENT } ||
                (state.turn.isActive && state.turnPhase != TurnPhase.AWAITING_APPROVAL)
            if (thinking) {
                item(key = "thinking") {
                    ThinkingIndicator(
                        when {
                            state.turnPhase == TurnPhase.CANCELLING -> "Stopping…"
                            state.pendingMessage != null || !hasOutputSinceLastPrompt(transcript) -> "Thinking…"
                            else -> "Working…"
                        },
                    )
                }
            }
            state.turn.error?.takeIf { it != state.pendingMessage?.error }?.let { error ->
                item(key = "turn-error") { InlineNotice(error, error = true, modifier = Modifier.padding(horizontal = 0.dp)) }
            }
            itemsIndexed(fallbackQuestions, key = { _, question -> "fallback-question:${question.id}" }) { _, question ->
                QuestionBlock(question, questionHandlers)
            }
            itemsIndexed(fallbackApprovals, key = { _, approval -> "fallback:${approval.toolCallId}" }) { _, approval ->
                AttentionBlock {
                    ApprovalPanel(
                        approval = approval,
                        resolving = approval.toolCallId in state.resolvingToolCallIds,
                        error = state.approval.errorsByToolCallId[approval.toolCallId],
                        handlers = approvalHandlers,
                        showTitle = true,
                    )
                }
            }
        }
        val firstLoad = state.snapshot == null && state.pendingMessage == null
        val emptyThread = !firstLoad && !hasRichTranscript && messages.isEmpty() &&
            state.approvals.isEmpty() && state.pendingMessage == null
        when {
            firstLoad -> MascotMessage(
                title = if (state.selectedThread?.id.isNullOrEmpty()) "Starting a new thread…" else "Opening thread…",
                detail = "Getting the latest from ${state.project?.name ?: "your desktop"}",
                mood = MascotMood.Working,
                modifier = Modifier.align(Alignment.Center),
            )
            emptyThread -> MascotMessage(
                title = "What should we build?",
                detail = "Ask ${agentName(state)} to fix a bug, explain code, or make a change in ${state.project?.name ?: "this project"}.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = openedAtEnd && showJumpToEnd && !followEnd,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(ZedColors.Surface)
                    .border(1.dp, ZedColors.Hairline, CircleShape)
                    .clickable {
                        followEnd = true
                        scope.launch { listState.glideToEnd() }
                    }
                    .semantics { contentDescription = "Jump to latest" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Symbols.ArrowDown, contentDescription = null, tint = ZedColors.Text, modifier = Modifier.size(18.dp))
            }
        }
        }
        offscreenApproval?.let { approval ->
            ApprovalJumpBar(approval) {
                inlineApprovalIndexes[approval.toolCallId]?.let { index ->
                    scope.launch { listState.animateScrollToItem(index + leadingItems) }
                }
            }
        }
        Composer(
            state = state,
            vm = vm,
            onConfigure = openConfiguration,
        )
    }

    if (showConfiguration) ThreadConfigurationSheet(state, vm) { showConfiguration = false }
}

@Composable
private fun TranscriptEntryItem(
    entry: TranscriptEntry,
    entryPosition: Int,
    cardKey: String,
    toolCardKey: String,
    approval: ToolApproval?,
    toolDetails: ToolDetails?,
    toolDetailsLoading: Boolean,
    toolDetailsError: String?,
    isTurnActive: Boolean,
    isLatestThought: ThoughtLocation?,
    resolvingToolCallIds: Set<String>,
    approvalErrors: Map<String, String>,
    approvalHandlers: ApprovalHandlers,
    onLoadToolDetails: (String, Boolean) -> Unit,
    question: Elicitation?,
    questionHandlers: QuestionHandlers,
) {
    val kind = entry.kind.lowercase()
    when {
        question != null -> QuestionBlock(question, questionHandlers)
        entry.planEntries.isNotEmpty() || kind.contains("plan") -> PlanEntryView(entry)
        kind.contains("compact") -> CompactionView(entry)
        entry.toolCallId != null || entry.toolName != null || kind.contains("tool") ||
            entry.rawInput != null || entry.detailsAvailable -> ToolCallView(
            entry = entry,
            cardKey = toolCardKey,
            approval = approval,
            toolDetails = toolDetails,
            toolDetailsLoading = toolDetailsLoading,
            toolDetailsError = toolDetailsError,
            resolving = entry.toolCallId in resolvingToolCallIds,
            approvalError = entry.toolCallId?.let(approvalErrors::get),
            approvalHandlers = approvalHandlers,
            onLoadToolDetails = onLoadToolDetails,
        )

        isThoughtKind(kind) -> ThoughtView(
            text = transcriptTextBlocks(entry).joinToString("\n"),
            thoughtKey = "$cardKey:entry",
            active = isTurnActive && isLatestThought == ThoughtLocation(entryPosition, null),
        )

        kind.contains("user") -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            transcriptTextBlocks(entry).forEach { UserMessage(it) }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entry.text?.takeIf { it.isNotEmpty() }?.let { AssistantText(it) }
            entry.chunks.forEachIndexed { chunkIndex, chunk ->
                if (isThoughtKind(chunk.kind.lowercase())) {
                    ThoughtView(
                        text = chunk.text,
                        thoughtKey = "$cardKey:chunk:$chunkIndex",
                        active = isTurnActive && isLatestThought == ThoughtLocation(entryPosition, chunkIndex),
                    )
                } else if (chunk.text.isNotEmpty()) {
                    AssistantText(chunk.text)
                }
            }
        }
    }
}

@Composable
private fun UserMessage(text: String, footer: (@Composable () -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .padding(start = 48.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(ZedColors.Subtle)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = ZedColors.Text,
            )
        }
        footer?.invoke()
    }
}

@Composable
private fun AssistantText(text: String) {
    val body = MaterialTheme.typography.bodyLarge
    SelectionContainer {
        Markdown(
            content = text,
            modifier = Modifier.fillMaxWidth(),
            // Paragraphs and list items need air between them to read as separate thoughts.
            padding = markdownPadding(block = 10.dp, listItemBottom = 4.dp),
            colors = markdownColor(
                text = ZedColors.Text,
                codeText = ZedColors.Text,
                inlineCodeText = ZedColors.Text,
                linkText = ZedColors.Accent,
                codeBackground = ZedColors.Subtle,
                inlineCodeBackground = ZedColors.Subtle,
                dividerColor = ZedColors.Hairline,
            ),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.titleSmall,
                h5 = MaterialTheme.typography.titleSmall,
                h6 = MaterialTheme.typography.titleSmall,
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                quote = body.copy(color = ZedColors.Muted),
                code = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                inlineCode = body.copy(fontFamily = MonoFamily),
            ),
        )
    }
}

@Composable
private fun PendingMessage(pending: PendingMessage) {
    // A sent message looks like any other at once; only a failure is labelled.
    if (pending.status != PendingMessageStatus.FAILED) {
        UserMessage(pending.text)
        return
    }
    UserMessage(pending.text) {
        Text(
            pending.error?.let { "Not sent · $it" } ?: "Not sent. Your text is back in the box.",
            modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ZedColors.Danger,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Shown from the moment a prompt leaves the phone until the turn ends: a slowly turning asterisk
 * beside shimmering text, so the gap before the first model output never looks frozen.
 */
@Composable
private fun ThinkingIndicator(label: String) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "thinking-rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "thinking-pulse",
    )
    val shimmer by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "thinking-shimmer",
    )
    Row(
        modifier = Modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Symbols.Asterisk,
            contentDescription = null,
            tint = ZedColors.Brand,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = pulse
                    scaleY = pulse
                },
        )
        var width by remember { mutableStateOf(1f) }
        Text(
            label,
            modifier = Modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) },
            style = MaterialTheme.typography.bodyMedium.copy(
                brush = Brush.linearGradient(
                    colors = listOf(ZedColors.Muted, ZedColors.Hairline, ZedColors.Muted),
                    start = Offset(width * shimmer - width * 0.5f, 0f),
                    end = Offset(width * shimmer + width * 0.5f, 0f),
                ),
            ),
        )
    }
}

/** A thin vertical rule beside indented content, like a quote. */
@Composable
private fun Indented(content: @Composable () -> Unit) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .padding(start = 7.dp, end = 14.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(ZedColors.Hairline),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun DisclosureRow(
    expanded: Boolean,
    onClick: () -> Unit,
    description: String,
    leading: @Composable () -> Unit,
    label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
        label()
        Icon(
            if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
            contentDescription = null,
            tint = ZedColors.Faint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ThoughtView(text: String, thoughtKey: String, active: Boolean) {
    var expanded by rememberSaveable(thoughtKey) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DisclosureRow(
            expanded = expanded,
            onClick = { expanded = !expanded },
            description = if (expanded) "Hide thinking" else "Show thinking",
            leading = { if (active) ThinkingDots(ZedColors.Faint) else StatusDot(ZedColors.Faint, size = 5.dp) },
        ) {
            Text(
                if (active) "Thinking" else "Thought",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Muted,
            )
            if (active && !expanded) {
                Text(
                    thoughtPreview(text),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZedColors.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (expanded && text.isNotEmpty()) {
            Indented {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted)
                }
            }
        }
    }
}

/**
 * Zed's built-in agent sends terminal choices as allow/deny pairs that share one label ("Only this
 * time", "Always for terminal"), because the desktop shows that label in a dropdown beside separate
 * Allow and Deny buttons. Listed one per row, the pair would read identically, so name the action.
 */
internal fun optionLabel(option: PermissionChoice, all: List<PermissionChoice>): String {
    val shared = all.count { it.label == option.label } > 1
    if (!shared) return option.label
    val action = if (option.allows) "Allow" else "Deny"
    return "$action ${option.label.replaceFirstChar { it.lowercase() }}"
}

@Composable
private fun toolStatusIcon(status: String?): Pair<ImageVector?, Color> {
    val value = status.orEmpty().lowercase()
    return when {
        value.contains("complete") -> Symbols.Check to ZedColors.Success
        value.contains("fail") -> Symbols.Close to ZedColors.Danger
        value.contains("reject") || value.contains("cancel") -> Symbols.Block to ZedColors.Faint
        value.contains("approval") || value.contains("waiting") -> null to ZedColors.Warning
        value.contains("run") || value.contains("pending") || value.contains("progress") -> null to ZedColors.Accent
        else -> null to ZedColors.Faint
    }
}

@Composable
private fun ToolCallView(
    entry: TranscriptEntry,
    cardKey: String,
    approval: ToolApproval?,
    toolDetails: ToolDetails?,
    toolDetailsLoading: Boolean,
    toolDetailsError: String?,
    resolving: Boolean,
    approvalError: String?,
    approvalHandlers: ApprovalHandlers,
    onLoadToolDetails: (String, Boolean) -> Unit,
) {
    val title = entry.title ?: entry.toolName ?: "Tool"
    val legacyOutputAvailable = entry.content.any(String::isNotBlank) || !entry.rawOutput.isNullOrBlank()
    var expanded by rememberSaveable(cardKey) { mutableStateOf(false) }
    val status = if (approval != null) "waiting_for_approval" else entry.status
    val (statusIcon, statusTint) = toolStatusIcon(status)
    val running = statusIcon == null && statusTint == ZedColors.Accent

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DisclosureRow(
            expanded = expanded,
            onClick = {
                val willExpand = !expanded
                expanded = willExpand
                val toolCallId = entry.toolCallId
                // Only a user opening an ordinary collapsed card reads retained output from desktop.
                if (willExpand && !legacyOutputAvailable && entry.detailsAvailable && toolCallId != null && toolDetails == null) {
                    onLoadToolDetails(toolCallId, false)
                }
            },
            description = if (expanded) "Collapse tool $title" else "Expand tool $title",
            leading = {
                when {
                    statusIcon != null -> Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(15.dp))
                    running -> CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = statusTint)
                    else -> StatusDot(statusTint, size = 6.dp)
                }
            },
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Text,
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Indented {
                entry.toolName?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = CodeTextStyle.copy(color = ZedColors.Faint))
                }
                entry.content.filter(String::isNotBlank).forEach { CodeBlock(it) }
                entry.rawInput?.takeIf(String::isNotBlank)?.let { CodeBlock(it, label = "Input") }
                when {
                    legacyOutputAvailable -> entry.rawOutput?.takeIf(String::isNotBlank)?.let { CodeBlock(it, label = "Output") }
                    toolDetailsLoading -> Text("Loading output…", style = MaterialTheme.typography.bodySmall, color = ZedColors.Faint)
                    toolDetails?.available == true -> {
                        CodeBlock(toolDetails.output.orEmpty(), label = "Output")
                        if (toolDetails.truncated) {
                            Text("Preview only. Open on desktop for the full output.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Faint)
                        }
                    }
                    toolDetailsError != null && entry.toolCallId != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Couldn't load output",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = ZedColors.Danger,
                        )
                        SmallButton("Retry", onClick = { onLoadToolDetails(entry.toolCallId, true) }, tone = ButtonTone.Quiet)
                    }
                    else -> Text("No output recorded.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Faint)
                }
            }
        }
        approval?.let {
            AttentionBlock {
                ApprovalPanel(approval = it, resolving = resolving, error = approvalError, handlers = approvalHandlers, showTitle = false)
            }
        }
    }
}

/**
 * The approval prompt as a numbered list of the desktop's own options, in the spirit of a
 * terminal permission prompt: one tap per decision, no oversized buttons.
 */
@Composable
private fun ApprovalPanel(
    approval: ToolApproval,
    resolving: Boolean,
    error: String?,
    handlers: ApprovalHandlers,
    showTitle: Boolean,
) {
    var checkedPatterns by remember(approval.toolCallId) { mutableStateOf(setOf<Int>()) }
    val options = remember(approval) {
        approval.options.sortedBy { if (it.allows) 0 else 1 }
    }
    Column {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(ZedColors.Warning)
                Text(
                    if (approval.kind == "action_choice") "How should Zed continue?" else "Allow this action?",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (showTitle) {
                Text(approval.title, style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted)
            }
            approval.content.filter(String::isNotBlank).forEach { CodeBlock(it) }
            approval.rawInput?.takeIf(String::isNotBlank)?.let { CodeBlock(it) }
            approval.allowBlockedReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Danger)
            }
            if (approval.patterns.isNotEmpty()) {
                Text("Always-rules cover", style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    approval.patterns.forEach { pattern ->
                        val checked = pattern.index in checkedPatterns
                        PatternChip(
                            label = pattern.displayName.ifBlank { pattern.pattern },
                            checked = checked,
                            enabled = !resolving,
                        ) {
                            checkedPatterns = if (checked) checkedPatterns - pattern.index else checkedPatterns + pattern.index
                        }
                    }
                }
            }
        }
        Hairline()
        if (options.isNotEmpty()) {
            options.forEachIndexed { index, option ->
                val scope = if (option.isAlways && checkedPatterns.isNotEmpty()) " (selected patterns)" else ""
                OptionRow(
                    number = index + 1,
                    label = optionLabel(option, approval.options) + scope,
                    danger = !option.allows,
                    enabled = !resolving && option.enabled,
                ) {
                    val patterns = if (option.isAlways) checkedPatterns.sorted() else emptyList()
                    handlers.onDecide(approval.toolCallId, ApprovalDecision(option.optionId, patterns))
                }
                if (index < options.lastIndex) Hairline(inset = 40.dp)
            }
        } else {
            OptionRow(1, "Allow once", danger = false, enabled = !resolving && approval.allowOnceAvailable) {
                handlers.onResolve(approval.toolCallId, true)
            }
            Hairline(inset = 40.dp)
            OptionRow(2, "Deny", danger = true, enabled = !resolving && approval.denyOnceAvailable) {
                handlers.onResolve(approval.toolCallId, false)
            }
        }
        if (resolving) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                color = ZedColors.Text,
                trackColor = ZedColors.Hairline,
            )
        }
        error?.let { InlineNotice("Not sent · $it", error = true, modifier = Modifier.padding(horizontal = 0.dp)) }
    }
}

@Composable
private fun OptionRow(number: Int, label: String, danger: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$number",
            modifier = Modifier.width(14.dp),
            style = CodeTextStyle.copy(color = ZedColors.Faint),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> ZedColors.Faint
                danger -> ZedColors.Danger
                else -> ZedColors.Text
            },
        )
    }
}

@Composable
private fun PatternChip(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (checked) ZedColors.Text else ZedColors.Subtle)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (checked) Icon(Symbols.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text(label, style = CodeTextStyle.copy(color = if (checked) Color.White else ZedColors.Text))
    }
}

@Composable
private fun QuestionBlock(question: Elicitation, handlers: QuestionHandlers) {
    val answering = question.id in handlers.answeringIds
    val error = handlers.errorsById[question.id]
    var values by remember(question.id) { mutableStateOf(initialQuestionValues(question)) }
    AttentionBlock {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(ZedColors.Accent)
                Text("Zed has a question", style = MaterialTheme.typography.titleMedium)
            }
            SelectionContainer { Text(question.message, style = MaterialTheme.typography.bodyLarge) }
            if (question.answerable) {
                question.fields.forEach { field ->
                    QuestionField(field, values[field.name], enabled = !answering) { values = values + (field.name to it) }
                }
            } else {
                Text(
                    question.url?.let { "This needs a browser on desktop:\n$it" } ?: "Answer this on desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZedColors.Muted,
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Danger) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (answering) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = ZedColors.Muted)
                    Spacer(Modifier.width(8.dp))
                }
                SmallButton("Decline", onClick = { handlers.onAnswer(question.id, "decline", emptyMap()) }, tone = ButtonTone.Quiet, enabled = !answering)
                if (question.answerable) {
                    SmallButton(
                        "Send",
                        onClick = { handlers.onAnswer(question.id, "accept", values) },
                        tone = ButtonTone.Primary,
                        enabled = !answering,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

private fun initialQuestionValues(question: Elicitation): Map<String, ElicitationValue> =
    question.fields.mapNotNull { field ->
        val value: ElicitationValue? = when (field.type) {
            ElicitationFieldType.BOOLEAN -> ElicitationValue.Flag(field.defaultBoolean ?: false)
            ElicitationFieldType.MULTI_SELECT -> ElicitationValue.Choices(field.defaultChoices)
            ElicitationFieldType.SINGLE_SELECT ->
                (field.defaultText ?: field.choices.firstOrNull()?.value?.takeIf { field.required })?.let { ElicitationValue.Text(it) }
            else -> field.defaultText?.let { ElicitationValue.Text(it) }
        }
        value?.let { field.name to it }
    }.toMap()

@Composable
private fun QuestionField(field: ElicitationField, value: ElicitationValue?, enabled: Boolean, onChange: (ElicitationValue) -> Unit) {
    val label = field.title + if (field.required) "" else " (optional)"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (field.type) {
            ElicitationFieldType.TEXT, ElicitationFieldType.NUMBER, ElicitationFieldType.INTEGER -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                val text = (value as? ElicitationValue.Text)?.value.orEmpty()
                BasicTextField(
                    value = text,
                    onValueChange = { onChange(ElicitationValue.Text(it)) },
                    enabled = enabled,
                    singleLine = field.type != ElicitationFieldType.TEXT,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = ZedColors.Text),
                    cursorBrush = SolidColor(ZedColors.Accent),
                    keyboardOptions = if (field.type == ElicitationFieldType.TEXT) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(ZedColors.Subtle)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .semantics { contentDescription = field.title },
                )
            }

            ElicitationFieldType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = (value as? ElicitationValue.Flag)?.value ?: false,
                    onCheckedChange = { onChange(ElicitationValue.Flag(it)) },
                    enabled = enabled,
                    colors = quietSwitchColors(),
                    modifier = Modifier.scale(0.8f),
                )
            }

            ElicitationFieldType.SINGLE_SELECT -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                val selected = (value as? ElicitationValue.Text)?.value
                field.choices.forEach { choice ->
                    ChoiceRow(
                        choice = choice,
                        icon = if (selected == choice.value) Symbols.RadioChecked else Symbols.RadioUnchecked,
                        selected = selected == choice.value,
                        enabled = enabled,
                    ) { onChange(ElicitationValue.Text(choice.value)) }
                }
            }

            ElicitationFieldType.MULTI_SELECT -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                val selected = (value as? ElicitationValue.Choices)?.values.orEmpty()
                field.choices.forEach { choice ->
                    val checked = choice.value in selected
                    ChoiceRow(
                        choice = choice,
                        icon = if (checked) Symbols.CheckBox else Symbols.CheckBoxBlank,
                        selected = checked,
                        enabled = enabled,
                    ) {
                        onChange(ElicitationValue.Choices(if (checked) selected - choice.value else selected + choice.value))
                    }
                }
            }
        }
        field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Faint) }
    }
}

@Composable
private fun ChoiceRow(choice: ElicitationChoice, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) ZedColors.Text else ZedColors.Faint, modifier = Modifier.size(20.dp))
        Column {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            choice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted) }
        }
    }
}

@Composable
private fun PlanEntryView(entry: TranscriptEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Plan", style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
        entry.text?.takeIf { it.isNotEmpty() }?.let { AssistantText(it) }
        entry.planEntries.forEach { plan ->
            val status = plan.status.lowercase()
            val done = status.contains("complete")
            val active = status.contains("progress")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    when {
                        done -> Symbols.CheckCircle
                        active -> Symbols.RadioChecked
                        else -> Symbols.RadioUnchecked
                    },
                    contentDescription = humanStatus(plan.status),
                    tint = when {
                        done -> ZedColors.Success
                        active -> ZedColors.Accent
                        else -> ZedColors.Faint
                    },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp),
                )
                Text(
                    plan.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) ZedColors.Muted else ZedColors.Text,
                )
            }
        }
    }
}

@Composable
private fun CompactionView(entry: TranscriptEntry) {
    var expanded by rememberSaveable(entry.index) { mutableStateOf(false) }
    val text = transcriptTextBlocks(entry).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DisclosureRow(
            expanded = expanded,
            onClick = { expanded = !expanded },
            description = "Context compacted",
            leading = { StatusDot(ZedColors.Faint, size = 5.dp) },
        ) {
            Text("Context compacted", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted)
        }
        if (expanded && text.isNotEmpty()) {
            Indented { Text(text, style = MaterialTheme.typography.bodyMedium, color = ZedColors.Muted) }
        }
    }
}

@Composable
private fun LoadOlderRow(olderCount: Int, loading: Boolean, error: String?, onLoad: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SmallButton(
            if (loading) "Loading…" else "Show $olderCount earlier",
            onClick = onLoad,
            tone = ButtonTone.Quiet,
            enabled = !loading,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZedColors.Danger) }
    }
}

@Composable
private fun ApprovalJumpBar(approval: ToolApproval, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(ZedColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(ZedColors.Warning)
        Text(
            "Approval needed · ${approval.title}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("View", style = MaterialTheme.typography.labelLarge, color = ZedColors.Text)
    }
    Hairline()
}

@Composable
private fun Composer(state: MobileState, vm: ZedViewModel, onConfigure: () -> Unit) {
    val turnActive = state.turn.isActive
    val canSend = !state.busy && !turnActive && state.draft.isNotBlank()
    val canStop = !state.busy && turnActive && state.turn.canCancel
    val modelLabel = configurationLabel(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZedColors.Background)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(ZedColors.Surface)
                .border(1.dp, ZedColors.Hairline, MaterialTheme.shapes.extraLarge),
        ) {
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                if (state.draft.isEmpty()) {
                    Text(
                        when {
                            turnActive -> "${agentName(state)} is working…"
                            else -> "Message ${agentName(state)}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZedColors.Faint,
                    )
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = vm::editDraft,
                    enabled = !state.busy && !turnActive,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = ZedColors.Text),
                    cursorBrush = SolidColor(ZedColors.Accent),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) vm.send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .semantics { contentDescription = if (usesExternalAgent(state)) "Message ${agentName(state)}" else "Message the Zed Agent" },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = !state.busy, onClick = onConfigure)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics { contentDescription = if (usesExternalAgent(state)) "Agent settings" else "Model and thinking" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Symbols.Tune, contentDescription = null, tint = ZedColors.Muted, modifier = Modifier.size(15.dp))
                    Text(modelLabel, style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = !state.busy, onClick = vm::showChanges)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics { contentDescription = changesLabel(state) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Symbols.Description, contentDescription = null, tint = ZedColors.Muted, modifier = Modifier.size(15.dp))
                    Text("Changes", style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                val active = canSend || canStop
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                turnActive && canStop -> ZedColors.Text
                                active -> ZedColors.Accent
                                else -> ZedColors.Subtle
                            },
                        )
                        .clickable(enabled = active, onClick = if (turnActive) vm::cancel else vm::send)
                        .semantics { contentDescription = if (turnActive) "Stop Agent" else "Send message" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (turnActive) Symbols.Stop else Symbols.ArrowUpward,
                        contentDescription = null,
                        tint = if (active) Color.White else ZedColors.Faint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietSheet(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZedColors.Surface,
        contentColor = ZedColors.Text,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

internal fun usesExternalAgent(state: MobileState): Boolean =
    state.configuration?.isExternalAgent == true || state.selectedThread?.agent?.isNative == false

internal fun agentName(state: MobileState): String = when {
    usesExternalAgent(state) -> state.configuration?.agent?.name ?: state.selectedThread?.agent?.name ?: "Agent"
    else -> "Zed"
}

internal fun configurationLabel(state: MobileState): String {
    val configuration = state.configuration
    if (!usesExternalAgent(state)) return configuration?.model?.modelName ?: "Model"
    val model = configuration?.options
        ?.firstOrNull { it.category == "model" && it.type == AGENT_OPTION_SELECT }
        ?.let { option -> option.values.firstOrNull { it.value == option.currentValue }?.name }
    return model ?: agentName(state)
}

@Composable
private fun ThreadConfigurationSheet(state: MobileState, vm: ZedViewModel, onDismiss: () -> Unit) {
    if (usesExternalAgent(state)) {
        ExternalAgentConfigurationSheet(state, vm, onDismiss)
        return
    }
    val configuration = state.configuration
    val selectedModel = configuration?.model
    val canChange = !state.turn.isActive
    QuietSheet(onDismiss, "Model") {
        if (state.isLoadingModels && state.models.isEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(1.dp),
                color = ZedColors.Text,
                trackColor = ZedColors.Hairline,
            )
        }
        if (state.turn.isActive) InlineNotice("You can change the model after this turn finishes.")
        when {
            state.isLoadingModels && state.models.isEmpty() -> InlineNotice("Loading models from desktop…")
            state.models.isEmpty() -> InlineNotice("No authenticated desktop models are available.")
            else -> state.models.forEach { model ->
                val selected = selectedModel?.providerId == model.providerId && selectedModel.modelId == model.modelId
                ListRow(
                    title = model.modelName,
                    subtitle = model.providerName,
                    enabled = canChange,
                    onClick = { vm.updateConfiguration(model = model) },
                    showChevron = false,
                    modifier = Modifier.semantics { contentDescription = "Select model ${model.providerName} ${model.modelName}" },
                    trailing = {
                        if (selected) Icon(Symbols.Check, contentDescription = "Selected", tint = ZedColors.Text, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
        if (selectedModel?.supportsThinking == true) {
            val thinkingEnabled = configuration.thinkingEnabled
            SectionLabel("Thinking")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Extended thinking", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = { vm.updateConfiguration(thinkingEnabled = it) },
                    enabled = canChange && (!thinkingEnabled || selectedModel.supportsDisablingThinking),
                    colors = quietSwitchColors(),
                    modifier = Modifier.scale(0.8f),
                )
            }
            if (thinkingEnabled && selectedModel.effortLevels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(ZedColors.Subtle)
                        .padding(3.dp),
                ) {
                    val choices = listOf<Pair<String, String?>>("Default" to null) + selectedModel.effortLevels.map { it.name to it.value }
                    choices.forEach { (name, value) ->
                        val selected = configuration.thinkingEffort == value
                        Text(
                            name,
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(if (selected) ZedColors.Surface else Color.Transparent)
                                .clickable(enabled = canChange) {
                                    if (value == null) vm.updateConfiguration(clearThinkingEffort = true) else vm.updateConfiguration(thinkingEffort = value)
                                }
                                .padding(vertical = 7.dp)
                                .semantics { contentDescription = "Select thinking effort $name" },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (selected) ZedColors.Text else ZedColors.Muted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalAgentConfigurationSheet(state: MobileState, vm: ZedViewModel, onDismiss: () -> Unit) {
    val configuration = state.configuration
    val canChange = !state.turn.isActive
    val modes = configuration?.modes?.takeIf { it.available.isNotEmpty() }
    QuietSheet(onDismiss, agentName(state)) {
        if (state.turn.isActive) InlineNotice("You can change these settings after this turn finishes.")
        when {
            configuration == null -> InlineNotice("Loading settings from desktop…")
            modes == null && configuration.options.isEmpty() ->
                InlineNotice("${agentName(state)} has no settings that can be changed from the phone.")
        }
        modes?.let {
            SectionLabel("Mode")
            it.available.forEach { mode ->
                AgentChoiceRow(
                    title = mode.name,
                    subtitle = mode.description,
                    selected = mode.id == it.current,
                    enabled = canChange,
                    onClick = { vm.setAgentMode(mode.id) },
                )
            }
        }
        configuration?.options.orEmpty().forEach { option ->
            when (option.type) {
                AGENT_OPTION_SELECT -> {
                    SectionLabel(option.name)
                    option.values.forEach { value ->
                        AgentChoiceRow(
                            title = value.name,
                            subtitle = value.description ?: value.group,
                            selected = value.value == option.currentValue,
                            enabled = canChange,
                            onClick = { vm.setAgentOption(option.id, AgentOptionValue.Choice(value.value)) },
                        )
                    }
                }
                AGENT_OPTION_BOOLEAN -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = option.currentBoolean == true,
                        onCheckedChange = { vm.setAgentOption(option.id, AgentOptionValue.Toggle(it)) },
                        enabled = canChange,
                        colors = quietSwitchColors(),
                        modifier = Modifier
                            .scale(0.8f)
                            .semantics { contentDescription = option.name },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentChoiceRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        // Re-selecting the current value would only round-trip to the agent.
        enabled = enabled && !selected,
        onClick = onClick,
        showChevron = false,
        modifier = Modifier.semantics { contentDescription = "Select $title" },
        trailing = {
            if (selected) Icon(Symbols.Check, contentDescription = "Selected", tint = ZedColors.Text, modifier = Modifier.size(18.dp))
        },
    )
}

internal fun changesLabel(state: MobileState): String =
    state.files.takeIf { it.isNotEmpty() }?.let { "Changes · ${it.size}" } ?: "Changes"

internal fun turnPhaseLabel(phase: TurnPhase): String = when (phase) {
    TurnPhase.SENDING -> "Sending"
    TurnPhase.RUNNING -> "Working"
    TurnPhase.AWAITING_APPROVAL -> "Waiting for you"
    TurnPhase.CANCELLING -> "Stopping"
    TurnPhase.COMPLETED -> "Done"
    TurnPhase.FAILED -> "Failed"
    TurnPhase.IDLE -> "Ready"
}

internal data class ThoughtLocation(val entryPosition: Int, val chunkPosition: Int?)

private fun latestThoughtLocation(transcript: List<TranscriptEntry>): ThoughtLocation? {
    val currentTurnStart = transcript.indexOfLast { it.kind.lowercase().contains("user") } + 1
    for (entryPosition in transcript.indices.reversed()) {
        if (entryPosition < currentTurnStart) break
        val entry = transcript[entryPosition]
        for (chunkPosition in entry.chunks.indices.reversed()) {
            if (isThoughtKind(entry.chunks[chunkPosition].kind.lowercase())) return ThoughtLocation(entryPosition, chunkPosition)
        }
        if (isThoughtKind(entry.kind.lowercase())) return ThoughtLocation(entryPosition, null)
    }
    return null
}

/** True once the model has produced anything after the most recent user message. */
private fun hasOutputSinceLastPrompt(transcript: List<TranscriptEntry>): Boolean {
    val lastPrompt = transcript.indexOfLast { it.kind.lowercase().contains("user") }
    return lastPrompt >= 0 && lastPrompt < transcript.lastIndex
}

private fun thoughtPreview(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(180)

private fun isThoughtKind(kind: String): Boolean = kind.contains("thought") || kind.contains("think") || kind.contains("reason")

private fun transcriptTextBlocks(entry: TranscriptEntry): List<String> = buildList {
    entry.text?.takeIf { it.isNotEmpty() }?.let(::add)
    entry.chunks.forEach { if (it.text.isNotEmpty()) add(it.text) }
}

/**
 * Scrolls to the very bottom of the last item, so a long final reply is not left cut off at its
 * top edge the way scrolling to the item alone would.
 */
private fun LazyListState.isNearEnd(): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return layoutInfo.totalItemsCount - 1 - lastVisible <= 1
}

/**
 * Scrolls smoothly down from where the reader is, a few screens per step, instead of
 * `animateScrollToItem`, which jumps near the target first and then animates the rest.
 */
internal suspend fun LazyListState.glideToEnd() {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().coerceAtLeast(1f)
    repeat(MAX_GLIDE_STEPS) {
        if (!canScrollForward) return
        animateScrollBy(viewport * 2, tween(durationMillis = 220, easing = LinearEasing))
    }
    // Very long threads: finish without animating through the remaining screens.
    scrollToEnd(animated = false)
}

private const val MAX_GLIDE_STEPS = 12

internal suspend fun LazyListState.scrollToEnd(animated: Boolean = true) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (animated) animateScrollToItem(last) else scrollToItem(last)
    val item = layoutInfo.visibleItemsInfo.lastOrNull { it.index == last } ?: return
    val overflow = item.offset + item.size - (layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding)
    if (overflow > 0) {
        if (animated) animateScrollBy(overflow.toFloat()) else scrollBy(overflow.toFloat())
    }
}
