# CeluVR

Prueba de concepto que **captura la pantalla de otra aplicación Android en tiempo real y la
muestra en modo estéreo** dentro de un visor Google Cardboard.

La idea es poder usar cualquier app del celular desde el visor, sin que esa app sepa nada de VR.
Es un reemplazo del *VR Mode* que Android discontinuó, y a diferencia de antecedentes como
"VR VNC" no usa red: la captura y el renderizado pasan enteros dentro del mismo dispositivo.

Desarrollado como Práctica Profesional Supervisada.

---

## Estado actual

| Fase | Qué hace | Estado |
|---|---|---|
| 1 | Captura de pantalla en tiempo real | Funcional |
| 1.5 | Ventana flotante que sobrevive a que la app pase a segundo plano | Funcional |
| 2 | Renderizado estéreo con Cardboard SDK: distorsión de lente + head tracking | Funcional |
| 3 | Interacción: accionar sobre la app capturada con el control | Funcional |
| 4 | Navegación entre apps y modo fluido (fps completos, sin computadora) | Funcional |

---

## Requisitos

| | |
|---|---|
| **Android Studio** | **Ladybug (2024.2.1) o superior.** Con versiones anteriores el proyecto no abre. |
| **JDK** | 17 o superior. |
| **Celular** | Android 8.0 o superior, con giróscopo. |
| **Visor** | Cualquier Cardboard o VR Box. No hace falta que tenga código QR. |
| **NDK / CMake** | **No hacen falta.** |

El Cardboard SDK ya viene compilado y versionado en `app/libs/`, así que no hay que instalar
nada del toolchain nativo para compilar la app.

---

## Compilar y correr

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O directamente **Run** desde Android Studio.

### Dos cosas con las que vas a tropezar la primera vez

**Android Studio te dice que el plugin es incompatible.** El mensaje es *"The project is using an
incompatible version (AGP 8.7.3) of the Android Gradle plugin"*. Significa que tu Android Studio
es viejo — no que haya algo mal en el código. Actualizalo a Ladybug o superior.

El síntoma despista: por línea de comandos compila perfecto mientras el IDE marca imports en rojo
y se niega a sincronizar. **Si te pasa eso, no busques el problema en el código.**

**Android Studio te pide elegir una JVM.** Las versiones nuevas traen un Java 25 adentro, y
Gradle 8.9 soporta hasta el 22. Elegí **JVM 21**.

### Y una que conviene no hacer

**No aceptes el banner que ofrece subir el plugin de Android a la versión 9.** Tiene cambios que
rompen. Las versiones fijadas hoy —Gradle 8.9, plugin 8.7.3— son las que están probadas.

---

## Cómo usarlo

1. Poné el celular **en horizontal antes de abrir la app**. Todo el flujo lo asume.
2. Abrí CeluVR y tocá **Iniciar**.
3. La primera vez te va a mandar a Ajustes a activar el servicio de accesibilidad
   **"Control de CeluVR"**: es el que dibuja la vista estéreo y traduce el control.
4. Elegí **"Una sola app"** y seleccioná la que querés ver en el visor (con el modo fluido activo este
   paso no aparece: el panel se abre directo en el visor).
5. Poné el celular en el visor, centrado con la **línea vertical** que se dibuja abajo.

Para detener la captura, usá la notificación o el botón de la pantalla principal.

### Videos y juegos fluidos (modo fluido, opcional)

Por defecto, desde el panel la mayoría de las apps de video y de juegos se ven a unos 3 cuadros por
segundo y sin video. El **modo fluido** las muestra a máxima velocidad, abriéndolas en una pantalla
virtual propia. Es opcional: sin él, el visor funciona igual y todo lo demás se ve bien.

**Cómo funciona:** crear esa pantalla necesita los permisos de la depuración de Android (adb). CeluVR
los consigue **sola**, conectándose al adbd del propio celular por la depuración inalámbrica — no hace
falta una computadora ni instalar otra app. (Si tenés [Shizuku](https://shizuku.rikka.app/) corriendo,
también lo usa, pero ya no es necesario.) El detalle está en la bitácora, en "Servidor propio y
autoconexión".

**Una sola vez por celular:**

1. Activar las opciones de desarrollador: Ajustes > Acerca del teléfono > Información de software >
   tocar 7 veces "Número de compilación".
2. Conectar el celular a una red Wi-Fi.
3. Ir a Ajustes > Opciones de desarrollador > **Depuración inalámbrica** y activarla.
4. Abrir CeluVR y tocar **Iniciar**. Cuando aparezca la ventanita de emparejamiento, ir a Depuración
   inalámbrica > **"Vincular dispositivo con código de vinculación"** y, con ese diálogo abierto,
   escribir el código de 6 dígitos en la ventanita. El emparejamiento queda guardado.

**Después de cada reinicio del celular:** solo volver a activar la **Depuración inalámbrica** (el
emparejamiento sigue valiendo; no hay que repetir el código). El Wi-Fi solo hace falta para que el adbd
arranque: no necesita internet (sirve el hotspot de otro celular) y, una vez conectado, se puede cortar.

Con el modo fluido activo, el chip de la pantalla principal dice **"Modo fluido"** y, al tocar Iniciar,
el panel abre las apps directo en el visor a fps completos, sin pasar por el selector. El video con DRM
(Netflix y similares) sale negro igual. Los pasos están resumidos también en la app, en **Acerca de >
Modo fluido**.

### Recomendaciones para la prueba física

- Brillo al máximo y brillo automático desactivado: dentro del visor se ve mucho más oscuro.
- Rotación automática desactivada.
- Apagado de pantalla en 5 o 10 minutos: la ventana flotante no mantiene la pantalla encendida.

### Ver el head tracking

```bash
adb logcat -s CardboardCapture
```

Al mover la cabeza tienen que cambiar los valores de `pitch`, `yaw` y `roll`. En esta fase la
imagen **no se mueve con la cabeza** — es lo planeado; el log es la forma de confirmar que el
sensor lee bien.

---

## Qué no funciona todavía

- **Las apps con DRM se ven en negro** (Netflix y similares). Es una protección del sistema
  operativo, no un error nuestro.
- **Las apps que se fuerzan en vertical rompen la vista estéreo.** La app que está adelante decide
  la orientación de toda la pantalla y nuestra ventana no tiene voto.
- **La corrección de las lentes es aproximada.** Se usan los valores del Cardboard original de
  Google, porque el visor de prueba no trae código QR con su perfil óptico. Se nota como una leve
  deformación en los bordes.
- **No se pueden sacar capturas de pantalla de la app**, por el mismo mecanismo que evita que la
  ventana se capture a sí misma.

Todo esto está explicado en detalle en la bitácora.

---

## Estructura

```
app/src/main/java/com/pps/cardboardpoc/
  MainActivity.kt               Pantalla de inicio: permisos y arranque de la captura
  ScreenCaptureService.kt       Captura la pantalla y entrega los frames
  StereoOverlay.kt              La ventana flotante y su ciclo de vida
  CardboardOverlayRenderer.kt   Dibuja el frame en cada ojo con OpenGL

app/libs/sdk-debug.aar          Cardboard SDK compilado (ver nota abajo)
docs/BITACORA_TECNICA.md        Bitácora técnica del proyecto
```

---

## Documentación

La **[bitácora técnica](docs/BITACORA_TECNICA.md)** tiene el detalle de cada fase: qué problemas
aparecieron, qué se decidió y por qué. Incluye una sección sobre cómo diagnosticamos, que vale la
pena leer antes de pelearse con un bug: en este proyecto el debugger paso a paso sirve poco.

---

## Sobre el Cardboard SDK versionado

En `app/libs/sdk-debug.aar` hay un binario de terceros: el
[Cardboard SDK de Google](https://github.com/googlevr/cardboard), licencia Apache 2.0, compilado
del tag `v1.35.0`.

Está versionado a propósito. Google no lo publica en ningún repositorio de dependencias, así que
la alternativa sería que cada integrante instale el NDK y lo compile por su cuenta.

**Si alguna vez hay que actualizarlo, no alcanza con recompilar:** el build de Google tiene una
falla que deja el `.aar` incompleto de una forma que no se nota hasta que la app se cierra en el
celular. El parche necesario está explicado en la Fase 2 de la bitácora.

## Íconos

El ícono de la app (`ic_celuvr_logo`, `ic_launcher_foreground` y las variantes monocromas) es un
visor de VR propio del proyecto.

Los íconos de la barra del visor (`app/src/main/res/drawable/ic_*_rounded.xml`) son
[Material Symbols](https://github.com/google/material-design-icons) estilo Rounded, de Google,
licencia Apache 2.0.
