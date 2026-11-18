package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cc.catsco.android.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

object CatsCoMobileSpec {
    val space2 = 4.dp
    val space4 = 8.dp
    val space6 = 12.dp
    val space8 = 16.dp
    val space12 = 24.dp

    val radiusSmall = RoundedCornerShape(6.dp)
    val radiusControl = RoundedCornerShape(8.dp)
    val radiusCard = RoundedCornerShape(8.dp)
    val radiusLarge = RoundedCornerShape(12.dp)
    val radiusComposer = RoundedCornerShape(28.dp)
    val menuShape = RoundedCornerShape(12.dp)
    val dialogShape = RoundedCornerShape(20.dp)
    val fieldShape = RoundedCornerShape(12.dp)
    val searchShape = RoundedCornerShape(24.dp)
    val contentCardShape = RoundedCornerShape(12.dp)
    val codeShape = RoundedCornerShape(8.dp)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val menuInset = 6.dp

    val minimumTouchTarget = 48.dp
    val sidebarShortcutHeight = 56.dp
    val accountFooterHeight = 76.dp
    val accountAvatarSize = 40.dp
    val historyRowHeight = 48.dp
    val menuWidth = 176.dp
    val menuItemHeight: androidx.compose.ui.unit.Dp
        @Composable get() = with(LocalDensity.current) {
            maxOf(minimumTouchTarget, menuLabelStyle.lineHeight.toDp() + 24.dp)
        }
    val actionIconSize = 23.dp
    val menuIconSize = actionIconSize
    val menuHorizontalPadding = 20.dp
    val menuContentPadding = PaddingValues(horizontal = menuHorizontalPadding - menuInset)
    val headerButtonTarget = 48.dp
    val headerButtonVisual = 40.dp
    val headerHeight = 60.dp
    val sidebarIconSize = actionIconSize
    val sidebarContentInset = 13.dp
    val sidebarLeadingSize = actionIconSize
    val sidebarLabelGap = 10.dp
    // Align history text with the visible left edge of the 20dp apps icon.
    val sidebarHistoryInset = sidebarContentInset + 3.dp

    val settingsGroupShape = RoundedCornerShape(24.dp)
    val settingsRowHeight = 56.dp
    val settingsIconSize = actionIconSize
    val settingsLabelGap = 14.dp
    val settingsText = 16.sp
    val settingsSectionText = 16.sp

    val historyText = 15.sp
    val menuText = 17.sp
    val menuLabelStyle = TextStyle(
        fontFamily = CatsCoFontFamily,
        fontSize = menuText,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val bodyText = 16.sp
    val captionText = 11.sp
}

@Composable
fun CatsCoWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.catsco_wordmark),
        contentDescription = "CatsCo",
        modifier = modifier,
        colorFilter = ColorFilter.tint(Mint),
    )
}

@Composable
fun CatsCoSidebarShortcut(icon: Int, title: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = CatsCoMobileSpec.space2),
        shape = CatsCoMobileSpec.radiusControl,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = CatsCoMobileSpec.sidebarContentInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CatsCoMobileSpec.sidebarLabelGap),
        ) {
            Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.sidebarLeadingSize), tint = MaterialTheme.colorScheme.onSurface)
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun CatsCoHeaderAction(icon: Int, label: String, decorated: Boolean = true, iconRotation: Float = 0f,
    enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(CatsCoMobileSpec.headerButtonTarget)) {
        val tint = LocalContentColor.current
        if (decorated) Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = if (MaterialTheme.colorScheme.background.luminance() < .5f) 0.dp else 1.dp,
        ) {
            Box(Modifier.size(CatsCoMobileSpec.headerButtonVisual), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), label, Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = iconRotation }, tint = tint)
            }
        } else {
            Box(Modifier.size(CatsCoMobileSpec.headerButtonTarget), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), label, Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = iconRotation }, tint = tint)
            }
        }
    }
}

@Composable
internal fun CatsCoHeaderAddMenu(label: String, enabled: Boolean, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CatsCoHeaderAction(R.drawable.e_add, label, enabled = enabled) { expanded = true }
        CatsCoMenuPopup(expanded, { expanded = false }, width = 208.dp) {
            options.forEach { (title, select) ->
                DropdownMenuItem(text = { Text(title, style = CatsCoMobileSpec.menuLabelStyle) }, onClick = {
                    expanded = false
                    select()
                })
            }
        }
    }
}

@Composable
internal fun CatsCoHeaderGlass(backdrop: HazeState, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.background
    val style = HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = .68f)),
        blurRadius = 16.dp,
        noiseFactor = .015f,
        fallbackTint = HazeTint(surface.copy(alpha = .94f)),
    )
    Box(modifier.layout { measurable, constraints ->
        // Keep the header's touch/layout bounds while fading below its controls.
        val effectHeight = constraints.maxHeight + 24.dp.roundToPx()
        val layer = measurable.measure(constraints.copy(minHeight = effectHeight, maxHeight = effectHeight))
        layout(constraints.maxWidth, constraints.maxHeight) { layer.place(0, 0) }
    }.hazeEffect(state = backdrop, style = style) {
        mask = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Black, .72f to Color.Black, 1f to Color.Transparent,
        )
    })
}

@Composable
fun CatsCoSidebarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索历史任务",
    shape: androidx.compose.ui.graphics.Shape = CatsCoMobileSpec.searchShape,
    glass: Boolean = true,
) {
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val glassStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tint = HazeTint(glassTint.copy(alpha = .60f)),
        blurRadius = 18.dp,
        noiseFactor = .02f,
        fallbackTint = HazeTint(glassTint.copy(alpha = .92f)),
    )
    Surface(
        modifier = if (glass) modifier.catsCoFloatingShadow(shape) else modifier,
        shape = shape,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (glass) Modifier.hazeEffect(state = backdrop, style = glassStyle) else Modifier)
                .padding(horizontal = CatsCoMobileSpec.sidebarContentInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CatsCoMobileSpec.sidebarLabelGap),
        ) {
            Icon(painterResource(R.drawable.e_search), "搜索", Modifier.size(CatsCoMobileSpec.sidebarLeadingSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val interactionSource = remember { MutableInteractionSource() }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).background(Color.Transparent),
                singleLine = true,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    TextFieldDefaults.DecorationBox(
                        value = query,
                        innerTextField = inner,
                        enabled = true,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interactionSource,
                        placeholder = { Text(placeholder, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        contentPadding = PaddingValues(0.dp),
                        container = {},
                    )
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.e_close), "清除搜索", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun CatsCoMorphingMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onComputer: () -> Unit,
    onNewTask: () -> Unit,
    onCloudFiles: () -> Unit,
    onRefresh: () -> Unit,
) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f,
        spring(dampingRatio = 1.12f, stiffness = if (expanded) 420f else 620f), label = "menuMorph")
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val expandedWidth = minOf(176.dp * fontScale, configuration.screenWidthDp.dp - 16.dp)
    val width = 96.dp + (expandedWidth - 96.dp) * progress
    val availableHeight = (configuration.screenHeightDp.dp - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 16.dp).coerceAtLeast(96.dp)
    val expandedHeight = minOf(CatsCoMobileSpec.menuInset * 2 + CatsCoMobileSpec.menuItemHeight * 4, availableHeight)
    val height = CatsCoMobileSpec.headerButtonTarget + (expandedHeight - CatsCoMobileSpec.headerButtonTarget) * progress
    val surfaceHeight = 40.dp + (expandedHeight - 40.dp) * progress
    val corner = 24.dp - 12.dp * progress
    val contentAlpha by animateFloatAsState(if (expanded) 1f else 0f,
        tween(durationMillis = if (expanded) 120 else 80, delayMillis = if (expanded) 50 else 0), label = "menuContent")
    val scrimAlpha by animateFloatAsState(if (expanded) .14f else 0f,
        tween(if (expanded) 120 else 80), label = "menuScrim")

    Box(Modifier.fillMaxSize()) {
        if (scrimAlpha > 0f || expanded) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(enabled = expanded) { onExpandedChange(false) },
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = (CatsCoMobileSpec.headerHeight - CatsCoMobileSpec.headerButtonTarget) / 2, end = 8.dp)
                .width(width).height(height)
                .semantics { contentDescription = if (expanded) "更多操作菜单" else "更多操作" },
        ) {
            Surface(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(surfaceHeight)
                    .catsCoFloatingShadow(RoundedCornerShape(corner)).clip(RoundedCornerShape(corner)),
                shape = RoundedCornerShape(corner),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp,
            ) {
                if (expanded || progress > .001f) {
                    Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }
                        .padding(CatsCoMobileSpec.menuInset).verticalScroll(rememberScrollState())) {
                        MorphMenuItem(R.drawable.e_computer, "我的电脑", expanded) { onExpandedChange(false); onComputer() }
                        MorphMenuItem(R.drawable.e_new_task, "新建任务", expanded) { onExpandedChange(false); onNewTask() }
                        MorphMenuItem(R.drawable.e_cloud, "云端文件", expanded) { onExpandedChange(false); onCloudFiles() }
                        MorphMenuItem(R.drawable.e_refresh, "刷新", expanded) { onExpandedChange(false); onRefresh() }
                    }
                } else {
                    Spacer(Modifier.fillMaxSize())
                }
            }
            if (progress < .99f) {
                Row(Modifier.align(Alignment.TopEnd).width(96.dp).height(48.dp)
                    .graphicsLayer { alpha = (1f - progress * 4f).coerceIn(0f, 1f) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(enabled = !expanded) { onExpandedChange(false); onNewTask() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.e_new_task), "新建任务", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(Modifier.width(.5.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)))
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(enabled = !expanded) { onExpandedChange(true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.e_more), "更多操作", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphMenuItem(icon: Int, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(CatsCoMobileSpec.menuItemHeight)
            .clip(CatsCoMobileSpec.radiusSmall).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = CatsCoMobileSpec.menuHorizontalPadding - CatsCoMobileSpec.menuInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.menuIconSize), tint = MaterialTheme.colorScheme.onSurface)
        Text(title, style = CatsCoMobileSpec.menuLabelStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun CatsCoComposerFrame(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.shadow(
            if (MaterialTheme.colorScheme.background.luminance() < .5f) 0.dp else 2.dp,
            CatsCoMobileSpec.radiusComposer,
            ambientColor = Color.Black.copy(alpha = .12f),
            spotColor = Color.Black.copy(alpha = .12f),
        ),
        shape = CatsCoMobileSpec.radiusComposer,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

@Composable
fun CatsCoAccountFooter(session: cc.catsco.core.Session, imageLoader: coil.ImageLoader, onClick: () -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = CatsCoMobileSpec.accountFooterHeight)
                .clickable(onClick = onClick)
                .padding(horizontal = CatsCoMobileSpec.space8, vertical = CatsCoMobileSpec.space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CatsCoMobileSpec.space6),
        ) {
            CatsCoAccountAvatar(session, imageLoader)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("账户与设置", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(
                painterResource(R.drawable.e_settings),
                contentDescription = "打开设置",
                modifier = Modifier.size(CatsCoMobileSpec.actionIconSize),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun CatsCoConversationActionMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectMore: () -> Unit,
    onDelete: () -> Unit,
) {
    val labelStyle = CatsCoMobileSpec.menuLabelStyle
    CatsCoMenuPopup(expanded, onDismiss) {
        DropdownMenuItem(
            modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall),
            contentPadding = CatsCoMobileSpec.menuContentPadding,
            text = { Text("重命名", style = labelStyle) },
            leadingIcon = { Icon(painterResource(R.drawable.e_edit), null, Modifier.size(CatsCoMobileSpec.menuIconSize), tint = MaterialTheme.colorScheme.onSurface) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall),
            contentPadding = CatsCoMobileSpec.menuContentPadding,
            text = { Text(if (pinned) "取消置顶" else "置顶", style = labelStyle) },
            leadingIcon = { Icon(painterResource(R.drawable.e_pin), null, Modifier.size(CatsCoMobileSpec.menuIconSize), tint = MaterialTheme.colorScheme.onSurface) },
            onClick = { onDismiss(); onTogglePin() },
        )
        DropdownMenuItem(
            modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall),
            contentPadding = CatsCoMobileSpec.menuContentPadding,
            text = { Text("多选", style = labelStyle) },
            leadingIcon = { Icon(painterResource(R.drawable.e_select), null, Modifier.size(CatsCoMobileSpec.menuIconSize), tint = MaterialTheme.colorScheme.onSurface) },
            onClick = { onDismiss(); onSelectMore() },
        )
        DropdownMenuItem(
            modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall),
            contentPadding = CatsCoMobileSpec.menuContentPadding,
            text = { Text("删除", style = labelStyle, color = MaterialTheme.colorScheme.error) },
            // The delete vector is taller, so compensate for its internal bounds.
            leadingIcon = { Icon(painterResource(R.drawable.e_delete), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
fun CatsCoMessageActionMenu(
    expanded: Boolean,
    anchorPoint: IntOffset?,
    regenerateEnabled: Boolean,
    onDismiss: () -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val actions = listOf(
        Triple("引用", R.drawable.message_quote, onQuote),
        Triple("复制全文", R.drawable.message_copy, onCopy),
        Triple("重新生成", R.drawable.e_refresh, onRegenerate),
    )
    CatsCoMenuPopup(expanded, onDismiss, anchorPoint, "message-action-card") {
        actions.forEachIndexed { index, (title, icon, action) ->
            DropdownMenuItem(
                modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall),
                contentPadding = CatsCoMobileSpec.menuContentPadding,
                enabled = index != 2 || regenerateEnabled,
                text = { Text(title, style = CatsCoMobileSpec.menuLabelStyle) },
                leadingIcon = { Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.menuIconSize),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (index != 2 || regenerateEnabled) 1f else .38f)) },
                onClick = { onDismiss(); action() },
            )
        }
    }
}

@Composable
internal fun CatsCoMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorPoint: IntOffset? = null,
    testTag: String = "conversation-action-card",
    width: androidx.compose.ui.unit.Dp = CatsCoMobileSpec.menuWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "conversationMenuVisibility",
    )
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val configuration = LocalConfiguration.current
    val adaptedWidth = minOf(width * density.fontScale.coerceAtLeast(1f), configuration.screenWidthDp.dp - 16.dp)
    val availableHeight = (configuration.screenHeightDp.dp - 16.dp).coerceAtLeast(56.dp)
    val positionProvider = remember(margin, anchorPoint) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                if (anchorPoint != null) {
                    val left = (anchorPoint.x - popupContentSize.width / 2).coerceIn(margin, maxX)
                    val below = anchorPoint.y + margin
                    val top = if (below <= maxY) below else anchorPoint.y - popupContentSize.height - margin
                    return IntOffset(left, top.coerceIn(margin, maxY))
                }
                val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
                val below = anchorBounds.bottom
                val above = anchorBounds.top - popupContentSize.height
                val top = when {
                    below <= maxY -> below
                    above >= margin -> above
                    else -> anchorBounds.top.coerceIn(margin, maxY)
                }
                return IntOffset(start.coerceIn(margin, maxX), top)
            }
        }
    }
    if (expanded || visibility > 0f) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            CompositionLocalProvider(LocalDensity provides density) {
                Surface(
                    modifier = Modifier.testTag(testTag).graphicsLayer {
                        alpha = visibility
                        scaleX = .96f + .04f * visibility
                        scaleY = .96f + .04f * visibility
                        transformOrigin = TransformOrigin(0f, 0f)
                    }.catsCoFloatingShadow(CatsCoMobileSpec.menuShape),
                    shape = CatsCoMobileSpec.menuShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.width(adaptedWidth).heightIn(max = availableHeight)
                        .padding(CatsCoMobileSpec.menuInset).verticalScroll(rememberScrollState()), content = content)
                }
            }
        }
    }
}
