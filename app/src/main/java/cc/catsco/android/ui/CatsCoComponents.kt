package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cc.catsco.android.R
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@Composable
internal fun CatsCoDisclosureChevron(
    expanded: Boolean,
    size: Dp = CatsCoMobileSpec.actionIconSize,
    tint: Color = LocalContentColor.current,
) {
    val angle = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(disclosureDuration, easing = FastOutSlowInEasing),
        label = "disclosureChevron",
    )
    Icon(
        painter = painterResource(R.drawable.e_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(size).graphicsLayer { rotationZ = angle.value },
        tint = tint,
    )
}

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
    val composerVerticalInset = 12.dp
    val composerRowVerticalInset = 9.dp
    val composerFrameMinHeight = minimumTouchTarget + composerRowVerticalInset * 2
    val sidebarShortcutHeight = 56.dp
    val accountFooterHeight = 76.dp
    val accountAvatarSize = 40.dp
    val historyRowHeight = 48.dp
    val menuWidth = 192.dp
    val menuItemHeight: androidx.compose.ui.unit.Dp
        @Composable get() = with(LocalDensity.current) {
            maxOf(minimumTouchTarget, menuLabelStyle.lineHeight.toDp() + 24.dp)
        }
    val actionIconSize = 23.dp
    val menuIconSize = 20.dp
    val menuHorizontalPadding = 16.dp
    val menuLabelGap = 12.dp
    val headerButtonTarget = 48.dp
    val headerButtonVisual = 40.dp
    val headerHeight = 60.dp
    val sidebarIconSize = actionIconSize
    val sidebarGutter = 13.dp
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
    val menuText = 16.sp
    val menuLabelStyle = TextStyle(
        fontFamily = CatsCoFontFamily,
        fontSize = menuText,
        lineHeight = 22.sp,
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
        modifier = Modifier.fillMaxWidth(),
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
    CatsCoBoundedIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(CatsCoMobileSpec.headerButtonTarget)) {
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

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun CatsCoHeaderGlass(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    overWallpaper: Boolean = false,
    opacity: () -> Float = { 1f },
) {
    val surface = MaterialTheme.colorScheme.background
    val style = HazeStyle(
        backgroundColor = if (overWallpaper) Color.Transparent else surface,
        tint = HazeTint(surface.copy(alpha = if (overWallpaper) .10f else .68f)),
        blurRadius = 16.dp,
        noiseFactor = if (overWallpaper) 0f else .015f,
        fallbackTint = HazeTint(surface.copy(alpha = if (overWallpaper) .10f else .94f)),
    )
    Box(modifier.layout { measurable, constraints ->
        // Keep the header's touch/layout bounds while fading below its controls.
        val effectHeight = constraints.maxHeight + 24.dp.roundToPx()
        val layer = measurable.measure(constraints.copy(minHeight = effectHeight, maxHeight = effectHeight))
        layout(constraints.maxWidth, constraints.maxHeight) { layer.place(0, 0) }
    }.hazeEffect(state = backdrop, style = style) {
        alpha = opacity()
        inputScale = HazeInputScale.Fixed(.67f)
        mask = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Black, .72f to Color.Black, 1f to Color.Transparent,
        )
    })
}

internal val LocalSidebarHistoryBackdrop = staticCompositionLocalOf { true }

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun CatsCoSidebarFieldSurface(
    backdrop: HazeState,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    glass: Boolean = true,
    floatingShadow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val liveBackdrop = glass && LocalSidebarHistoryBackdrop.current
    val glassStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tint = HazeTint(glassTint.copy(alpha = .60f)),
        blurRadius = 18.dp,
        noiseFactor = .02f,
        fallbackTint = HazeTint(glassTint.copy(alpha = .92f)),
    )
    Surface(
        modifier = if (glass && floatingShadow) modifier.catsCoFloatingShadow(shape) else modifier,
        shape = shape,
        color = when {
            liveBackdrop -> Color.Transparent
            glass -> androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerLow, glassTint, .60f)
            else -> MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Box(if (liveBackdrop) Modifier.hazeEffect(state = backdrop, style = glassStyle) {
            inputScale = HazeInputScale.Fixed(.5f)
        } else Modifier) {
            content()
        }
    }
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
    CatsCoSidebarFieldSurface(backdrop, modifier, shape, glass = glass, floatingShadow = true) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
fun CatsCoHeaderActionMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onComputer: () -> Unit,
    onNewTask: () -> Unit,
    onCloudFiles: () -> Unit,
    onRefresh: () -> Unit,
    transition: Transition<Boolean> = updateTransition(expanded, label = "headerActionMenu"),
    notificationUnreadCount: Int? = null,
    showNotificationBadge: Boolean = false,
    onNotifications: (() -> Unit)? = null,
) {
    val progress = transition.animateFloat(
        transitionSpec = { tween(if (targetState) 210 else 150, easing = FastOutSlowInEasing) },
        label = "menuExpansion",
    ) { if (it) 1f else 0f }
    val showMenu = transition.currentState || transition.targetState || transition.isRunning
    val showCapsuleActions = !transition.currentState || !transition.targetState || transition.isRunning
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val capsuleWidth = 96.dp
    val capsuleHeight = CatsCoMobileSpec.headerButtonVisual
    val capsuleTop = (CatsCoMobileSpec.headerButtonTarget - capsuleHeight) / 2
    val headerTop = (CatsCoMobileSpec.headerHeight - CatsCoMobileSpec.headerButtonTarget) / 2
    val expandedWidth = CatsCoMobileSpec.menuWidth * fontScale
    val menuItemCount = if (onNotifications == null) 4 else 5
    val expandedHeight = CatsCoMobileSpec.menuInset * 2 + CatsCoMobileSpec.menuItemHeight * menuItemCount
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val lightTheme = MaterialTheme.colorScheme.background.luminance() >= .5f
    val unreadCount = notificationUnreadCount ?: 0
    val unreadLabel = if (unreadCount > 99) "99+" else unreadCount.toString()
    val showBell = showNotificationBadge && onNotifications != null

    Box(Modifier.fillMaxSize()) {
        if (showMenu) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = .10f * progress.value
                }.background(Color.Black)
                    .clickable(interactionSource = null, indication = null, enabled = expanded) { onExpandedChange(false) },
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().navigationBarsPadding()
                .padding(start = 8.dp, top = headerTop, end = 8.dp, bottom = 8.dp),
        ) {
            Box(
                // The rows keep their measured bounds. Only the drawn outline contracts into the capsule.
                Modifier.align(Alignment.TopEnd).width(expandedWidth).height(expandedHeight).graphicsLayer()
                    .drawBehind {
                        val shape = HeaderMenuShape(progress.value, capsuleWidth, capsuleHeight, capsuleTop)
                        val outline = shape.createOutline(size, layoutDirection, this)
                        if (lightTheme) translate(top = 1.dp.toPx()) {
                            drawOutline(outline, Color.Black.copy(alpha = .04f))
                        }
                        drawOutline(outline, surfaceColor)
                        drawOutline(outline, borderColor, style = Stroke(.5.dp.toPx()))
                    }.graphicsLayer {
                        shape = HeaderMenuShape(progress.value, capsuleWidth, capsuleHeight, capsuleTop)
                        clip = true
                    },
            ) {
                if (showMenu) Column(
                    Modifier.fillMaxSize().graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = ((progress.value - .30f) / .65f).coerceIn(0f, 1f)
                    }.semantics {
                        contentDescription = "更多操作菜单"
                        if (!expanded) hideFromAccessibility()
                    }.padding(CatsCoMobileSpec.menuInset).verticalScroll(rememberScrollState(), enabled = expanded),
                ) {
                    if (onNotifications != null) {
                        CatsCoMenuActionItem(
                            "通知", R.drawable.e_notifications, enabled = expanded, dimWhenDisabled = false,
                            fontWeight = if (unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                            trailingText = if (unreadCount > 0) "$unreadLabel 条未读" else null,
                        ) {
                            onExpandedChange(false)
                            onNotifications()
                        }
                    }
                    listOf(
                        Triple("我的电脑", R.drawable.e_computer, onComputer),
                        Triple("新建任务", R.drawable.e_new_task, onNewTask),
                        Triple("文件与应用", R.drawable.e_cloud, onCloudFiles),
                        Triple("刷新", R.drawable.e_refresh, onRefresh),
                    ).forEach { (title, icon, action) ->
                        CatsCoMenuActionItem(title, icon, enabled = expanded, dimWhenDisabled = false) {
                            onExpandedChange(false)
                            action()
                        }
                    }
                }
            }
            if (showCapsuleActions) Row(
                Modifier.align(Alignment.TopEnd).width(capsuleWidth).height(CatsCoMobileSpec.headerButtonTarget)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = (1f - progress.value / .30f).coerceIn(0f, 1f)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().catsCoActionClickable(enabled = !expanded,
                        shape = RoundedCornerShape(topStart = capsuleHeight / 2, bottomStart = capsuleHeight / 2),
                        verticalInset = capsuleTop, onClick = onNewTask),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.e_new_task), "新建任务", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.width(.5.dp).height(22.dp).background(borderColor.copy(alpha = .7f)))
                Box(
                    Modifier.weight(1f).fillMaxHeight().catsCoActionClickable(enabled = !expanded,
                        shape = RoundedCornerShape(topEnd = capsuleHeight / 2, bottomEnd = capsuleHeight / 2),
                        verticalInset = capsuleTop) { onExpandedChange(true) }
                        .semantics {
                            contentDescription = "更多操作"
                            if (showBell && unreadCount > 0) stateDescription = "$unreadCount 条未读通知"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = showBell,
                        animationSpec = tween(130),
                        label = "headerMoreIcon",
                    ) { bell ->
                        Box(Modifier.fillMaxWidth().height(capsuleHeight), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(if (bell) R.drawable.e_notifications else R.drawable.e_more),
                                null,
                                Modifier.offset(x = if (bell && unreadCount > 0) (-2).dp else 0.dp)
                                    .size(CatsCoMobileSpec.actionIconSize),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            if (bell && unreadCount > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp)
                                        .sizeIn(minWidth = 13.dp, minHeight = 13.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                        .padding(horizontal = 3.dp)
                                        .semantics { hideFromAccessibility() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        unreadLabel,
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class HeaderMenuShape(
    val fraction: Float,
    val capsuleWidth: Dp,
    val capsuleHeight: Dp,
    val capsuleTop: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val width = androidx.compose.ui.util.lerp(capsuleWidth.toPx().coerceAtMost(size.width), size.width, fraction)
        val height = androidx.compose.ui.util.lerp(capsuleHeight.toPx().coerceAtMost(size.height), size.height, fraction)
        val top = (capsuleTop.toPx() * (1f - fraction)).coerceAtMost(size.height - height)
        val left = if (layoutDirection == LayoutDirection.Ltr) size.width - width else 0f
        val radius = (24.dp - 12.dp * fraction).toPx().coerceAtMost(minOf(width, height) / 2f)
        Outline.Rounded(RoundRect(Rect(left, top, left + width, top + height), CornerRadius(radius)))
    }
}

@Composable
internal fun CatsCoMenuActionItem(
    title: String,
    icon: Int,
    enabled: Boolean = true,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
    dimWhenDisabled: Boolean = true,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled || !dimWhenDisabled) color else color.copy(alpha = color.alpha * .38f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.menuItemHeight)
            .clip(CatsCoMobileSpec.radiusSmall).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = CatsCoMobileSpec.menuHorizontalPadding - CatsCoMobileSpec.menuInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CatsCoMobileSpec.menuLabelGap),
    ) {
        Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.menuIconSize), tint = contentColor)
        Text(
            title,
            modifier = if (trailingText != null) Modifier.weight(1f) else Modifier,
            style = CatsCoMobileSpec.menuLabelStyle,
            fontWeight = fontWeight,
            color = contentColor,
        )
        if (trailingText != null) {
            Text(trailingText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun CatsCoComposerFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.shadow(
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
    onMoveToProject: (() -> Unit)? = null,
) {
    CatsCoMenuPopup(expanded, onDismiss) {
        CatsCoMenuActionItem("重命名", R.drawable.e_edit) { onDismiss(); onRename() }
        CatsCoMenuActionItem(if (pinned) "取消置顶" else "置顶", R.drawable.e_pin) { onDismiss(); onTogglePin() }
        if (onMoveToProject != null) CatsCoMenuActionItem("移到项目", R.drawable.ic_project_outline) { onDismiss(); onMoveToProject() }
        CatsCoMenuActionItem("多选", R.drawable.e_select) { onDismiss(); onSelectMore() }
        CatsCoMenuActionItem("从本机隐藏", R.drawable.message_eye_off) {
            onDismiss()
            onDelete()
        }
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
            CatsCoMenuActionItem(title, icon, enabled = index != 2 || regenerateEnabled) {
                onDismiss()
                action()
            }
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
    val transition = updateTransition(expanded, label = "anchoredMenu")
    val visibility = transition.animateFloat(
        transitionSpec = { tween(if (targetState) 145 else 100, easing = LinearOutSlowInEasing) },
        label = "anchoredMenuVisibility",
    ) { if (it) 1f else 0f }
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
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
                    origin = TransformOrigin(
                        ((anchorPoint.x - left).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        if (below <= maxY) 0f else 1f,
                    )
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
                origin = TransformOrigin(if (layoutDirection == LayoutDirection.Ltr) 0f else 1f, if (below <= maxY) 0f else 1f)
                return IntOffset(start.coerceIn(margin, maxX), top)
            }
        }
    }
    if (transition.currentState || transition.targetState) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = expanded),
        ) {
            CompositionLocalProvider(LocalDensity provides density) {
                Surface(
                    modifier = Modifier.testTag(testTag).graphicsLayer {
                        alpha = visibility.value
                        scaleX = .96f + .04f * visibility.value
                        scaleY = .96f + .04f * visibility.value
                        transformOrigin = origin
                    }.semantics { if (!expanded) hideFromAccessibility() }.catsCoFloatingShadow(CatsCoMobileSpec.menuShape),
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
