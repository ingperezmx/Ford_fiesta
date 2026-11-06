package com.x.carlauncher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.x.carlauncher.R
import com.x.carlauncher.carassets.CarAssetManager
import com.x.carlauncher.carassets.CarSlot
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.XTheme

private data class NavItem(
    val icon: ImageVector?,
    val contentDescription: String,
    val accentColor: Color,
    val isCarIcon: Boolean = false,
    val isMicrosoftLauncher: Boolean = false
)

private val navItems = listOf(
    NavItem(Icons.Default.Home,       "Inicio",             Color(0xFF38BDF8)), // Neon Cyan
    NavItem(Icons.Default.Navigation, "Navegacion",         Color(0xFF00E5FF)), // Vibrant Sky - Flecha GPS
    NavItem(Icons.Default.Phone,      "Contactos",          Color(0xFF10B981)), // Emerald Green
    NavItem(Icons.Default.MusicNote,  "Musica",             Color(0xFFA855F7)), // Electric Violet
    NavItem(Icons.Default.Explore,    "Mapa Full",          Color(0xFFF59E0B)), // Sunset Amber
    NavItem(
        icon = null,
        contentDescription = "Microsoft Launcher",
        accentColor = Color(0xFF00A4EF), // Microsoft Fluent Blue
        isMicrosoftLauncher = true
    )
)

/**
 * High-end Automotive Left Navigation Dock.
 * Features luxury glassmorphic styling, personality accent colors for each vehicle function,
 * animated glow pills, and smooth tactile responsiveness.
 */
@Composable
fun LeftNavigationDock(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = XTheme.colors
    val dockBackgroundBrush = remember(colors) {
        Brush.verticalGradient(colors.dockGradient)
    }

    Box(
        modifier = modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(dockBackgroundBrush)
    ) {
        // Subtle vertical dividing separator on right edge
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            colors.border.copy(alpha = 0.5f),
                            colors.secondary.copy(alpha = 0.2f),
                            colors.border.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            navItems.forEachIndexed { index, item ->
                NavDockItem(
                    item       = item,
                    isSelected = index == selectedTab,
                    onClick    = { onTabSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun NavDockItem(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Animated states for smooth automotive transition
    val indicatorHeight by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 0.dp,
        animationSpec = tween(durationMillis = 280),
        label = "indicatorHeight"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "itemScale"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else item.accentColor.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 200),
        label = "iconColor"
    )

    val containerBg = if (isSelected) {
        Brush.linearGradient(
            colors = listOf(
                item.accentColor.copy(alpha = 0.35f),
                item.accentColor.copy(alpha = 0.15f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1E293B).copy(alpha = 0.35f),
                Color(0xFF0F172A).copy(alpha = 0.45f)
            )
        )
    }

    val borderColor = if (isSelected) {
        item.accentColor.copy(alpha = 0.75f)
    } else {
        Color(0xFF334155).copy(alpha = 0.3f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glowing illuminated bar on the LEFT edge
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(indicatorHeight)
                .background(
                    color = if (isSelected) item.accentColor else Color.Transparent,
                    shape = RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                )
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Luxury Icon Container Box
        Box(
            modifier = Modifier
                .size(42.dp)
                .scale(scale)
                .clip(RoundedCornerShape(13.dp))
                .background(containerBg)
                .border(
                    BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Subtle glowing backlight behind the icon when selected
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(item.accentColor.copy(alpha = 0.25f), CircleShape)
                )
            }

            if (item.isCarIcon) {
                val context = LocalContext.current
                val carBitmap = remember { CarAssetManager.getCustomBitmap(context, CarSlot.BASE) }
                if (carBitmap != null) {
                    Image(
                        bitmap = carBitmap,
                        contentDescription = item.contentDescription,
                        modifier = Modifier
                            .size(34.dp)
                            .padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.corolla_base),
                        contentDescription = item.contentDescription,
                        modifier = Modifier
                            .size(34.dp)
                            .padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else if (item.isMicrosoftLauncher) {
                MicrosoftLauncherIcon(isSelected = isSelected)
            } else if (item.icon != null) {
                Icon(
                    imageVector        = item.icon,
                    contentDescription = item.contentDescription,
                    tint               = iconColor,
                    modifier           = Modifier.size(23.dp)
                )
            }
        }
    }
}

/**
 * Icono de Microsoft Launcher para el Dock lateral.
 * Carga el icono oficial nativo directamente desde el sistema (si esta instalado)
 * con fallback visual de alta definicion al logotipo Fluent de Microsoft (4 colores).
 */
@Composable
private fun MicrosoftLauncherIcon(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val launcherBitmap = remember {
        try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon("com.microsoft.launcher")
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            drawable.toBitmap(w, h).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    if (launcherBitmap != null) {
        Image(
            bitmap = launcherBitmap,
            contentDescription = "Microsoft Launcher",
            modifier = modifier
                .size(25.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        // Logotipo oficial Fluent 4-color de Microsoft (Rojo, Verde, Azul, Amarillo)
        Box(
            modifier = modifier.size(21.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(Color(0xFFF25022), RoundedCornerShape(2.dp)) // Rojo Microsoft
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(Color(0xFF7FBA00), RoundedCornerShape(2.dp)) // Verde Microsoft
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(Color(0xFF00A4EF), RoundedCornerShape(2.dp)) // Azul Microsoft
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(Color(0xFFFFB900), RoundedCornerShape(2.dp)) // Amarillo Microsoft
                    )
                }
            }
        }
    }
}
