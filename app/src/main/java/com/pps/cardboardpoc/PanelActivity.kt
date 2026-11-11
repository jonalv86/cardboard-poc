package com.pps.cardboardpoc

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.pps.cardboardpoc.ui.theme.CeluVRTheme
import com.pps.cardboardpoc.vdisplay.MultiWindowTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Panel de apps del visor: lista todas las apps lanzables, con una sección propia arriba para las
 * abiertas en la sesión y el listado completo debajo.
 */
class PanelActivity : ComponentActivity() {

    private val apps = mutableStateOf<List<LaunchableApp>>(emptyList())
    private val recentComponents = mutableStateOf<List<ComponentName>>(emptyList())
    private val gridState = LazyGridState()

    fun scrollToTop() {
        lifecycleScope.launch {
            gridState.scrollToItem(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            CeluVRTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val capturing by ScreenCaptureService.capturing.collectAsState()
                    LaunchedEffect(capturing) {
                        if (!capturing) finish()
                    }
                    AppPanel(
                        apps = apps.value,
                        recentComponents = recentComponents.value,
                        onAppClick = { launch(it.component) },
                        gridState = gridState
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        JoystickManager.panelInFront = false
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) pendingLaunch = null
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        JoystickManager.panelInFront = true
        if (ScreenCaptureService.portrait.value) {
            startService(ScreenCaptureService.portraitIntent(this, false))
        }
        reopenIfClosedOnLaunch()
        openRequestedPackage()
        lifecycleScope.launch {
            apps.value = withContext(Dispatchers.Default) { loadApps() }
            recentComponents.value = SessionApps.getRecent()
        }
        window.decorView.postDelayed(::warnIfPanelNotCaptured, CAPTURE_CHECK_DELAY_MS)
    }

    private fun openRequestedPackage() {
        val packageName = intent.getStringExtra(EXTRA_OPEN_PACKAGE) ?: return
        intent.removeExtra(EXTRA_OPEN_PACKAGE)
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
        if (component == null) {
            Log.w(TAG, "panel: $packageName no tiene actividad de inicio")
            return
        }
        window.decorView.post { launch(component) }
    }

    private fun warnIfPanelNotCaptured() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ScreenCaptureService.isCapturing && !ScreenCaptureService.capturedTaskVisible) {
            Log.w(TAG, "panel al frente sin captura: en el selector no se eligió CeluVR")
        }
    }

    private fun loadApps(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                LaunchableApp(
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label = info.loadLabel(packageManager).toString(),
                    info = info
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Abre [component]. En la pantalla virtual cada app va en su propia tarea, en modo multiventana; con
     * la proyección se abre dentro de la tarea de CeluVR, que es la que se captura, salvo las que se
     * cierran solas ahí.
     */
    private fun launch(component: ComponentName) {
        val virtual = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            JoystickManager.targetDisplayId != Display.DEFAULT_DISPLAY
        val ownTask = virtual || SessionApps.needsOwnTask(component)
        val flags = if (ownTask) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(flags)
        try {
            startActivity(intent, if (virtual) multiWindowOptions() else null)
            SessionApps.markUsed(component)
            recentComponents.value = SessionApps.getRecent()
            pendingLaunch = if (ownTask) null else PendingLaunch(component, SystemClock.uptimeMillis())
            Log.d(TAG, "panel: abre ${component.flattenToShortString()} tareaPropia=$ownTask")
        } catch (e: Exception) {
            Log.w(TAG, "panel: no se pudo abrir ${component.flattenToShortString()}", e)
        }
    }

    /**
     * Opciones para abrir una app en modo multiventana ocupando toda la pantalla virtual. Ver
     * [MultiWindowTasks]. Si el sistema no acepta el modo, la app se abre como siempre.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun multiWindowOptions(): Bundle {
        val options = ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(0, 0, JoystickManager.screenWidth, JoystickManager.screenHeight))
        try {
            HiddenApiBypass.invoke(
                ActivityOptions::class.java, options, "setLaunchWindowingMode",
                MultiWindowTasks.WINDOWING_MODE_MULTI_WINDOW
            )
        } catch (e: Exception) {
            Log.w(TAG, "panel: no se pudo pedir el modo multiventana", e)
        }
        return options.toBundle()
    }

    private fun reopenIfClosedOnLaunch() {
        val launch = pendingLaunch ?: return
        pendingLaunch = null
        if (SystemClock.uptimeMillis() - launch.atMs > SELF_CLOSE_WINDOW_MS) return

        Log.d(TAG, "panel: ${launch.component.flattenToShortString()} se cerró sola; se abre aparte")
        SessionApps.markNeedsOwnTask(launch.component)
        launch(launch.component)
    }

    private class PendingLaunch(val component: ComponentName, val atMs: Long)

    companion object {
        private const val TAG = "CardboardCapture"
        private var pendingLaunch: PendingLaunch? = null
        private const val SELF_CLOSE_WINDOW_MS = 1500L
        private const val CAPTURE_CHECK_DELAY_MS = 500L

        private var instance: PanelActivity? = null

        fun scrollToTop() {
            instance?.scrollToTop()
        }

        private const val EXTRA_OPEN_PACKAGE = "open_package"

        fun show(context: Context, openPackage: String? = null) {
            val displayId = JoystickManager.targetDisplayId
            val options = if (displayId != Display.DEFAULT_DISPLAY) {
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            } else {
                null
            }
            context.startActivity(
                Intent(context, PanelActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                    .putExtra(EXTRA_OPEN_PACKAGE, openPackage),
                options
            )
        }
    }
}

/** App que aparece en el panel. */
class LaunchableApp(
    val component: ComponentName,
    val label: String,
    val info: ResolveInfo
)

@Composable
private fun AppPanel(
    apps: List<LaunchableApp>,
    recentComponents: List<ComponentName>,
    onAppClick: (LaunchableApp) -> Unit,
    gridState: LazyGridState
) {
    val recentApps = recentComponents.mapNotNull { comp -> apps.find { it.component == comp } }
    // Los recientes llegan después que el resto y la grilla deja fijo el primer elemento visible:
    // sin volver al principio, quedarían arriba, fuera de la vista.
    LaunchedEffect(recentApps.map { it.component }) {
        gridState.scrollToItem(0)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (recentApps.isNotEmpty()) {
            item(key = "recent_title", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("RECIENTES", first = true)
            }
            items(recentApps, key = { "recent_${it.component.flattenToString()}" }) { app ->
                AppTile(app = app, onClick = { onAppClick(app) })
            }
        }
        item(key = "all_title", span = { GridItemSpan(maxLineSpan) }) {
            SectionTitle("TODAS LAS APLICACIONES", first = recentApps.isEmpty())
        }
        items(apps, key = { "all_${it.component.flattenToString()}" }) { app ->
            AppTile(app = app, onClick = { onAppClick(app) })
        }
    }
}

/** Título de una sección del panel; separado de la sección de arriba salvo que sea [first]. */
@Composable
private fun SectionTitle(text: String, first: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (first) 0.dp else 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun AppTile(app: LaunchableApp, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        val icon = rememberAppIcon(app.info)
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(64.dp))
        } else {
            Spacer(modifier = Modifier.size(64.dp))
        }
        Text(
            text = app.label,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun rememberAppIcon(info: ResolveInfo): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, info) {
        value = withContext(Dispatchers.IO) {
            info.loadIcon(context.packageManager).toBitmap().asImageBitmap()
        }
    }
    return icon
}
