package am.khosq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line of an action sheet: an icon, a label, and what happens on tap. */
class SheetAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The one bottom sheet every screen uses: rounded top, a soft handle, the theme's surface.
 * Content goes in a padded column; the sheet itself takes care of the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val k = KTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(topStart = Corner.xl, topEnd = Corner.xl),
        dragHandle = { BottomSheetDefaults.DragHandle(color = k.lineStrong) },
    ) {
        Column(modifier = Modifier.padding(bottom = Space.l), content = content)
    }
}

/** A tappable row for sheets and lists: a soft icon tile, a label and an optional second line. */
@Composable
fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    danger: Boolean = false,
) {
    val k = KTheme.colors
    val tone = if (danger) k.danger else k.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(Corner.s + 2.dp)).background(tone.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (danger) k.danger else k.text,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(text = subtitle, color = k.textSecondary, style = TextStyle(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A sheet with a short list of actions, for long-press menus. */
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val k = KTheme.colors
    KSheet(onDismiss = onDismiss) {
        if (title != null) {
            Text(
                text = title,
                color = k.text,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s),
            )
        }
        for (a in actions) {
            SheetRow(icon = a.icon, label = a.label, danger = a.danger, onClick = a.onClick)
        }
        Spacer(Modifier.height(Space.xs))
    }
}

/** The title line at the top of a sheet that has a form or details in it. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = KTheme.colors.text,
        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.padding(horizontal = Space.xl, vertical = Space.xs),
    )
}

/** Dimmed helper text under a title or field. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = KTheme.colors.textSecondary) {
    Text(
        text = text,
        color = color,
        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        modifier = modifier,
    )
}
