package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.data.attachmentDescription
import cc.catsco.core.Attachment
import cc.catsco.core.Session
import coil.ImageLoader

@Composable
internal fun ComposerAttachments(
    files: List<Attachment>,
    session: Session?,
    imageLoader: ImageLoader,
    previewEnabled: Boolean,
    onPreview: (Attachment) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("composer-attachments").horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEachIndexed { index, file ->
            if (file.mime.startsWith("image/")) {
                Box(Modifier.width(88.dp).height(72.dp)) {
                    CatsCoAccountAvatar(
                        session?.copy(avatarUrl = file.url), imageLoader,
                        modifier = Modifier.align(Alignment.BottomStart).size(64.dp)
                            .clickable(enabled = previewEnabled, role = Role.Button) { onPreview(file) }
                            .semantics { contentDescription = "预览图片 ${index + 1}" },
                        shape = RoundedCornerShape(10.dp), fallbackIcon = R.drawable.e_image,
                        contentScale = ContentScale.Fit,
                    )
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.e_close), "移除图片 ${index + 1}", Modifier.size(CatsCoMobileSpec.actionIconSize),
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.width(208.dp).heightIn(min = 72.dp), shape = CatsCoMobileSpec.contentCardShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clickable(enabled = previewEnabled, role = Role.Button) { onPreview(file) }
                                .semantics { contentDescription = "预览 ${file.name}" }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(painterResource(R.drawable.e_file), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(file.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(attachmentDescription(file), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.e_close), "移除 ${file.name}", Modifier.size(CatsCoMobileSpec.actionIconSize),
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
