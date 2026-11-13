# CeluVR

CeluVR **captura la pantalla de cualquier app de Android en tiempo real y la muestra en estéreo** dentro de
un visor Google Cardboard.

La idea es poder usar cualquier app del celular desde el visor, sin que esa app sepa nada de VR.
Es un reemplazo del *VR Mode* que Android discontinuó, y a diferencia de antecedentes como
"VR VNC" no usa red: la captura y el renderizado pasan enteros dentro del mismo dispositivo.

Versión 1.3.0. Hecha como Práctica Profesional Supervisada por el Grupo 5, en 2026. Los cambios de cada
versión están en el [CHANGELOG](CHANGELOG.md).

---

## Estado actual

| Fase | Qué hace | Estado |
|---|---|---|
| 1 | Captura de pantalla en tiempo real | Funcional |
| 1.5 | Ventana flotante que sobrevive a que la app pase a segundo plano | Funcional |
| 2 | Renderizado estéreo con Cardboard SDK: distorsión de lente + head tracking | Funcional |
| 3 | Interacción: accionar sobre la app capturada con el control | Funcional |
| 4 | Navegación entre apps y modo fluido (fps completos, sin computadora) | Funcional |
| — | Comandos de voz offline: tocar, agarrar, soltar, centrar, volver, acercar y alejar | Funcional |
| — | Zoom de la pantalla (1×, 1,5× y 2×) desde la barra o por voz | Funcional |

---

## Qué hace falta

| Para | Qué | Detalle |
|---|---|---|
| Instalarla | Android 8 o posterior | Con eso se instala, pero no alcanza para el visor. |
| Usar el visor | **Android 14 o posterior** | Antes no arranca. La pantalla principal lo avisa con el chip "Android 14+". |
| | Giroscopio | Mueve el cursor con la cabeza. |
| | Visor Cardboard o VR BOX | No hace falta que tenga código QR: se usa el perfil del Cardboard original. |
| | Control VR BOX por Bluetooth, en modo joystick | Mantené **@** y apretá **C**. |
| | Permiso de accesibilidad "Control de CeluVR" | Si el APK se instaló desde un archivo, antes hay que permitir la configuración restringida (paso 7 de abajo). |
| | Unos 170 MB libres | El APK pesa unos 110 MB. El modelo de voz (60 MB) se copia al almacenamiento de la app la primera vez que se usa la voz. |
| | RAM: sin mínimo medido | Una aproximación en "Limitaciones". |
| Comandos de voz (opcional) | Permiso de micrófono | Andan sin internet. |
| Modo fluido (opcional) | Opciones de desarrollador y depuración inalámbrica | Se configura una sola vez, desde Ajustes › Modo fluido. |
| | Wi-Fi para vincular y para arrancar después de cada reinicio | No necesita internet: sirve el hotspot de otro celular. Una vez conectado, se puede cortar. |
| Video con DRM | Modo fluido | En modo normal se ve en negro. |

---

## Instalar el APK

Para usar CeluVR sin compilarla. Los mismos pasos, con una captura de cada pantalla, están en la
**[guía de instalación](docs/guia-instalacion.html)**: se abre con cualquier navegador, sin internet. GitHub
muestra su código; para verla, bajala con **Download raw file**.

1. Descomprimí el zip **en una computadora**, con la contraseña `pps` (en Windows, clic derecho ›
   **Extraer todo**). Adentro está `CeluVR.apk`: pasalo al celular. La app Archivos de Android no extrae un
   zip con contraseña ni abre el APK de adentro.
2. Abrí el APK en el celular. Android pide permiso para instalar apps desde la app con la que lo abriste
   (Mis archivos, Chrome, etc.; también puede figurar como "Administrador de descargas"). Tocá
   **Permitir**, o en un Pixel con Android 16, **Configuración**, y prendé **Confiar en esta fuente**.
3. En un Samsung con **Bloqueo automático** activado no deja instalar: apagalo un momento en Ajustes ›
   Seguridad y privacidad.
4. Si aparece un aviso de Play Protect porque la app no viene de la tienda, elegí **Instalar de todas
   formas**.
5. Vinculá el control VR BOX por Bluetooth y pasalo a modo joystick: mantené **@** y apretá **C**. Hacelo
   antes de seguir: al volver de Accesibilidad, CeluVR pide enseguida compartir la pantalla.
6. Abrí CeluVR y tocá **Iniciar**: te lleva a Ajustes › Accesibilidad. Entrá a **"Control de CeluVR"** (en
   Samsung está dentro de "Aplicaciones instaladas"), activalo y aceptá **Permitir**.
7. Si el interruptor no se prende y aparece el aviso **"A la app se le negó el acceso"**, es la
   configuración restringida: el renglón queda gris, con "Función controlada por configuración
   restringida". Cerrá el aviso, andá a Ajustes › Aplicaciones › CeluVR, tocá **⋮** y elegí **"Permitir
   configuración restringida"**. Después volvé a activar el control.

De vuelta en CeluVR, el chip tiene que decir **"Joystick OK"** y se abre el diálogo para compartir la
pantalla: seguí desde el paso 3 de [Cómo usarlo](#cómo-usarlo). Si el diálogo no aparece, tocá **Iniciar**.

Los pasos 2, 3, 4 y 7 dependen de la marca y de la versión de Android, y el texto de cada aviso puede
cambiar un poco. Instalando por adb no aparecen. Desde un archivo se probó en el emulador Pixel con Android
16; ahí, sin bloqueo de pantalla, "Permitir configuración restringida" no pidió PIN.

---

## Para compilar

| | |
|---|---|
| **Android Studio** | **Ladybug (2024.2.1) o superior.** Con versiones anteriores el proyecto no abre. |
| **JDK** | 17 o superior. Para correr Gradle, hasta el 24 (ver abajo). |
| **NDK / CMake** | **No hacen falta.** |
| **Celular** | Uno de verdad, con lo de [Qué hace falta](#qué-hace-falta). |
| **Emulador** | Uno x86_64 no sirve tal cual: el Cardboard SDK solo trae arm64-v8a y armeabi-v7a, y el visor se cierra con `UnsatisfiedLinkError`. Usá una imagen con traducción ARM e instalá con `adb install --abi arm64-v8a` (anduvo en un Pixel 3a con Android 16 y Google APIs). |

El Cardboard SDK ya viene compilado y versionado en `app/libs/`, así que no hay que instalar
nada del toolchain nativo para compilar la app.

### Compilar y correr

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O directamente **Run** desde Android Studio. Los tests unitarios corren con `./gradlew test`.

**La primera compilación necesita internet:** baja las dependencias y el modelo de reconocimiento de voz
(unos 40 MB), que mete en el APK. El modelo queda guardado en `app/build/`, así que solo se vuelve a bajar
después de un `clean`. En una computadora sin nada de Gradle, la primera vez baja unos 440 MB (Gradle,
las dependencias y el modelo), ocupa 1,5 GB en `~/.gradle` y tarda unos 4 minutos con los tests.

### Dos cosas con las que vas a tropezar la primera vez

**Android Studio te dice que el plugin es incompatible.** El mensaje es *"The project is using an
incompatible version (AGP 8.7.3) of the Android Gradle plugin"*. Significa que tu Android Studio
es viejo — no que haya algo mal en el código. Actualizalo a Ladybug o superior.

El síntoma despista: por línea de comandos compila perfecto mientras el IDE marca imports en rojo
y se niega a sincronizar. **Si te pasa eso, no busques el problema en el código.**

**Android Studio te pide elegir una JVM.** Las versiones nuevas traen un Java 25 adentro, y
Gradle 8.14.5 corre hasta el 24. Elegí **JVM 21**.

### Y una que conviene no hacer

**No aceptes el banner que ofrece subir el plugin de Android a la versión 9.** Tiene cambios que
rompen. Las versiones fijadas hoy —Gradle 8.14.5, plugin 8.7.3— son las que están probadas.

### Si una descarga deja de estar

Dos cosas se bajan de sitios que no son repositorios garantizados. Si alguno deja de publicarlas, se puede
compilar con copias guardadas: conviene guardarlas mientras estén. El zip del modelo se baja de la
[página de modelos de Vosk](https://alphacephei.com/vosk/models). Los `.aar` de JitPack quedan en la caché
de Gradle de cualquiera que haya compilado el proyecto, en `~/.gradle/caches/modules-2/files-2.1/`, bajo
`com.github.MuntashirAkon`.

**El modelo de voz** (alphacephei.com). Pasale el zip a Gradle:

```bash
./gradlew :app:assembleDebug -PvoskModelZip=<ruta>/vosk-model-small-es-0.42.zip
```

Usa ese zip sin conexión y no lo borra. Para no repetirlo en cada comando, poné
`voskModelZip=<ruta>` en el `gradle.properties` de tu usuario (`~/.gradle/`).

**libadb-android y sus dependencias** (JitPack):

1. Copiá a `app/libs/` los tres `.aar`: `libadb-android-3.1.1`, `sun-security-android-1.1` y
   `spake2-android-2.2.1`. El build ya incluye cualquier `.aar` que haya ahí.
2. En `app/build.gradle.kts`, cambiá las dos líneas de `libs.libadb.android` y `libs.sun.security.android`
   por esta:

   ```kotlin
   implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
   ```

   Es la dependencia de libadb que está en Maven Central. Un `.aar` local no la trae solo.
3. En `settings.gradle.kts`, borrá la línea de `jitpack.io`.

Con los dos cambios, el proyecto compila con `--offline` sin JitPack ni alphacephei.com.

### El APK que se entrega

Es el de **debug**: `app/build/outputs/apk/debug/app-debug.apk`, renombrado `CeluVR.apk`. El de release
sale sin firmar, porque el proyecto no tiene `signingConfig`, y Android no lo instala. Además, el de debug
es depurable, y eso habilita los atajos de prueba por adb (ver la bitácora, "Cómo diagnosticamos").

**Está firmado con la clave de debug de la computadora que lo compiló** (`~/.android/debug.keystore`). Un
APK compilado en otra computadora tiene otra firma, y Android no lo instala encima del anterior. Hay que
desinstalar primero, y con eso se pierden el servicio de accesibilidad activado y la vinculación del modo
fluido. No se entregan claves.

**El zip con contraseña** es solo para mandarlo por correo, que suele rechazar los `.apk`. No protege
nada: la contraseña está en este README. Tiene que ir con cifrado **ZipCrypto**, no AES, porque el
"Extraer todo" de Windows no abre AES. Por ejemplo:

```bash
zip -e CeluVR.zip CeluVR.apk
```

Con 7-Zip, elegí el formato zip y el método de cifrado ZipCrypto.

---

## Cómo usarlo

1. Abrí CeluVR y tocá **Iniciar**. La pantalla principal es vertical; el visor pasa solo a horizontal.
2. La primera vez te va a mandar a Ajustes a activar el servicio de accesibilidad
   **"Control de CeluVR"**: es el que dibuja la vista estéreo y traduce el control (ver
   [Instalar el APK](#instalar-el-apk)).
3. Android pregunta qué compartir. Elegí **"Una sola app"** (en Android 16, **"Compartir una app"**) y
   seleccioná **CeluVR**. Con "Pantalla completa" ("Compartir pantalla completa") se ve en negro. Con el
   modo fluido activo este paso no aparece: el panel se abre directo en el visor.
4. Poné el celular en el visor, centrado con la **línea vertical** que se dibuja abajo.

Desde el panel del visor se abren las apps; arriba, en **RECIENTES**, quedan las que abriste en la sesión.
Para detener la captura, usá la **X** del visor o la notificación: CeluVR vuelve a su pantalla principal.

### La barra y los bordes

Al llevar el cursor al borde de abajo aparece la **barra del visor**, con estos botones:

- **Inicio** vuelve al panel. No aparece cuando el panel ya está al frente.
- **Ir arriba** lleva al principio del contenido: el tope del panel, o el principio de la lista principal
  de la app que estás usando.
- **Vertical** pasa la app a vertical y la devuelve a horizontal (solo en modo fluido).
- **La lupa** cambia el zoom (ver abajo).

Dejar el cursor quieto contra un borde hace avanzar la pantalla hacia ese lado. Con el gatillo apretado
el arrastre sigue apenas empujás contra el borde y se va acelerando; al dejar de empujar frena de a poco.

### Ajustes

El engranaje de la pantalla principal abre **Ajustes**: el giroscopio (activarlo, sensibilidad y
aceleración del puntero), el zoom (con qué zoom arranca cada sesión y cuánto se suaviza el movimiento de la
cabeza), los comandos de voz y el modo fluido. **Restablecer** vuelve el giroscopio, el zoom y la voz a
sus valores por defecto. Los cambios del giroscopio, del zoom y de la voz se aplican al iniciar la
siguiente sesión.

### Zoom

El botón con la lupa de la barra del visor agranda la pantalla: cada toque pasa de **1×** a **1,5×**, a
**2×** y vuelve a 1×, y el botón muestra el nivel. También se puede decir **"acercar"** y **"alejar"**.

Con zoom, la imagen ocupa más del ojo y lo que no entra queda afuera, como en una pantalla de cine: girar
la cabeza recorre la pantalla, con la retícula siempre en el centro. Al llegar al borde real de la pantalla
la imagen se frena, y recién ahí aparecen las flechas del avance por borde o, abajo de todo, la barra. La
barra se queda quieta mientras está a la vista, así se pueden mirar sus botones.

Cada sesión arranca con el zoom elegido en **Ajustes › Zoom** (1× si no se cambió).

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

- **Netflix, Disney+, Max, Flow y otras apps con DRM** muestran el video: el visor usa una copia de la
  pantalla que compone el sistema. Esa vista no tiene corrección de lente: a través del visor, la imagen se
  ve algo deformada hacia los bordes. Los toques se hacen sobre esa copia, y sin zoom son algo menos
  precisos.
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
| **"acercar"** | Sube un nivel de zoom, hasta 2× |
| **"alejar"** | Baja un nivel de zoom, hasta 1× |

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

Al mover la cabeza tienen que cambiar los valores de `pitch`, `yaw` y `roll`. Sin zoom, la
imagen **no se mueve con la cabeza** — es lo planeado; el log es la forma de confirmar que el
sensor lee bien.

---

## Limitaciones

- **Probada en un solo celular:** un Samsung S25 Ultra con Android 16. La instalación, además, en el
  emulador Pixel 3a con Android 16, instalando con `--abi arm64-v8a`. Android 14 y 15 y otras marcas,
  sin probar.
- **La RAM no tiene un mínimo medido.** CeluVR y el servidor del modo fluido usaron unos 330 MB (PSS) en
  el emulador, con YouTube en el visor. Es una sola medición y una aproximación: la traducción ARM puede
  inflarla, el emulador no cuenta la memoria de la GPU, y en un celular puede dar distinto.
- **El modo fluido, el video con DRM y la multiventana usan interfaces ocultas de Android**, que pueden
  cambiar o desaparecer con una actualización del sistema.
- **En modo normal, las apps con DRM se ven en negro** (Netflix y similares). Es una protección del
  sistema operativo, no un error nuestro. En modo fluido se ven, pero sin corrección de lente.
- **El botón Vertical solo existe en modo fluido.** En modo normal la orientación la fija la pantalla del
  celular.
- **El resaltado del setup del modo fluido usa los textos de Ajustes de AOSP y One UI.** En celulares de
  otras marcas puede no encontrar la opción a tocar; el teclado del código funciona igual.
- **La corrección de las lentes es aproximada.** Se usan los valores del Cardboard original de
  Google, porque el visor de prueba no trae código QR con su perfil óptico. Se nota como una leve
  deformación en los bordes.
- **Un emulador x86_64 no sirve tal cual:** ver [Para compilar](#para-compilar).
- **No se pueden sacar capturas de pantalla de la app**, por el mismo mecanismo que evita que la
  ventana se capture a sí misma. La grabadora de pantalla de Samsung y Smart View tampoco arrancan con el
  visor andando.

Todo esto está explicado en detalle en la bitácora. Lo que falta hacer está en sus
[Próximos pasos](docs/BITACORA_TECNICA.md#proximos-pasos).

---

## Estructura

Los 43 archivos de `app/src/main/java/com/pps/cardboardpoc/`, por subsistema. Para empezar a leer:
`MainActivity.kt` (el arranque), `ScreenCaptureService.kt` (el pipeline de captura) y
`JoystickAccessibilityService.kt` (el control). La bitácora cuenta por qué cada uno quedó así.

```
Arranque y pantallas
  MainActivity.kt                 Pantalla principal, Ajustes, permisos y arranque del visor
  PanelActivity.kt                El panel de apps que se ve dentro del visor
  SessionApps.kt                  Las apps abiertas en la sesión (RECIENTES)
  ui/theme/                       Colores, tema y tipografía de Compose (3 archivos)

Captura y render
  ScreenCaptureService.kt         Dueño del pipeline: MediaProjection o pantalla virtual, cuadros al visor
  WindowScreenshotSource.kt       Captura por ventanas, el otro camino de la captura híbrida
  StereoOverlay.kt                La ventana flotante del visor
  CardboardOverlayRenderer.kt     Dibuja el cuadro en cada ojo con OpenGL y el Cardboard SDK
  GlQuad.kt                       Lo que comparten las superficies GL
  OverlayUi.kt                    Lo que va encima de la imagen: retícula, barra, brillo del borde
  ReticleState.kt, ReticleStyle.kt
  ZoomWindow.kt, ZoomSettings.kt  Qué parte de la pantalla se ve con zoom, y sus ajustes

Control y puntero
  JoystickAccessibilityService.kt El servicio de accesibilidad: control, gestos y atajos de prueba por adb
  JoystickManager.kt              El cursor, compartido entre la captura y el servicio
  HeadPointer.kt                  La cabeza mueve el cursor
  VirtualFinger.kt                El dedo simulado: apoyar, seguir y levantar
  VoiceCommands.kt                Comandos de voz con Vosk

Barra y bordes
  ControlBar.kt                   La barra flotante: Inicio, Ir arriba, Vertical y zoom
  EdgeZone.kt                     En qué borde está el cursor
  EdgeScroller.kt                 El avance por borde y el arrastre que acelera

Modo fluido: pantalla virtual y servidor propio
  VirtualDisplayBackend.kt        La pantalla virtual confiable, la preste quien la preste
  ServerDisplay.kt                La que crea el servidor propio
  ShizukuDisplay.kt               La que se crea con Shizuku, como alternativa
  FrontAppDetector.kt             Qué app está al frente en la pantalla virtual
  FrontAppOrientation.kt          Vertical u horizontal según la app al frente
  WindowFit.kt                    Cómo encaja la ventana de cada app
  vdisplay/VDisplayServer.kt      El proceso servidor, lanzado con app_process como shell
  vdisplay/VDisplayConnection.kt, VDisplayReceiverProvider.kt
                                  Cómo le llega a la app el binder del servidor
  vdisplay/MultiWindowTasks.kt    Las apps abiertas en multiventana
  adb/AdbConnectionManager.kt     La identidad adb de CeluVR
  adb/AdbServerLauncher.kt        Se conecta al adbd del celular y lanza el servidor
  adb/WirelessDebugging.kt        Enciende sola la depuración inalámbrica
  adb/FluidSetup.kt, FluidSetupStep.kt
                                  El setup guiado desde Ajustes
  adb/PairingOverlay.kt           El flotante del código de vinculación
  adb/PairedDevicesCleaner.kt     Limpia entradas viejas de "Dispositivos vinculados"

Video con DRM
  StreamingApps.kt                Qué apps protegen el video
  MirrorUiRenderer.kt             La UI del visor encima de los clones de la pantalla

app/src/main/aidl/                IVDisplayServer.aidl, la interfaz entre la app y el servidor
app/src/test/                     8 clases de tests unitarios
app/libs/sdk-debug.aar            Cardboard SDK compilado (ver abajo)
docs/BITACORA_TECNICA.md          Bitácora técnica del proyecto
docs/cardboard-sdk/               El parche del SDK y cómo recompilarlo
docs/guia-instalacion.html        La instalación paso a paso, con capturas
```

---

## Documentación

La **[bitácora técnica](docs/BITACORA_TECNICA.md)** tiene el detalle de cada fase: qué problemas
aparecieron, qué se decidió y por qué. Incluye una sección sobre cómo diagnosticamos, que vale la
pena leer antes de pelearse con un bug: en este proyecto el debugger paso a paso sirve poco.

La **[guía de instalación](docs/guia-instalacion.html)** muestra la instalación paso a paso, con una
captura de cada pantalla, para quien instala el APK sin compilar. Desde GitHub hay que bajarla para verla.

---

## Sobre el Cardboard SDK versionado

En `app/libs/sdk-debug.aar` hay un binario de terceros: el
[Cardboard SDK de Google](https://github.com/googlevr/cardboard), licencia Apache 2.0, compilado
del tag `v1.35.0`.

Está versionado a propósito. Google no lo publica en ningún repositorio de dependencias, así que
la alternativa sería que cada integrante instale el NDK y lo compile por su cuenta.

**Si alguna vez hay que actualizarlo, no alcanza con recompilar:** el build de Google tiene una
falla que deja el `.aar` incompleto de una forma que no se nota hasta que la app se cierra en el
celular. El parche y los pasos están en [`docs/cardboard-sdk/`](docs/cardboard-sdk/README.md).

## Sobre el modelo de voz

Los comandos de voz usan [Vosk](https://alphacephei.com/vosk/) con el modelo
**`vosk-model-small-es-0.42`** de Alpha Cephei, licencia Apache 2.0, tal como se publica: no tiene
entrenamiento propio. No está versionado: la tarea `downloadVoskModel` de Gradle lo baja al compilar, o lo
toma de un zip local (ver [Si una descarga deja de estar](#si-una-descarga-deja-de-estar)).

## Íconos

El ícono de la app (`ic_celuvr_logo`, `ic_launcher_foreground` y las variantes monocromas) es un
visor de VR propio del proyecto.

Los íconos de la barra del visor (`app/src/main/res/drawable/ic_*_rounded.xml`) son
[Material Symbols](https://github.com/google/material-design-icons) estilo Rounded, de Google,
licencia Apache 2.0. La lupa del botón de zoom (`ic_zoom.xml`) y la flecha de "Ir arriba"
(`ic_vertical_align_top.xml`) son propias del proyecto.
