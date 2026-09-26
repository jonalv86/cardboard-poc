# CardboardPOC

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
| 3 | Interacción — poder accionar sobre la app capturada | Pendiente |

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
2. Abrí CardboardPOC y tocá **Iniciar captura**.
3. La primera vez te va a mandar a Ajustes a habilitar *"Mostrar sobre otras apps"*. Es un permiso
   que Android no concede con un diálogo simple.
4. Elegí **"Una sola app"** y seleccioná la que querés ver en el visor.
5. Poné el celular en el visor, centrado con la **línea vertical** que se dibuja abajo.

Para detener la captura, usá la notificación o el botón de la pantalla principal.

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

- **No se puede accionar sobre la app capturada.** La ventana flotante se come los toques. Es el
  objetivo de la Fase 3.
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
