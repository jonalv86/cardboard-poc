# Changelog

Todos los cambios notables de CeluVR. El formato sigue, a grandes rasgos,
[Keep a Changelog](https://keepachangelog.com/es-ES/1.0.0/), y el proyecto usa
[versionado semántico](https://semver.org/lang/es/).

El detalle técnico de cada hito está en la [bitácora](docs/BITACORA_TECNICA.md).

## [1.3.0] — 2026-11-12

### Agregado

- **Botón "Ir arriba" en la barra del visor** (issue #31): lleva al principio del contenido. En el panel,
  al tope de la lista de apps; en las demás apps, al principio de su lista principal.
- **Arrastre que acelera contra el borde** (issue #26): con el gatillo apretado, seguir empujando contra
  un borde continúa el arrastre y lo va acelerando; al dejar de empujar frena de a poco.
- **Compilar sin las descargas externas:** `-PvoskModelZip=<ruta>` usa un zip local del modelo de voz en
  lugar de bajarlo de alphacephei.com, y el README explica cómo reemplazar las dependencias de JitPack.
- **El parche del Cardboard SDK, versionado** en `docs/cardboard-sdk/`, con los pasos para recompilarlo y
  verificar el `.aar`.
- **Guía de instalación** en `docs/guia-instalacion.html`: los pasos para instalar el APK, con una captura de
  cada pantalla. Se abre con el navegador, sin internet.

### Cambiado

- Con el panel de apps al frente, la barra ya no muestra el botón Inicio.
- **README:** "Qué hace falta" (Android 14 o posterior para el visor) y "Limitaciones" en un solo lugar;
  los pasos de instalación, corregidos y probados desde un archivo; el APK que se entrega y su firma; un
  mapa del código por subsistema.
- **Bitácora:** cómo probar el visor sin ponérselo, una sola lista de próximos pasos, y las versiones de
  Gradle y la rama principal (`master`) al día.

## [1.2.0] — 2026-11-11

### Agregado

- **Zoom de la pantalla en el visor** (issue #36): un botón con una lupa en la barra pasa de 1× a 1,5×, a
  2× y vuelve a 1×, y muestra el nivel. Con zoom, la parte que se ve sigue a la cabeza como una pantalla de
  cine; las flechas del avance por borde y la barra aparecen recién en el borde real de la pantalla.
  Funciona también con las apps con DRM. Al cerrar el visor vuelve al zoom de Ajustes.
- **Comandos de voz "acercar" y "alejar"**: suben o bajan un nivel de zoom.
- **Ajustes › Zoom:** "Zoom al iniciar" (1×, 1,5× o 2×) y "Suavizado del movimiento" de la cabeza (de 0 a
  150 ms; antes estaba fijo en 60 ms).
- **Comandos de voz offline** (issue #14): "tocar", "agarrar", "soltar", "centrar" y "volver" manejan el
  cursor sin tocar el celular. Solo cuentan dichos solos, así un video con sonido no los dispara. El
  agarre por voz se suelta solo a los 15 s.
- **Ajustes › Comandos de voz:** switch "Control por voz", apagado por defecto. Pide el permiso de
  micrófono; si se niega, queda apagado y avisa, con un botón a los permisos de la app.
- **Pantalla de Ajustes**, desde el engranaje de la pantalla principal: giroscopio (activarlo,
  sensibilidad y aceleración del puntero), zoom, comandos de voz, modo fluido y "Restablecer".
- **Video con DRM en modo fluido** (Netflix, Disney+, Prime Video, Max, Flow y otras): el visor muestra
  copias de la pantalla que compone el sistema, sin corrección de lente.
- **Apps recientes en el panel:** sección "RECIENTES" con las apps abiertas durante la sesión.
- **Setup guiado del modo fluido** (issues #39 y #40), en **Ajustes › Modo fluido**: pasos con tilde, un
  botón que lleva a "Acerca del teléfono" o a "Depuración inalámbrica" según lo que falte, y un marco
  violeta sobre la opción a tocar. El flotante del código es una burbuja arrastrable que se abre sola
  cuando aparece el diálogo de vinculación, con el teclado arriba.
- **Switch "Modo fluido"** para apagarlo sin perder la vinculación ni apagar la depuración inalámbrica. Al
  encenderlo prueba la vinculación guardada y no pide código.
- **Loader al iniciar en modo fluido** (issue #34): el ícono gira en vaivén hasta que el visor abre, y si
  falla, un aviso explica por qué (sin Wi-Fi, vinculación quitada, depuración inalámbrica apagada, servidor
  que no arrancó) y ofrece seguir en modo normal.
- Chip **"Fluido sin Wi-Fi"** en la pantalla principal cuando el modo fluido está configurado pero falta
  la red.

### Cambiado

- **Apps verticales en modo fluido:** cada app se abre en multiventana, así una que solo funciona en
  vertical llena la pantalla horizontal en lugar de verse con bandas. Ninguna app pasa sola a vertical:
  se usa el botón Vertical de la barra (issues #35 y #27).
- La configuración del modo fluido pasó de **Acerca de** a **Ajustes**.
- Al vincular con el código, CeluVR vuelve a la pantalla principal.
- CeluVR figura una sola vez en "Dispositivos vinculados", como "CeluVR", y quita sola sus entradas
  viejas al conectar.

### Corregido

- Disney+, Max y Flow se veían en negro en el visor en modo fluido (issue #48).
- En el reproductor de Disney+ el gatillo no tocaba nada: no se podía pausar ni adelantar.
- Con la mayoría de las apps al frente, la barra no mostraba el botón Vertical.
- Si el sistema cancela el dedo apoyado, CeluVR ya no lo da por apoyado hasta que se suelte el gatillo.
- El gatillo sobre los diálogos de permisos del sistema no hacía nada (issue #29).
- Al cerrar el visor, otra app quedaba adelante de CeluVR (issue #32).
- Iniciar el visor en modo fluido tardaba 5 s y el loader se congelaba.
- Después de "Olvidar emparejamiento", vincular de nuevo fallaba hasta reiniciar la app.
- "Olvidar emparejamiento" parecía no hacer nada: el servidor del modo fluido seguía vivo.
- El resaltado marcaba "Depuración inalámbrica" en lugar de la opción de vincular con código.

### Notas técnicas

- Reconocimiento con Vosk y el modelo `vosk-model-small-es-0.42` (Apache 2.0), sin entrenamiento propio.
  Gradle lo baja en la primera compilación (necesita internet) y va dentro del APK, que pasa de unos 68 a
  109 MB.
- Probado en Android 16 (Samsung SM-S938B), en modo fluido y modo proyección. Falta probar en Android 13
  o anterior.
- Las copias de pantalla para DRM y la multiventana usan interfaces ocultas de Android, desde el servidor
  propio; con Shizuku no hay copias de pantalla.
- El servicio de accesibilidad declara `isAccessibilityTool`: Google Play revisa qué apps lo hacen.
- El resaltado del setup lee los textos de la app Ajustes del celular; está probado en One UI (es-US).
- El zoom recorta el frame que ya se captura, sin subir la resolución: cada ojo muestra la captura
  achicada a la mitad, así que hasta 2× no se agranda ningún píxel. Con las apps con DRM recorta el sistema.
- Con las copias de pantalla a la vista, los toques se hacen sobre la copia del ojo izquierdo: Android
  descarta los toques a una ventana protegida dentro de la pantalla virtual. Sin zoom tienen un tercio de
  la precisión habitual.

## [1.1.0] — 2026-10-22

### Agregado

- **Modo fluido sin Shizuku.** CeluVR crea la pantalla virtual confiable con su propio proceso servidor
  (lanzado con `app_process`, con los permisos de adb), en lugar de depender de la app Shizuku. Mismo
  resultado — videos y juegos a fps completos — sin instalar nada aparte.
- **Autoconexión desde el propio celular, sin computadora.** CeluVR se vuelve cliente adb del adbd local
  (depuración inalámbrica): se empareja una vez con el código del sistema y lanza el servidor sola. Después
  de reiniciar el celular reenciende la depuración inalámbrica por su cuenta (se concede
  `WRITE_SECURE_SETTINGS` en el primer emparejamiento): no queda ningún paso manual.
- **Configuración explícita del modo fluido.** El emparejamiento se inicia a mano desde **Acerca de ›
  Modo fluido**, con el botón **"Configurar modo fluido"**, y se revierte con **"Olvidar emparejamiento"**.
  Tocar **Iniciar** nunca muestra el emparejamiento: si el modo fluido no está configurado, usa la captura
  normal.
- **Ventanita de emparejamiento.** Un teclado numérico flotante para escribir el código de vinculación
  sin salir del diálogo del sistema; aparece al tocar "Configurar modo fluido".
- **Pantalla "Acerca de" rediseñada**, con una sección desplegable **"Modo fluido (opcional)"** que, según
  el estado, guía la configuración paso a paso o confirma que ya está configurado.
- **Indicador "Modo fluido / Modo normal"** en la pantalla principal, según el modo fluido esté activo o no.
- **Ícono nuevo** (casco de VR violeta) en el logo de la app, el launcher y la notificación.
- **Botón Vertical** en la barra del visor (con la pantalla virtual): pasa la app a vertical, con la misma
  imagen desenfocada a los costados (issue #15).

### Cambiado

- Shizuku pasó a ser un **camino alternativo**: si está corriendo, se usa; si no, se usa el servidor
  propio. Ya no es necesario para la fluidez.
- Los colores del "Acerca de" siguen el patrón de la pantalla principal (títulos en el color del tema).

### Corregido

- El avance por borde se movía a los tirones (issue #25).

### Notas técnicas

- El emparejamiento TLS necesita la librería Conscrypt propia (en Android nuevo, el Conscrypt del sistema
  ya no expone `exportKeyingMaterial` con la firma esperada).
- La ventanita de emparejamiento es un overlay de accesibilidad, no de aplicación: el diálogo de
  vinculación del sistema oculta los overlays comunes por seguridad.
- Probado en Android 16 (Samsung SM-S938B). Usa interfaces ocultas de Android, que pueden cambiar entre
  versiones. El video con DRM sigue sin verse (limitación del sistema).

### Requisitos para el modo fluido (una sola vez por celular)

Activar opciones de desarrollador y la depuración inalámbrica; en CeluVR, Acerca de › Modo fluido ›
**"Configurar modo fluido"**, abrir "Vincular dispositivo con código" y escribir el código en la ventanita.
Después de cada reinicio no hay paso manual: CeluVR reenciende la depuración inalámbrica sola.

## [1.0.0]

Versión base.

- Captura en tiempo real de otra app y renderizado **estéreo** en un visor Google Cardboard (distorsión
  de lente y head tracking con el Cardboard SDK).
- Ventana flotante que sobrevive a que la app capturada pase a segundo plano.
- **Interacción** con la app capturada mediante un control VR BOX: cursor estéreo con el stick, gatillo
  para tocar, botón para Atrás, y puntero combinado con el giroscopio.
- **Navegación entre apps** desde un panel propio, con avance por borde y botón "Inicio".
- **Modo fluido con Shizuku:** pantalla virtual propia donde cualquier app se ve a fps completos.
