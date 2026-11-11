package com.pps.cardboardpoc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton encargado de gestionar el estado del cursor virtual compartido entre la captura de pantalla
 * y el servicio de accesibilidad. Controla las coordenadas cartesianas, los límites físicos de la pantalla
 * y delega la ejecución de gestos (toques) en el dispositivo.
 */
object JoystickManager {
    private const val TAG = "JoystickManager"
    
    /** Posición horizontal del cursor, en píxeles de pantalla. La lee el hilo de render en cada cuadro. */
    @Volatile
    var cursorX = 0f
        private set

    /** Posición vertical del cursor, en píxeles de pantalla. La lee el hilo de render en cada cuadro. */
    @Volatile
    var cursorY = 0f
        private set

    /** Ancho de la pantalla capturada en píxeles; 0 hasta que arranca la primera captura. */
    @Volatile
    var screenWidth = 0
        private set

    /** Alto de la pantalla capturada en píxeles; 0 hasta que arranca la primera captura. */
    @Volatile
    var screenHeight = 0
        private set

    /** Pantalla donde caen los toques y donde se abre el panel: la física, o la virtual del visor. */
    @Volatile
    var targetDisplayId = Display.DEFAULT_DISPLAY

    /** Posición horizontal que aporta el joystick, en píxeles de pantalla. */
    private var baseX = 0f

    /** Posición vertical que aporta el joystick, en píxeles de pantalla. */
    private var baseY = 0f

    /** Desplazamiento horizontal que aporta la cabeza, en píxeles; 0 sin giroscopio. */
    private var headOffsetX = 0f

    /** Desplazamiento vertical que aporta la cabeza, en píxeles; 0 sin giroscopio. */
    private var headOffsetY = 0f

    private var lastHeadOffsetX = 0f
    private var lastHeadOffsetY = 0f

    /** Cursor y parte de la pantalla que se ve en el visor, tomados en el mismo instante. */
    class Snapshot(
        /** Posición horizontal del cursor, en píxeles de pantalla. */
        val cursorX: Float,
        /** Posición vertical del cursor, en píxeles de pantalla. */
        val cursorY: Float,
        /** Parte de la pantalla que se ve en el visor. */
        val window: ZoomWindow,
        /** Posición horizontal, en píxeles de pantalla, en la que está anclada la [ControlBar]. */
        val barAnchorX: Float
    ) {
        /** Lugar de la [ControlBar] en la vista. */
        val barFrame: ControlBar.Frame get() = ControlBar.frame(window, barAnchorX)
    }

    /** Zoom máximo de la imagen del visor. */
    const val MAX_ZOOM = 3f

    /** Zona muerta con que arranca el zoom: la ventana sigue al cursor centrada, como una pantalla de cine. */
    private const val DEFAULT_ZOOM_DEAD_ZONE = 0f

    /**
     * Zoom pedido para la imagen del visor, entre 1 (la pantalla entera) y [MAX_ZOOM]. Lo lee el hilo
     * de render para mostrarlo en la barra.
     */
    @Volatile
    var zoomLevel = 1f
        private set

    /** Fracción de la ventana con zoom en la que el cursor se mueve sin arrastrarla; ver [ZoomWindow.follow]. */
    private var zoomDeadZone = DEFAULT_ZOOM_DEAD_ZONE

    /** Cómo entra la imagen en el ojo, según el render. */
    private var zoomFit = ZoomFit.FULL

    /** Medio alto máximo de la imagen con zoom que reemplaza al del render, o 0 para usar el del render. */
    private var zoomMaxHalfHeight = 0f

    /** Parte de la pantalla que se ve en el visor; se modifica con el lock tomado. */
    private var zoomWindow = ZoomWindow.of(0, 0, ZoomFit.FULL, 1f)

    /**
     * Cursor y ventana visible del último movimiento. El hilo de render lo lee una vez por cuadro, así
     * dibuja la imagen y la retícula con la misma ventana.
     */
    @Volatile
    var snapshot = Snapshot(0f, 0f, zoomWindow, 0f)
        private set

    /**
     * Posición horizontal, en píxeles de pantalla, en la que se ancla la [ControlBar]. Mientras está
     * oculta sigue al centro de la ventana visible, así aparece centrada en la vista; mientras está a la
     * vista queda fija, y con zoom el cursor recorre sus botones.
     */
    private var barAnchorX = 0f

    /**
     * Cuántas veces cambió el zoom efectivo. La fuente de la cabeza lo compara con el último valor que
     * atendió para tomar como frente la orientación de ese momento: mide su desplazamiento con la
     * escala del zoom, y con la escala nueva el cursor saltaría.
     */
    @Volatile
    var zoomChangeCount = 0
        private set

    // Umbral de zona muerta para contrarrestar el drift natural de los sticks analógicos
    private const val DEAD_ZONE = 0.15f

    // Velocidad del cursor en píxeles por segundo: arranca lenta para poder apuntar fino y acelera
    // mientras se mantiene la dirección.
    private const val MIN_SPEED = 400f
    private const val MAX_SPEED = 1600f
    private const val ACCELERATION_MS = 1000f
    private const val TICK_MS = 16L

    private val handler = Handler(Looper.getMainLooper())

    // El stick digital del VR BOX solo manda un evento cuando cambia de valor, y el D-pad uno al
    // apretar y otro al soltar: lo que se guarda es la dirección vigente, y el tick la aplica.
    private var stickX = 0f
    private var stickY = 0f
    private var dpadLeft = false
    private var dpadRight = false
    private var dpadUp = false
    private var dpadDown = false

    private var moveStartMs = 0L
    private var lastTickMs = 0L
    private var moving = false

    private val tick = object : Runnable {
        override fun run() {
            val dx = directionX()
            val dy = directionY()
            if (dx == 0f && dy == 0f) {
                moving = false
                Log.d(TAG, "Cursor detenido en ($cursorX, $cursorY) tras ${lastTickMs - moveStartMs} ms")
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsedSeconds = (now - lastTickMs) / 1000f
            lastTickMs = now
            val ramp = ((now - moveStartMs) / ACCELERATION_MS).coerceAtMost(1f)
            // La velocidad es la que se ve en el visor: con zoom, el mismo recorrido es menos pantalla.
            val speed = (MIN_SPEED + (MAX_SPEED - MIN_SPEED) * ramp) / snapshot.window.zoom
            moveBase(dx * speed * elapsedSeconds, dy * speed * elapsedSeconds)
            refreshEdgeContact()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Si el gatillo tiene apoyado el dedo simulado. */
    private var pressing = false

    /** Dedo simulado apoyado con el gatillo. */
    class Press(
        /** Momento (uptime en ms) en que se apoyó el dedo. */
        val startMs: Long,
        /** Posición horizontal donde se apoyó, en píxeles de pantalla. */
        val originX: Float,
        /** Posición vertical donde se apoyó, en píxeles de pantalla. */
        val originY: Float
    )

    /** Dedo apoyado con el gatillo, o null si no hay. Lo lee el hilo de render en cada cuadro. */
    @Volatile
    var press: Press? = null
        private set

    /**
     * Si la [ControlBar] está a la vista. Se actualiza con cada movimiento del cursor según
     * [ControlBar.nextVisible]; con el dedo apoyado se oculta, así un arrastre no la hace aparecer.
     */
    @Volatile
    var controlBarVisible = false
        private set

    /** Cursor apoyado sobre un borde de la pantalla. */
    class EdgeContact(
        /** Borde horizontal: -1 izquierdo, 0 ninguno, 1 derecho. */
        val dirX: Int,
        /** Borde vertical: -1 superior, 0 ninguno, 1 inferior. */
        val dirY: Int,
        /** Momento (uptime en ms) en que el cursor llegó al borde. */
        val startMs: Long
    ) {
        /**
         * Fracción (0..1) de la espera cumplida antes de que la pantalla empiece a avanzar. Llega a 1
         * cuando arranca el avance, porque el arranque se programa con la misma espera.
         */
        fun fill(nowMs: Long): Float = ((nowMs - startMs) / EDGE_DWELL_MS.toFloat()).coerceIn(0f, 1f)
    }

    /** Cuánto tiene que quedarse el cursor en un borde para que la pantalla empiece a avanzar. */
    const val EDGE_DWELL_MS = 1000L

    /** Borde sobre el que está el cursor, o null si no hay. Lo lee el hilo de render en cada cuadro. */
    @Volatile
    var edgeContact: EdgeContact? = null
        private set

    private val startEdgeScroll = Runnable {
        val contact = edgeContact ?: return@Runnable
        Log.d(TAG, "Avance automático hacia (${contact.dirX}, ${contact.dirY})")
        accessibilityService?.startEdgeScroll(contact.dirX, contact.dirY)
    }

    private val serviceConnectedState = MutableStateFlow(false)

    /** Si el servicio de accesibilidad está conectado. Sin él no hay ventana estéreo ni control. */
    val serviceConnected: StateFlow<Boolean> = serviceConnectedState

    // Referencia débil/estática al servicio activo para despachar inyecciones de eventos globales
    var accessibilityService: JoystickAccessibilityService? = null
        set(value) {
            field = value
            serviceConnectedState.value = value != null
        }

    /**
     * Momento (uptime en ms) del último evento de mouse que mandó el VR BOX, o 0 si nunca mandó. En
     * modo mouse el sistema dibuja su propio puntero, que no está en estéreo; el renderer lo usa para
     * avisar que hay que pasar el control a modo joystick.
     */
    @Volatile
    var lastMouseModeEventMs = 0L
        private set

    /** Registra que llegó un evento del control en modo mouse. */
    fun reportMouseModeEvent() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMouseModeEventMs > MOUSE_MODE_NOTICE_MS) {
            Log.w(TAG, "El VR BOX está en modo mouse: el puntero del sistema no se ve en estéreo")
        }
        lastMouseModeEventMs = now
    }

    /** Cuánto tiempo sigue visible el aviso de modo mouse después del último evento. */
    const val MOUSE_MODE_NOTICE_MS = 4000L

    /**
     * Ajusta los límites del cursor a la resolución de la captura. La primera vez lo centra; en los
     * cambios siguientes (una rotación, por ejemplo) conserva su posición relativa en la pantalla.
     */
    @Synchronized
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (screenWidth == 0 || screenHeight == 0) {
            baseX = width / 2f
            baseY = height / 2f
        } else {
            baseX = baseX * width / screenWidth
            baseY = baseY * height / screenHeight
        }
        screenWidth = width
        screenHeight = height
        updateCursor()
        rebuildZoomWindow()
        Log.d(TAG, "Pantalla ${width}x${height}, cursor en ($cursorX, $cursorY)")
        refreshEdgeContact()
    }

    private val refreshEdgeContactOnMain = Runnable { refreshEdgeContact() }

    /**
     * Registra el desplazamiento que aporta la cabeza, en píxeles desde la posición del joystick. Se
     * llama desde el hilo de render, una vez por cuadro. [zoomChange] es el [zoomChangeCount] con cuya
     * escala se midió: si el zoom cambió después, el desplazamiento se descarta.
     */
    fun setHeadOffset(dx: Float, dy: Float, zoomChange: Int) {
        synchronized(this) {
            if (zoomChange != zoomChangeCount) return
            lastHeadOffsetX = headOffsetX
            lastHeadOffsetY = headOffsetY
            headOffsetX = dx
            headOffsetY = dy
            updateCursor()
        }
        // El avance por borde maneja gestos y vive en el hilo principal.
        handler.removeCallbacks(refreshEdgeContactOnMain)
        handler.post(refreshEdgeContactOnMain)
    }

    /**
     * Cuántas veces se pidió centrar el cursor. La fuente de la cabeza lo compara con el último valor
     * que atendió para tomar como frente la orientación de ese momento.
     */
    @Volatile
    var recenterCount = 0
        private set

    /**
     * Lleva el cursor y la ventana con zoom al centro de la pantalla, y descarta el desplazamiento del
     * joystick y de la cabeza.
     */
    fun recenter() {
        synchronized(this) {
            if (screenWidth == 0 || screenHeight == 0) return
            baseX = screenWidth / 2f
            baseY = screenHeight / 2f
            headOffsetX = 0f
            headOffsetY = 0f
            zoomWindow = zoomWindow.centered()
            updateCursor()
            recenterCount++
        }
        Log.d(TAG, "Cursor centrado en ($cursorX, $cursorY)")
        refreshEdgeContact()
    }

    /**
     * Mueve la posición del joystick. Contra un borde avanza solo hasta donde el cursor lo toca, así
     * que al volver no hay desplazamiento de más que deshacer.
     */
    @Synchronized
    private fun moveBase(stepX: Float, stepY: Float) {
        baseX = limitStep(baseX, stepX, headOffsetX, screenWidth.toFloat())
        baseY = limitStep(baseY, stepY, headOffsetY, screenHeight.toFloat())
        updateCursor()
    }

    /**
     * Aplica un paso del joystick sobre un eje. Si la cabeza ya lleva el cursor más allá del borde,
     * empujar hacia ese borde no mueve la base, pero tampoco la hace retroceder.
     */
    private fun limitStep(base: Float, step: Float, headOffset: Float, size: Float): Float {
        val target = base + step
        return if (step > 0f) {
            minOf(target, maxOf(base, size - headOffset))
        } else {
            maxOf(target, minOf(base, -headOffset))
        }
    }

    /**
     * Recalcula el cursor, corre la ventana con zoom para seguirlo y decide si la [ControlBar] queda a
     * la vista. La barra está anclada al borde de abajo de la pantalla: con zoom, bajar por la pantalla
     * la hace avanzar, y la barra aparece recién al llegar abajo de todo.
     */
    private fun updateCursor() {
        if (screenWidth == 0 || screenHeight == 0) return
        cursorX = (baseX + headOffsetX).coerceIn(0f, screenWidth.toFloat())
        cursorY = (baseY + headOffsetY).coerceIn(0f, screenHeight.toFloat())
        val window = zoomWindow.follow(cursorX, cursorY, zoomDeadZone)
        zoomWindow = window
        if (!controlBarVisible) barAnchorX = window.centerX
        val bar = ControlBar.frame(window, barAnchorX)
        controlBarVisible = !pressing &&
            ControlBar.nextVisible(
                controlBarVisible, window.toViewX(cursorX) - bar.shiftX, window.toViewY(cursorY),
                bar.width, bar.height
            )
        snapshot = Snapshot(cursorX, cursorY, window, barAnchorX)
    }

    /**
     * Cambia el zoom de la imagen del visor, entre 1 (la pantalla entera) y [MAX_ZOOM]. El punto bajo
     * el cursor queda en el mismo lugar.
     */
    fun setZoom(level: Float) {
        synchronized(this) {
            zoomLevel = level.coerceIn(1f, MAX_ZOOM)
            rebuildZoomWindow()
        }
        refreshEdgeContact()
    }

    /** Registra cómo entra la imagen en el ojo. Lo llama el render cuando cambia. */
    fun setZoomFit(fit: ZoomFit) {
        synchronized(this) {
            if (fit == zoomFit) return
            zoomFit = fit
            rebuildZoomWindow()
        }
    }

    /**
     * Cambia la fracción de la ventana con zoom en la que el cursor se mueve sin arrastrarla: 0 la
     * centra en el cursor y 1 la mueve solo cuando el cursor empuja su borde.
     */
    fun setZoomDeadZone(fraction: Float) {
        synchronized(this) {
            zoomDeadZone = fraction.coerceIn(0f, 1f)
            updateCursor()
        }
        Log.d(TAG, "Zoom: zona muerta $zoomDeadZone")
    }

    /**
     * Cambia el medio alto (1 = el ojo entero) hasta el que crece la imagen con zoom; 0 vuelve al que
     * calcula el render.
     */
    fun setZoomMaxHalfHeight(halfHeight: Float) {
        synchronized(this) {
            zoomMaxHalfHeight = halfHeight.coerceIn(0f, 1f)
            rebuildZoomWindow()
        }
    }

    /**
     * Rehace la ventana visible con el zoom, el encaje y el tamaño de pantalla actuales. Si cambia el
     * zoom efectivo, el cursor queda donde está, con todo su desplazamiento pasado al joystick, y la
     * cabeza toma un frente nuevo. Con la [ControlBar] a la vista, el cursor queda sobre el mismo punto
     * de la barra: ella no cambia de tamaño con el zoom, y sin esto el botón de zoom quedaría fuera
     * del cursor después de tocarlo.
     */
    private fun rebuildZoomWindow() {
        val fit = if (zoomMaxHalfHeight > 0f) zoomFit.copy(maxHalfHeight = zoomMaxHalfHeight) else zoomFit
        val window = zoomWindow.rebuilt(screenWidth, screenHeight, fit, zoomLevel, cursorX, cursorY)
        if (window.zoom != zoomWindow.zoom) {
            if (controlBarVisible) {
                val ratio = zoomWindow.zoom / window.zoom
                cursorX = barAnchorX + (cursorX - barAnchorX) * ratio
                cursorY = screenHeight - (screenHeight - cursorY) * ratio
            }
            baseX = cursorX
            baseY = cursorY
            headOffsetX = 0f
            headOffsetY = 0f
            zoomChangeCount++
        }
        zoomWindow = window
        updateCursor()
        Log.d(TAG, "Zoom: $zoomWindow")
    }

    /**
     * Registra la inclinación actual del stick (-1..1 por eje). El cursor se sigue moviendo mientras
     * no vuelva al centro, con una velocidad proporcional a la inclinación.
     */
    fun setStick(axisX: Float, axisY: Float) {
        stickX = if (Math.abs(axisX) > DEAD_ZONE) axisX else 0f
        stickY = if (Math.abs(axisY) > DEAD_ZONE) axisY else 0f
        updateMovement()
        refreshEdgeContact()
    }

    /** Registra que una flecha del D-pad se apretó o se soltó. El cursor se mueve mientras siga apretada. */
    fun setDpad(keyCode: Int, pressed: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight = pressed
            KeyEvent.KEYCODE_DPAD_UP -> dpadUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadDown = pressed
        }
        updateMovement()
        refreshEdgeContact()
    }

    /**
     * Mueve el cursor a la posición pedida, limitada a la pantalla. Lo usa el stick y sirve igual para
     * cualquier otra entrada: una posición pedida más allá de un borde deja el cursor sobre ese borde.
     */
    fun moveCursorTo(x: Float, y: Float) {
        synchronized(this) {
            if (screenWidth == 0 || screenHeight == 0) return
            baseX = x.coerceIn(0f, screenWidth.toFloat()) - headOffsetX
            baseY = y.coerceIn(0f, screenHeight.toFloat()) - headOffsetY
            updateCursor()
        }
        refreshEdgeContact()
    }

    /**
     * Vuelve a evaluar si el cursor está sobre un borde, según [EdgeZone]. Si se queda ahí
     * [EDGE_DWELL_MS], la pantalla avanza hacia ese lado hasta que el cursor deja el borde. Con el
     * gatillo apretado avanza inmediatamente al empujar y acelera/desacelera suavemente. Sin captura tampoco:
     * no hay pantalla estéreo que mirar.
     */
    fun refreshEdgeContact() {
        val enabled = ScreenCaptureService.isCapturing &&
            screenWidth != 0 && screenHeight != 0
        val current = edgeContact
        val edge = if (enabled) {
            EdgeZone.edgeAt(
                cursorX, cursorY, screenWidth, screenHeight,
                current?.let { EdgeZone.Edge(it.dirX, it.dirY) } ?: EdgeZone.NONE
            )
        } else {
            EdgeZone.NONE
        }

        if (edge.isNone) {
            if (current != null) {
                handler.removeCallbacks(startEdgeScroll)
                accessibilityService?.stopEdgeScroll()
                edgeContact = null
                Log.d(TAG, "Cursor fuera del borde")
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        if (pressing) {
            val pushing = isPushingEdge(edge)
            accessibilityService?.setEdgeScrollPushing(pushing)

            val existing = current
            if (existing == null || existing.dirX != edge.dirX || existing.dirY != edge.dirY) {
                edgeContact = EdgeContact(edge.dirX, edge.dirY, now - EDGE_DWELL_MS)
                Log.d(TAG, "Arrastre contra el borde (${edge.dirX}, ${edge.dirY}), pushing=$pushing")
                accessibilityService?.startEdgeScroll(edge.dirX, edge.dirY)
            } else {
                if (pushing) {
                    accessibilityService?.startEdgeScroll(edge.dirX, edge.dirY)
                }
            }
            return
        }

        if (current != null && current.dirX == edge.dirX && current.dirY == edge.dirY) return
        if (current == null && edge.isNone) return

        handler.removeCallbacks(startEdgeScroll)
        if (current != null) accessibilityService?.stopEdgeScroll()
        if (edge.isNone) {
            edgeContact = null
            Log.d(TAG, "Cursor fuera del borde")
            return
        }
        edgeContact = EdgeContact(edge.dirX, edge.dirY, now)
        Log.d(TAG, "Cursor en el borde (${edge.dirX}, ${edge.dirY})")
        handler.postDelayed(startEdgeScroll, EDGE_DWELL_MS)
    }

    private fun isPushingEdge(edge: EdgeZone.Edge): Boolean {
        val dx = directionX()
        val dy = directionY()
        val pushingX = edge.dirX != 0 && dx * edge.dirX > 0f
        val pushingY = edge.dirY != 0 && dy * edge.dirY > 0f
        val pushingHeadX = edge.dirX != 0 && (headOffsetX - lastHeadOffsetX) * edge.dirX > 0f
        val pushingHeadY = edge.dirY != 0 && (headOffsetY - lastHeadOffsetY) * edge.dirY > 0f
        return pushingX || pushingY || pushingHeadX || pushingHeadY
    }

    private fun directionX(): Float =
        (stickX + (if (dpadRight) 1f else 0f) - (if (dpadLeft) 1f else 0f)).coerceIn(-1f, 1f)

    private fun directionY(): Float =
        (stickY + (if (dpadDown) 1f else 0f) - (if (dpadUp) 1f else 0f)).coerceIn(-1f, 1f)

    /**
     * Arranca el tick cuando aparece una dirección. Un cambio de dirección con el cursor ya en
     * movimiento conserva la velocidad alcanzada; el tick se detiene solo cuando no queda ninguna.
     */
    private fun updateMovement() {
        if (moving || (directionX() == 0f && directionY() == 0f)) return
        moving = true
        moveStartMs = SystemClock.uptimeMillis()
        lastTickMs = moveStartMs
        handler.post(tick)
    }
    
    /**
     * Apoya un dedo simulado donde está el cursor. El dedo lo sigue hasta [releasePress], así que
     * sirve tanto para un tap como para un arrastre. Sobre la [ControlBar] visible no apoya nada:
     * ejecuta el botón que esté bajo el cursor, y en el resto de la píldora no hace nada.
     */
    fun pressDown() {
        val view = snapshot
        val bar = view.barFrame
        val barX = view.window.toViewX(view.cursorX) - bar.shiftX
        val barY = view.window.toViewY(view.cursorY)
        if (controlBarVisible && ScreenCaptureService.isCapturing &&
            ControlBar.contains(barX, barY, bar.width, bar.height)
        ) {
            when (ControlBar.buttonAt(barX, barY, bar.width, bar.height)) {
                ControlBar.Button.HOME -> showPanel()
                ControlBar.Button.PORTRAIT -> togglePortrait()
                ControlBar.Button.ZOOM -> setZoom(ZoomLevels.next(zoomLevel))
                null -> Log.d(TAG, "Gatillo en la barra, fuera de los botones: no se toca la app")
            }
            return
        }
        Log.d(TAG, "Apoyando dedo en: ($cursorX, $cursorY)")
        // updateCursor corre también en el hilo de render y decide la visibilidad de la barra con
        // pressing.
        synchronized(this) {
            pressing = true
            controlBarVisible = false
        }
        press = Press(SystemClock.uptimeMillis(), cursorX, cursorY)
        refreshEdgeContact()
        accessibilityService?.pressAt(cursorX, cursorY)
    }

    /** Levanta el dedo apoyado con [pressDown], en la posición actual del cursor. */
    fun releasePress() {
        synchronized(this) { pressing = false }
        press = null
        accessibilityService?.releasePress()
        refreshEdgeContact()
    }

    /** Cuánto puede durar un agarre por voz si nunca se dice "soltar". */
    private const val VOICE_GRAB_TIMEOUT_MS = 15_000L

    /** Dedo apoyado con "agarrar" que todavía no se soltó; null si no hay. */
    private var voiceGrab: Press? = null

    /** Levanta el dedo de "agarrar" al vencer el plazo, salvo que ya se haya levantado por otra vía. */
    private val releaseVoiceGrab = Runnable {
        val grab = voiceGrab ?: return@Runnable
        voiceGrab = null
        if (press === grab) {
            Log.d(TAG, "Voz: agarre liberado tras $VOICE_GRAB_TIMEOUT_MS ms sin \"soltar\"")
            releasePress()
        }
    }

    /** Toque corto donde está el cursor, igual que un gatillo apretado y soltado enseguida. */
    fun voiceClick() {
        if (press != null) {
            Log.d(TAG, "Voz: tocar ignorado, ya hay un dedo apoyado")
            return
        }
        pressDown()
        releasePress()
    }

    /**
     * Apoya el dedo donde está el cursor y lo deja apoyado hasta [voiceRelease] o hasta
     * [VOICE_GRAB_TIMEOUT_MS]. Mientras tanto el dedo sigue al cursor, así que mover la cabeza arrastra.
     */
    fun voiceGrab() {
        if (press != null) {
            Log.d(TAG, "Voz: agarrar ignorado, ya hay un dedo apoyado")
            return
        }
        pressDown()
        // Sobre la barra de controles pressDown ejecuta el botón y no apoya nada.
        val grab = press ?: return
        voiceGrab = grab
        handler.removeCallbacks(releaseVoiceGrab)
        handler.postDelayed(releaseVoiceGrab, VOICE_GRAB_TIMEOUT_MS)
    }

    /** Levanta el dedo apoyado, sea de "agarrar" o del gatillo. */
    fun voiceRelease() {
        handler.removeCallbacks(releaseVoiceGrab)
        voiceGrab = null
        if (press == null) return
        releasePress()
    }

    /**
     * Olvida el dedo apoyado que el sistema canceló antes de que se levantara, para que la retícula
     * vuelva a reposo y la voz no siga ignorando comandos hasta un "soltar".
     */
    fun onPressCancelled() {
        Log.d(TAG, "El sistema canceló el dedo apoyado")
        handler.removeCallbacks(releaseVoiceGrab)
        voiceGrab = null
        releasePress()
    }

    /** Vuelve al panel de apps del visor. Fuera de la captura no hace nada. */
    fun showPanel() {
        if (!ScreenCaptureService.isCapturing) return
        val service = accessibilityService ?: return
        Log.d(TAG, "Inicio: vuelve al panel")
        PanelActivity.show(service)
    }

    /**
     * Pasa la app a la vista a vertical, o la devuelve a horizontal. Fuera de la captura no hace nada.
     */
    fun togglePortrait() {
        if (!ScreenCaptureService.isCapturing) return
        val service = accessibilityService ?: return
        val portrait = !ScreenCaptureService.portrait.value
        Log.d(TAG, "Vertical: $portrait")
        service.startService(ScreenCaptureService.portraitIntent(service, portrait))
    }

    /**
     * Solicita la emulación de la pulsación física del botón de retroceso del sistema operativo.
     */
    fun performBack() {
        Log.d(TAG, "Simulando botón Atrás")
        accessibilityService?.performBackAction()
    }
}
