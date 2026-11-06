package com.x.carlauncher.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayDark
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val label: String,
    val packageName: String,
    val iconBitmap: Bitmap?
)

/**
 * In-memory cache for ultra-fast, zero-lag opening of the App Drawer.
 */
object AppDrawerCache {
    @Volatile
    var cachedApps: List<InstalledApp>? = null

    suspend fun getOrLoad(context: Context): List<InstalledApp> {
        cachedApps?.let { return it }
        val loaded = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        cachedApps = loaded
        return loaded
    }

    fun invalidate() {
        cachedApps = null
    }
}

/**
 * Native, automotive-grade App Drawer displaying all installed applications on the head unit.
 * Features live search, rapid alphabetical filtering, and touch-optimized launch targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerScreen(
    isOpen: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    BackHandler { onClose() }

    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(AppDrawerCache.cachedApps ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppDrawerCache.cachedApps == null) }

    LaunchedEffect(Unit) {
        if (AppDrawerCache.cachedApps == null) {
            val loaded = AppDrawerCache.getOrLoad(context)
            apps = loaded
            isLoading = false
        }
    }

    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0060B19))
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ColorBorder),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // ── Top Bar: Title, Search Field, and Close Button ────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Todas las Aplicaciones",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isLoading) "Cargando..." else "${filteredApps.size} apps disponibles",
                            fontSize = 12.sp,
                            color = ColorGrayMid
                        )
                    }

                    // Search input
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar aplicacion...", fontSize = 13.sp, color = ColorGrayMid) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar",
                                tint = ColorGrayMid,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1E293B),
                            unfocusedContainerColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .width(280.dp)
                            .height(46.dp)
                    )

                    // Close Button (X)
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFF334155), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Apps Grid ────────────────────────────────────────────────
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorPrimary)
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No se encontraron aplicaciones", color = ColorGrayMid, fontSize = 16.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6), // 6 columns fits landscape 1280x720 perfectly
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppGridItem(
                                app = app,
                                onClick = {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    if (launchIntent != null) {
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try {
                                            context.startActivity(launchIntent)
                                            onClose()
                                        } catch (e: Exception) {
                                            android.util.Log.e("AppDrawer", "Failed to launch ${app.packageName}", e)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridItem(
    app: InstalledApp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ColorPrimary.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = app.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Loads all launchable apps using PackageManager and decodes icons into hardware bitmaps.
 */
private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val selfPkg = context.packageName

    val disabledLaunchers = setOf(
        "com.android.launcher2", "com.android.launcher3", "com.android.launcher4",
        "com.android.launcher5", "com.android.launcher8", "com.android.launcher11",
        "com.android.launcher20", "com.android.launcher24", "com.android.launcher27",
        "com.android.launcher30", "com.android.launcher43", "com.android.launcher44",
        "com.android.launcher47", "com.android.launcher51", "com.sprd.powersavemodelauncher"
    )

    return resolveInfos
        .asSequence()
        .filter { it.activityInfo != null }
        .filter { it.activityInfo.packageName != selfPkg }
        .filter { !disabledLaunchers.contains(it.activityInfo.packageName) }
        .map { resolveInfo ->
            val label = try {
                resolveInfo.loadLabel(pm).toString()
            } catch (_: Exception) {
                resolveInfo.activityInfo.packageName
            }

            val iconBitmap = try {
                val drawable = resolveInfo.loadIcon(pm)
                drawableToBitmap(drawable)
            } catch (_: Exception) {
                null
            }

            InstalledApp(
                label = label,
                packageName = resolveInfo.activityInfo.packageName,
                iconBitmap = iconBitmap
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 72
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 72
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
