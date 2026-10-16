package com.pps.cardboardpoc

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.pps.cardboardpoc.ui.theme.CeluVRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel de apps del visor: lista todas las apps lanzables, con las usadas en la sesión primero. Vive
 * en la tarea de CeluVR y abre cada app sin FLAG_ACTIVITY_NEW_TASK, así que las que lo permiten
 * quedan dentro de esa tarea, que es la que captura la proyección. Las que se van a una tarea propia
 * se siguen viendo, más lentas, con la captura por ventanas.
 */
class PanelActivity : ComponentActivity() {

    private val apps = mutableStateOf<List<LaunchableApp>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El panel es el inicio del visor: Atrás no tiene a dónde volver.
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
                    AppPanel(apps = apps.value, onAppClick = { launch(it.component) })
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isInFront = false
    }

    override fun onStop() {
        super.onStop()
        // La app abierta llegó a tapar el panel: si el panel vuelve, es porque el usuario volvió. Un
        // stop por recreación no cuenta: lo provoca la app que fuerza vertical al abrirse y cerrarse.
        if (!isChangingConfigurations) pendingLaunch = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isInFront = true
        // El panel está pensado en horizontal: en una pantalla vertical el sistema lo encaja chico.
        if (ScreenCaptureService.portrait.value) {
            startService(ScreenCaptureService.portraitIntent(this, false))
        }
        reopenIfClosedOnLaunch()
        openRequestedPackage()
        // Se recalcula al volver para que la app recién usada pase adelante.
        lifecycleScope.launch {
            apps.value = withContext(Dispatchers.Default) { loadApps() }
        }
        window.decorView.postDelayed(::warnIfPanelNotCaptured, CAPTURE_CHECK_DELAY_MS)
    }

    /** Abre la app pedida con [show], si hay una, una vez que el panel quedó al frente. */
    private fun openRequestedPackage() {
        val packageName = intent.getStringExtra(EXTRA_OPEN_PACKAGE) ?: return
        intent.removeExtra(EXTRA_OPEN_PACKAGE)
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
        if (component == null) {
            Log.w(TAG, "panel: $packageName no tiene actividad de inicio")
            return
        }
        // Después del resume, como un toque del usuario: si no, el chequeo de apps que se cierran
        // solas confundiría este lanzamiento con uno anterior.
        window.decorView.post { launch(component) }
    }

    /**
     * Con el panel al frente, la tarea capturada tendría que estar a la vista. Si no lo está, en el
     * selector se eligió otra app: todo menos esa app se ve con la captura por ventanas.
     */
    private fun warnIfPanelNotCaptured() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ScreenCaptureService.isCapturing && !ScreenCaptureService.capturedTaskVisible) {
            Log.w(TAG, "panel al frente sin captura: en el selector no se eligió CeluVR")
        }
    }

    private fun loadApps(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ranks = SessionApps.ranks()
        return packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                LaunchableApp(
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label = info.loadLabel(packageManager).toString(),
                    info = info
                )
            }
            .sortedWith(
                compareBy<LaunchableApp> { ranks[it.component] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
    }

    /**
     * Abre la app dentro de la tarea del visor, que es la que se ve a fps completos. Las que se sabe
     * que no lo toleran se abren en una tarea propia y se ven con la captura por ventanas.
     */
    private fun launch(component: ComponentName) {
        val ownTask = SessionApps.needsOwnTask(component)
        // Si la app ya está abierta dentro de la tarea, REORDER_TO_FRONT la trae en vez de apilar
        // otra instancia.
        val flags = if (ownTask) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(flags)
        try {
            startActivity(intent)
            SessionApps.markUsed(component)
            pendingLaunch = if (ownTask) null else PendingLaunch(component, SystemClock.uptimeMillis())
            Log.d(TAG, "panel: abre ${component.flattenToShortString()} tareaPropia=$ownTask")
        } catch (e: Exception) {
            Log.w(TAG, "panel: no se pudo abrir ${component.flattenToShortString()}", e)
        }
    }

    /**
     * Algunas apps cierran su actividad de inicio si no es la raíz de su tarea, que es lo que pasa al
     * abrirlas dentro de la del visor: el panel vuelve al frente sin haber quedado tapado. En ese caso
     * se la abre de nuevo en una tarea propia, y se recuerda para el resto de la sesión.
     */
    private fun reopenIfClosedOnLaunch() {
        val launch = pendingLaunch ?: return
        pendingLaunch = null
        if (SystemClock.uptimeMillis() - launch.atMs > SELF_CLOSE_WINDOW_MS) return

        Log.d(TAG, "panel: ${launch.component.flattenToShortString()} se cerró sola; se abre aparte")
        SessionApps.markNeedsOwnTask(launch.component)
        launch(launch.component)
    }

    /** App recién abierta dentro de la tarea del visor, y cuándo. */
    private class PendingLaunch(val component: ComponentName, val atMs: Long)

    companion object {
        private const val TAG = "CardboardCapture"

        /**
         * Última app abierta dentro de la tarea del visor que todavía no tapó el panel. Vive acá y no
         * en la actividad porque una app que fuerza vertical puede hacer que el panel se recree.
         */
        private var pendingLaunch: PendingLaunch? = null

        /** Si el panel vuelve antes de esto sin haber quedado tapado, la app se cerró sola al abrirse. */
        private const val SELF_CLOSE_WINDOW_MS = 1500L

        /** Margen para que el aviso de visibilidad de la tarea capturada llegue después de traer el panel. */
        private const val CAPTURE_CHECK_DELAY_MS = 500L

        /** Si el panel está al frente. Lo lee el hilo de render para armar la barra de controles. */
        @Volatile
        var isInFront = false
            private set

        /** App que el panel abre apenas vuelve al frente, como si se la hubiera elegido ahí. */
        private const val EXTRA_OPEN_PACKAGE = "open_package"

        /**
         * Trae el panel al frente desde cualquier contexto. Con NEW_TASK el sistema usa la tarea de
         * CeluVR si ya existe, y REORDER_TO_FRONT sube el panel dentro de ella sin crear otro.
         * NO_USER_ACTION evita que la app de adelante lo tome como que el usuario la deja y pase a
         * ventana flotante (YouTube lo hace): flotando sigue contando como visible, y si es la app
         * capturada el visor nunca pasaría a la captura por ventanas.
         * Con [openPackage], el panel abre esa app al volver al frente. Se abre en la pantalla destino
         * de los toques, que con la pantalla virtual del visor no es la física.
         */
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
    /** Actividad de lanzamiento de la app. */
    val component: ComponentName,
    /** Nombre visible de la app. */
    val label: String,
    /** Datos del sistema con los que se carga el ícono. */
    val info: ResolveInfo
)

@Composable
private fun AppPanel(apps: List<LaunchableApp>, onAppClick: (LaunchableApp) -> Unit) {
    // Celdas grandes: dentro del visor la pantalla se ve chica y el cursor tiene que acertarles.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(apps, key = { it.component.flattenToString() }) { app ->
            AppTile(app = app, onClick = { onAppClick(app) })
        }
    }
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

/** Carga el ícono fuera del main thread: con más de 200 apps, cargarlos juntos traba el panel. */
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
