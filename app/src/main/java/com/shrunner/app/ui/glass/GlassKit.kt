package com.shrunner.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shrunner.app.R
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTextStyle
import io.github.neoruaa.glasmio.core.IosTextVariant
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

/*
 * Every Glasmio entry point that is shared between screens is wrapped here, so a Glasmio API change
 * only needs fixing in one place. Names and parameters follow docs/components.md and the sample app.
 */

val BottomBarHeight = 64.dp

/** Space a page must leave at the bottom for the floating tab bar. */
@Composable
fun bottomBarSpace(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight + 24.dp

@Composable
fun glassContentColor(): Color = if (LocalGlassColors.current.isDark) Color.White else Color.Black

@Composable
fun glassSecondaryColor(): Color = LocalGlassColors.current.secondaryContent

@Composable
fun glassBody(): TextStyle = GlasmioTheme.textStyles.body

@Composable
fun glassBodyEmphasized(): TextStyle = GlasmioTheme.textStyles[IosTextStyle.Body, IosTextVariant.Emphasized]

/** Theme + the backdrop host every Glasmio control needs. [content] is drawn as the overlay layer. */
@Composable
fun GlassAppHost(dark: Boolean, reduceTransparency: Boolean, content: @Composable () -> Unit) {
    GlasmioTheme(isDark = dark, appearance = GlassAppearance(reduceTransparency = reduceTransparency)) {
        val colors = GlasmioTheme.colors
        GlasmioBackdropHost(
            modifier = Modifier.fillMaxSize(),
            sampledContent = { Box(Modifier.fillMaxSize().background(colors.groupedBackground)) },
            overlayContent = {
                // Material Text/Icon default to black without a Surface; give them the theme's content color.
                CompositionLocalProvider(LocalContentColor provides glassContentColor()) { content() }
            },
        )
    }
}

/** Simple top bar for pushed screens (editor, run). Tab pages use GlasmioPinnedListPage's own title. */
@Composable
fun GlassTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            GlasmioIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = glassBodyEmphasized(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

fun alertButton(label: String, onClick: () -> Unit, cancel: Boolean = false): GlasmioAlertButtonSpec =
    if (cancel) GlasmioAlertButtonSpec(label, onClick, role = GlasmioAlertButtonRole.Cancel)
    else GlasmioAlertButtonSpec(label, onClick)

/** Alert with a title line and optional second line (the title slot is the only text slot used). */
@Composable
fun GlassAlert(
    visible: Boolean,
    title: String,
    message: String?,
    buttons: List<GlasmioAlertButtonSpec>,
    onDismiss: () -> Unit,
) {
    GlasmioAlertDialog(
        visible = visible,
        title = if (message.isNullOrEmpty()) title else "$title\n$message",
        onDismissRequest = onDismiss,
        buttons = buttons,
    )
}

/** Short bottom panel for forms and confirmations that need more than an alert. */
@Composable
fun GlassSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlasmioBottomSheet(visible = visible, onDismissRequest = onDismiss, fitContent = true) {
        content()
    }
}

