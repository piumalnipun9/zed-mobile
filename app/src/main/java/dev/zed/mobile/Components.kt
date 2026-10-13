package dev.zed.mobile

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zeddesktop.mobile.R

internal enum class ButtonTone { Primary, Secondary, Quiet, Danger }

/** The one compact button used across the app: 36dp tall, 8dp corners, no shadows. */
@Composable
internal fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Secondary,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    val shape = MaterialTheme.shapes.small
    val sized = modifier.heightIn(min = 36.dp)
    when (tone) {
        ButtonTone.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = sized,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = ZedColors.Text, contentColor = Color.White),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }

        ButtonTone.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = sized,
            shape = shape,
            contentPadding = padding,
            border = androidx.compose.foundation.BorderStroke(1.dp, ZedColors.Hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZedColors.Text),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }

        ButtonTone.Quiet, ButtonTone.Danger -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = sized,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (tone == ButtonTone.Danger) ZedColors.Danger else ZedColors.Muted,
            ),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    HorizontalDivider(modifier = modifier.padding(start = inset), thickness = 0.5.dp, color = ZedColors.Hairline)
}

/** A flat list row: optional leading mark, a title with secondary line, and trailing content. */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMonospace: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) ZedColors.Text else ZedColors.Faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = if (subtitleMonospace) CodeTextStyle.copy(color = ZedColors.Muted) else MaterialTheme.typography.bodySmall,
                    color = ZedColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
        if (showChevron) {
            Icon(
                Symbols.ChevronRight,
                contentDescription = null,
                tint = ZedColors.Faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ZedColors.Muted,
    )
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** Small inline status text; errors get an icon rather than a heavy colored box. */
@Composable
internal fun InlineNotice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (error) {
            Icon(
                Symbols.Error,
                contentDescription = null,
                tint = ZedColors.Danger,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) ZedColors.Danger else ZedColors.Muted,
        )
    }
}

@Composable
internal fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ZedColors.Text)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = ZedColors.Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Monospace text on a subtle fill. Long lines scroll sideways instead of wrapping. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted) }
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(ZedColors.Subtle)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = CodeTextStyle,
                softWrap = false,
            )
        }
    }
}

/** A bordered white block for things that need the user: approvals and questions. */
@Composable
internal fun AttentionBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(ZedColors.Surface)
            .border(1.dp, ZedColors.Hairline, MaterialTheme.shapes.medium),
    ) { content() }
}

@Composable
internal fun ThinkingDots(color: Color = ZedColors.Muted) {
    val transition = rememberInfiniteTransition(label = "agent-thinking")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent-thinking-pulse",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha = when (index) {
                0 -> pulse
                1 -> 0.5f + pulse * 0.3f
                else -> 0.3f + pulse * 0.4f
            }
            StatusDot(color = color.copy(alpha = alpha), size = 5.dp)
        }
    }
}

@Composable
internal fun ZedBrandMark(size: Dp, tint: Color = ZedColors.Text, contentDescription: String? = null) {
    Icon(
        painter = painterResource(id = R.drawable.zed_logo),
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

internal fun humanStatus(status: String): String =
    status.trim().replace('_', ' ').replace('-', ' ').lowercase().replaceFirstChar { it.uppercase() }

internal fun statusColor(status: String): Color {
    val value = status.lowercase()
    return when {
        value.contains("error") || value.contains("fail") -> ZedColors.Danger
        value.contains("approval") || value.contains("waiting") || value.contains("authentication") -> ZedColors.Warning
        value.contains("run") || value.contains("progress") || value.contains("generat") ||
            value.contains("active") || value.contains("sending") -> ZedColors.Accent
        value.contains("complete") || value.contains("done") -> ZedColors.Success
        else -> ZedColors.Faint
    }
}

/**
 * Material Symbols (Outlined, weight 300) used across the app, so every icon shares one stroke
 * weight. Sources live in `res/drawable/ms_*.xml`.
 */
internal object Symbols {
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_add)
    val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_arrow_back)
    val ArrowUpward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_arrow_upward)
    val Asterisk: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_asterisk)
    val AttachFile: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_attach_file)
    val Block: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_block)
    val ChatBubble: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_chat_bubble)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_check)
    val CheckBox: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_check_box)
    val CheckBoxBlank: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_check_box_outline_blank)
    val CheckCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_check_circle)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_chevron_right)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_close)
    val Description: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_description)
    val Dns: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_dns)
    val Error: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_error)
    val ExpandLess: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_expand_less)
    val ExpandMore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_expand_more)
    val Folder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_folder)
    val List: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_list)
    val Logout: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_logout)
    val Menu: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_menu)
    val MoreHoriz: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_more_horiz)
    val NewThread: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_edit_square)
    val RadioChecked: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_radio_button_checked)
    val RadioUnchecked: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_radio_button_unchecked)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_search)
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_settings)
    val Stop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_stop)
    val Tune: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_tune)
}
