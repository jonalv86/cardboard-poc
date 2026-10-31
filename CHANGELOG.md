# Changelog

Todos los cambios notables de CeluVR. El formato sigue, a grandes rasgos,
[Keep a Changelog](https://keepachangelog.com/es-ES/1.0.0/), y el proyecto usa
[versionado semántico](https://semver.org/lang/es/).

El detalle técnico de cada hito está en la [bitácora](docs/BITACORA_TECNICA.md).

## [Sin publicar]

### Agregado

- **Comandos de voz offline** (issue #14): "tocar", "agarrar", "soltar", "centrar" y "volver" manejan el
  cursor sin tocar el celular. Solo cuentan dichos solos, así un video con sonido no los dispara. El
  agarre por voz se suelta solo a los 15 s.
- **Ajustes › Comandos de voz:** switch "Control por voz", apagado por defecto. Pide el permiso de
  micrófono; si se niega, queda apagado y avisa, con un botón a los permisos de la app.

### Corregido

- Si el sistema cancela el dedo apoyado, CeluVR ya no lo da por apoyado hasta que se suelte el gatillo.

### Notas técnicas

- Reconocimiento con Vosk y el modelo `vosk-model-small-es-0.42` (Apache 2.0), sin entrenamiento propio.
  Gradle lo baja en la primera compilación (necesita internet) y va dentro del APK, que pasa de unos 68 a
  109 MB.
- Probado en Android 16 (Samsung SM-S938B), en modo fluido y modo proyección. Falta probar en Android 13
  o anterior.

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

### Cambiado

- Shizuku pasó a ser un **camino alternativo**: si está corriendo, se usa; si no, se usa el servidor
  propio. Ya no es necesario para la fluidez.
- Los colores del "Acerca de" siguen el patrón de la pantalla principal (títulos en el color del tema).

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
