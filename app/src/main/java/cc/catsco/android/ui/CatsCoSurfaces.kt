package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun Modifier.catsCoFloatingShadow(shape: Shape): Modifier = shadow(
    elevation = if (MaterialTheme.colorScheme.background.luminance() < .5f) 0.dp else 2.dp,
    shape = shape,
    ambientColor = Color.Black.copy(alpha = .12f),
    spotColor = Color.Black.copy(alpha = .12f),
)

@Composable
internal fun CatsCoAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = CatsCoMobileSpec.dialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

@Composable
internal fun CatsCoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CatsCoMobileSpec.fieldShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
    ),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = CatsCoMobileSpec.minimumTouchTarget),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
internal fun CatsCoSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    CatsCoButton(onClick, modifier, enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF414141) else Color(0xFFEDEDED),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ), contentPadding = contentPadding, content = content)
}

@Composable
internal fun ModuleActionRow(label: String, icon: Int? = null, enabled: Boolean = true,
    trailing: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.settingsRowHeight)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        icon?.let { Icon(painterResource(it), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = color) }
        Text(label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp, color = color.copy(alpha = if (enabled) 1f else .38f))
        if (trailing) Icon(painterResource(R.drawable.e_expand), null,
            Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = -90f }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CatsCoSegmentedControl(options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier, testTagPrefix: String = "segment", enabled: Boolean = true) {
    if (options.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer).padding(4.dp).selectableGroup()) {
        val width = maxWidth / options.size
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val offset by animateDpAsState(width * index, tween(200, easing = CubicBezierEasing(.2f, .7f, .2f, 1f)), label = "segment-position")
        val color = MaterialTheme.colorScheme.surface
        Box(Modifier.matchParentSize().drawBehind {
            drawRoundRect(color, Offset(offset.toPx(), 0f), Size(width.toPx(), size.height), CornerRadius(10.dp.toPx()))
        })
        Row {
            options.forEachIndexed { optionIndex, (id, label) ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(id == selected, enabled = enabled, role = Role.Tab) { onSelect(id) }
                    .testTag("$testTagPrefix-$optionIndex"), contentAlignment = Alignment.Center) {
                    Text(label, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 15.sp,
                        lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
