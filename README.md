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
| — | Comandos de voz offline: tocar, agarrar, soltar, centrar y volver | Funcional |

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

**La primera compilación necesita internet:** baja el modelo de reconocimiento de voz (unos 40 MB) y lo
mete en el APK. Queda guardado en `app/build/`, así que solo se vuelve a bajar después de un `clean`.

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

Desde el panel del visor se abren las apps; arriba, en **RECIENTES**, quedan las que abriste en la sesión.
Para detener la captura, usá la **X** del visor o la notificación: CeluVR vuelve a su pantalla principal.

### Ajustes

El engranaje de la pantalla principal abre **Ajustes**: el giroscopio (activarlo, sensibilidad y
aceleración del puntero), los comandos de voz y el modo fluido. **Restablecer** vuelve el giroscopio y la
voz a sus valores por defecto. Los cambios del giroscopio y de la voz se aplican al iniciar la siguiente
sesión.

### Videos y juegos fluidos (modo fluido, opcional)

Por defecto, desde el panel la mayoría de las apps de video y de juegos se ven a unos 3 cuadros por
segundo y sin video. El **modo fluido** las muestra a máxima velocidad, abriéndolas en una pantalla
virtual propia. Es opcional: sin él, el visor funciona igual y todo lo demás se ve bien.

**Cómo funciona:** crear esa pantalla necesita los permisos de la depuración de Android (adb). CeluVR
los consigue **sola**, conectándose al adbd del propio celular por la depuración inalámbrica — no hace
falta una computadora ni instalar otra app. (Si tenés [Shizuku](https://shizuku.rikka.app/) corriendo,
también lo usa, pero ya no es necesario.) El detalle está en la bitácora, en "Servidor propio y
autoconexión".

**Una sola vez por celular**, con el celular conectado a una red Wi-Fi y el servicio de accesibilidad de
CeluVR activo: en CeluVR, ir a **Ajustes › Modo fluido (opcional)**. La tarjeta muestra los tres pasos, con
una tilde en los que ya están hechos, y un botón que lleva al lugar del paso que falta:

1. **"Ir a Acerca del teléfono"**: activar las opciones de desarrollador tocando 7 veces "Número de
   compilación" (en Samsung, dentro de "Información de software").
2. **"Ir a Depuración inalámbrica"**: abre Opciones de desarrollador con "Depuración inalámbrica"
   resaltada. Activarla y entrar.
3. Tocar **"Vincular dispositivo con un código de vinculación"** (CeluVR la marca con un marco violeta).
   Con el diálogo abierto se despliega solo el panel de CeluVR con un teclado: escribir ahí el código de 6
   dígitos y tocar **Vincular**. El panel se puede arrastrar si tapa el código.

Al vincular, CeluVR vuelve a la pantalla principal con el modo fluido encendido. Tocar **Iniciar** sin
configurar nunca pide emparejar: usa la captura normal.

**Encender, apagar y olvidar**, desde la misma tarjeta:

- El switch **"Modo fluido"** lo apaga sin perder la vinculación y sin apagar la depuración inalámbrica
  (otra app puede estar usándola). Al encenderlo de nuevo no pide código.
- **"Olvidar emparejamiento"** quita a CeluVR de "Dispositivos vinculados" y apaga la depuración
  inalámbrica. Para volver hay que vincular de nuevo.

CeluVR aparece una sola vez en "Dispositivos vinculados", como **"CeluVR"**: si quedaron entradas viejas
suyas, las quita sola al conectar.

**Después de cada reinicio del celular:** no hay que hacer nada a mano. CeluVR vuelve a activar la
**Depuración inalámbrica** sola al tocar Iniciar (el emparejamiento sigue valiendo; no hay que repetir el
código). El Wi-Fi solo hace falta para que el adbd arranque: no necesita internet (sirve el hotspot de otro
celular) y, una vez conectado, se puede cortar.

Con el modo fluido activo, el chip de la pantalla principal dice **"Modo fluido"** (o **"Fluido sin
Wi-Fi"** si falta la red) y, al tocar Iniciar, el panel abre las apps directo en el visor a fps completos,
sin pasar por el selector. Mientras arranca se ve el ícono de CeluVR girando; si algo falla, un aviso dice
por qué y ofrece seguir en modo normal.

En modo fluido, además:

- **Netflix y otras apps con DRM** muestran el video: el visor usa una copia de la pantalla que compone el
  sistema. Esa vista no tiene corrección de lente: a través del visor, la imagen se ve algo deformada hacia
  los bordes.
- El botón **Vertical** de la barra del visor pasa la app a vertical y la devuelve a horizontal. Las apps
  que solo funcionan en vertical igual llenan la pantalla horizontal.

### Comandos de voz (opcional)

Con el visor puesto, el cursor también se maneja con la voz, sin internet:

| Decí | Hace |
|---|---|
| **"tocar"** | Un toque donde está el cursor |
| **"agarrar"** | Apoya el dedo y lo deja apoyado: mover la cabeza arrastra |
| **"soltar"** | Levanta el dedo de "agarrar" (si no, se suelta solo a los 15 s) |
| **"centrar"** | Lleva el cursor al centro |
| **"volver"** | Atrás |

Se activa en **Ajustes › Comandos de voz › "Control por voz"**, que pide el permiso de micrófono. Cada
comando tiene que ir **solo y con una pausa corta** antes del siguiente: una frase que contiene la palabra
("tocá ahí") no hace nada, y así un video con sonido no dispara comandos. El cambio se aplica al iniciar la
siguiente sesión.

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

- **En modo normal, las apps con DRM se ven en negro** (Netflix y similares). Es una protección del
  sistema operativo, no un error nuestro. En modo fluido se ven, pero sin corrección de lente.
- **El botón Vertical solo existe en modo fluido.** En modo normal la orientación la fija la pantalla del
  celular.
- **El resaltado del setup del modo fluido usa los textos de Ajustes de AOSP y One UI.** En celulares de
  otras marcas puede no encontrar la opción a tocar; el teclado del código funciona igual.
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
  VoiceCommands.kt              Escucha el micrófono y traduce los comandos de voz
  PanelActivity.kt              El panel de apps que se ve dentro del visor
  adb/                          Modo fluido: emparejamiento, conexión al adbd local y setup guiado
  vdisplay/                     El servidor propio que crea la pantalla virtual

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

## Sobre el modelo de voz

Los comandos de voz usan [Vosk](https://alphacephei.com/vosk/) con el modelo
**`vosk-model-small-es-0.42`** de Alpha Cephei, licencia Apache 2.0, tal como se publica: no tiene
entrenamiento propio. No está versionado: la tarea `downloadVoskModel` de Gradle lo baja al compilar.

## Íconos

El ícono de la app (`ic_celuvr_logo`, `ic_launcher_foreground` y las variantes monocromas) es un
visor de VR propio del proyecto.

Los íconos de la barra del visor (`app/src/main/res/drawable/ic_*_rounded.xml`) son
[Material Symbols](https://github.com/google/material-design-icons) estilo Rounded, de Google,
licencia Apache 2.0.
