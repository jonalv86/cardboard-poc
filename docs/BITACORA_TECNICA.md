# Bitácora Técnica — Manejo por Google Cardboard de Aplicaciones Android

> Documento vivo del equipo. Se actualiza al cerrar cada hito (no reemplaza al informe de
> Investigación Bibliográfica Inicial entregado a la cátedra, lo complementa con el detalle
> de implementación). Ubicación sugerida en el repo: `docs/BITACORA_TECNICA.md`.

## Índice

- [Requisitos del entorno](#entorno)
- [Fase 1 — Prueba de concepto de captura de pantalla](#fase-1)
- [Fase 1.5 — Ventana overlay](#fase-15)
- [Fase 2 — Integración con Cardboard SDK](#fase-2)
- [Cómo diagnosticamos](#diagnostico)
- [Convenciones del equipo](#convenciones)
- [Próximos pasos](#proximos-pasos)

---

<a id="entorno"></a>
## Requisitos del entorno

Esta sección es para cualquiera que clone el repo y quiera compilar. Leerla primero ahorra un
rato largo: **la mayoría de los problemas que parecen del código son en realidad del entorno.**

### Para compilar y correr la app

| | |
|---|---|
| **Android Studio** | **Ladybug (2024.2.1) o superior.** No es opcional, ver abajo. |
| **JDK** | 17 o superior (el equipo está usando 21). |
| **Celular de prueba** | Android 8.0 o superior. En Android 7 la app no instala. |
| **NDK / CMake** | **No hacen falta.** El `.aar` del SDK ya está compilado y versionado en `app/libs/`. |

Con eso alcanza: `./gradlew :app:assembleDebug` tiene que compilar sin tocar nada más.

### Por qué la versión de Android Studio no se puede elegir

El `.aar` del Cardboard SDK lleva grabada adentro la exigencia de compilar contra la API 35, y el
build falla si uno intenta bajar a 34. De ahí se encadena todo: la API 35 requiere el plugin de
Android 8.6 o superior, y ese plugin requiere Android Studio Ladybug o más nuevo.

Con una versión anterior **el proyecto ni siquiera se puede abrir**: Android Studio lo rechaza
antes de sincronizar, con el mensaje *"The project is using an incompatible version (AGP 8.7.3) of
the Android Gradle plugin"*.

Ojo con el síntoma, porque despista: por línea de comandos compila perfecto mientras el IDE marca
imports en rojo y se niega a sincronizar. **Si te pasa eso, no busques el problema en el código.**

Y al actualizar aparece un segundo escalón: las versiones nuevas de Android Studio traen un Java
25 adentro, y Gradle 8.9 soporta hasta el 22. Sale un diálogo pidiendo elegir una JVM — hay que
elegir **JVM 21**, que es la que venimos usando por línea de comandos. No conviene resolverlo
subiendo Gradle: sería mover una versión que hoy funciona, sin necesidad.

### Dos cosas que conviene NO hacer

**No aceptes subir el plugin de Android a la versión 9.** Android Studio lo va a ofrecer con un
banner apenas abras el proyecto. La 9 tiene cambios que rompen, y las versiones que están fijadas
hoy (Gradle 8.9, plugin 8.7.3) son las que están probadas y funcionando. Si alguien lo sube, que
sea como una tarea propia y con tiempo, no de paso.

**No actualices el Cardboard SDK a la ligera.** Recompilarlo no es bajar una dependencia: hay que
aplicarle un parche a mano que está explicado en la Fase 2. Sin ese parche el `.aar` sale roto de
una forma que no se nota hasta que la app se cierra en el celular.

### Solo si necesitás recompilar el SDK

No debería hacer falta, pero si hay que actualizarlo:

1. Clonar `github.com/googlevr/cardboard` **fuera** de este proyecto.
2. Instalar, desde el SDK Manager: **NDK `29.0.14206865`**, **CMake** y **Android SDK Platform 35**.
3. Aplicar el parche al archivo de compilación nativa del SDK (Fase 2, problema 1). **Este paso es
   obligatorio y no está en la documentación de Google.**
4. Compilar el módulo del SDK y copiar el `.aar` resultante a `app/libs/`.
5. Verificar que el `.aar` quedó con **dos** librerías nativas por arquitectura, no una.
6. Anotar en la Fase 2 el commit del SDK que se usó.

---

<a id="fase-1"></a>
## Fase 1 — Prueba de concepto de captura de pantalla

**Objetivo:** confirmar que es técnicamente viable capturar en tiempo real el contenido
renderizado de otra aplicación Android, sin usar red (a diferencia del antecedente "VR VNC"),
como base para el sistema alternativo de renderizado SBS que reemplaza al VR Mode nativo
que Android discontinuó.

**Implementación:**
- `MediaProjectionManager` + `MediaProjection` + `VirtualDisplay` para capturar la pantalla.
- `ImageReader` para recibir los frames capturados como `Bitmap`.
- `ScreenCaptureService`: foreground service con `foregroundServiceType="mediaProjection"`,
  obligatorio desde Android 14 para poder usar `MediaProjection`.
- Permisos en manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`.

**Problema encontrado y solución:**
- La app se cerraba (`IllegalStateException`) al intentar capturar. Causa: en Android 14+ es
  obligatorio registrar un `MediaProjection.Callback` mediante `registerCallback()` **antes**
  de llamar a `createVirtualDisplay()`. Sin el callback registrado, el sistema no permite
  iniciar la captura.

**Resultado:** funcional. Captura confirmada en pantalla completa (modo espejo, capturando
la propia app) el {COMPLETAR FECHA}.

---

<a id="fase-15"></a>
## Fase 1.5 — Ventana overlay

**Motivación:** en Android 14+, cuando el usuario elige compartir **"una app específica"**
(en vez de "pantalla completa") en el diálogo del sistema de `MediaProjection`, Android no
devuelve el foco a la app que originó la captura: lanza directamente la app elegida al frente
y empuja la app capturadora a segundo plano. Como la Fase 1 mostraba el resultado dentro de
la propia `Activity`, ese contenido dejaba de ser visible apenas se elegía una app puntual.
**Conclusión:** la vista de resultado no puede depender de que la Activity esté en primer
plano; necesita ser una ventana que se mantenga visible por encima de cualquier otra app.

**Implementación:**
- Permiso `SYSTEM_ALERT_WINDOW` + verificación en runtime con `Settings.canDrawOverlays()` +
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` para que el usuario lo habilite manualmente
  (no se pide con un diálogo simple, requiere ir a Ajustes).
- Ventana `TYPE_APPLICATION_OVERLAY` gestionada con `WindowManager`, agregada/quitada según
  el ciclo de vida del servicio de captura.

**Problemas encontrados y soluciones:**

1. **Opacidad vs. toques — trade-off confirmado empíricamente.**
   Opacidad total y "toques que atraviesan la ventana" (`FLAG_NOT_TOUCHABLE`) son
   mutuamente excluyentes. Se optó por **opacidad total**: la ventana overlay consume los
   toques, por lo que la app de fondo deja de recibirlos.
   *Consecuencia para la Fase 3 (interacción):* como un `ImageView` no es clickable por
   defecto y no tiene listener, los toques no producen ninguna acción todavía — es
   funcionalidad pendiente, no un bug.

2. **Efecto "mosaico" (imagen repetida en tiles).**
   Causa: el flujo de uso era elegir la app a capturar en modo vertical y recién después
   rotar el celular a horizontal (para el visor Cardboard). El `VirtualDisplay` se creaba
   con las dimensiones de la orientación vieja (vía `getRealMetrics()`, que no se
   actualiza sola al rotar), generando un desfasaje entre el tamaño del buffer esperado y
   el contenido real volcado.
   Solución: fijar `android:screenOrientation="landscape"` en el manifest para que toda
   la app (incluida la elección de qué capturar) ocurra siempre en la orientación final,
   sin rotación intermedia.
   **Alcance real de esta solución (precisado en la Fase 2):** el lock está en `MainActivity`
   y controla la orientación de *nuestra activity*, no la del display. Cubre el caso de que el
   usuario rote el celular, pero **no** cubre que una app de terceros fuerce portrait. Ver
   "Limitaciones conocidas al cierre de la Fase 2".

3. **Confirmación empírica de `FLAG_SECURE` con Netflix.**
   Con el overlay ya opaco y bien dimensionado, se probó capturar Netflix: el área
   capturada se muestra en negro, confirmando que las apps con contenido DRM bloquean la
   captura de pantalla a nivel de sistema. Con apps sin DRM (calculadora, navegador) la
   captura se ve correctamente. *Este es un hallazgo con evidencia propia, no solo
   bibliografía — documentar con captura de pantalla en el informe final.*

4. **Camino técnico para la interacción futura (Fase 3), ya mapeado:**
   - Convertir coordenada de toque local → coordenada de la pantalla capturada: usar la
     `imageMatrix` del `ImageView` (da la transformación gratis, sobrevive a cambios de
     `scaleType`).
   - Para accionar sobre la app capturada: `AccessibilityNodeInfo.performAction(ACTION_CLICK)`,
     **no** `dispatchGesture()`. Motivo: un tap sintético por coordenada vía
     `dispatchGesture()` entra por el pipeline de input normal, y la ventana táctil más alta
     es el propio overlay — se lo comería a sí mismo, en bucle. `performAction` opera sobre
     el nodo directamente y no pasa por la capa de toques. Válido tanto para control táctil
     como por mirada (gaze) o botón bluetooth.
   - **Limitación conocida a validar por app:** apps que dibujan su UI sobre un Canvas
     propio (juegos, algunos reproductores con controles custom) pueden no exponer nodos de
     accesibilidad individuales — un solo nodo "pantalla completa" en vez de un nodo por
     botón. En esos casos `performAction` no tiene qué apuntar. Pendiente probar con 2-3
     apps candidatas al demo antes de comprometerse a una sola estrategia de interacción.

**Resultado:** funcional. Overlay opaco, correctamente dimensionado en landscape, con
hallazgos de `FLAG_SECURE` y de estrategia de interacción documentados.

---

<a id="fase-2"></a>
## Fase 2 — Integración con Cardboard SDK

**Objetivo:** dejar atrás el estéreo "casero" de la Fase 1.5 —dos `ImageView` mostrando el mismo
bitmap duplicado, sin ninguna corrección óptica— y pasar a un renderizado estereoscópico de
verdad con el Cardboard SDK de Google. Eso aporta dos cosas que hoy no teníamos: la distorsión
de barril que compensa las lentes del visor, y la lectura de la orientación de la cabeza.

### Lo primero que aprendimos: el SDK no se baja, se compila

Uno espera agregar una línea al `build.gradle` y seguir. Con el Cardboard SDK no se puede:
Google no lo publica en Maven Central ni en su propio repositorio, y las releases de GitHub
tampoco traen un `.aar` listo para usar. Hay que clonar `github.com/googlevr/cardboard` y
compilarlo uno mismo, lo que obliga a instalar el NDK (usamos la versión `29.0.14206865`),
CMake y el Platform 35 del SDK de Android.

Eso condiciona cómo trabaja el equipo, así que tomamos una decisión temprano: **el `.aar`
compilado se commitea al repo**, en `app/libs/sdk-debug.aar`. Quien lo compila lo sube una vez y
el resto lo levanta con un `git pull`, sin instalar NDK ni CMake. En `build.gradle.kts` entra
como `implementation(fileTree("libs") { include("*.aar") })`.

Usamos el `.aar` de debug y no el de release porque el build de release del SDK tiene ofuscación
activada, y preferimos no depender de que sus reglas preserven todo lo que necesitamos.

Versión usada: **tag `v1.35.0`, commit `5969239e7c87f4cd64c8ec170ce1e7f4eb559e37`**
(10/08/2026). Si alguna vez se actualiza, hay que repetir la compilación —incluido el parche que
se explica más abajo— y volver a commitear el `.aar`, anotando acá el commit nuevo.

### La decisión de diseño más importante: por dónde entrarle al SDK

El SDK ofrece dos caminos, y la documentación oficial muestra solo uno.

El camino documentado es el de **NDK/C++**: escribís tu propia capa JNI y el renderer en C++,
dentro de tu app. El otro, mucho menos visible, es un **API en Java** que vive en la carpeta
`java_api/` del repo del SDK. Ese API expone una clase `CardboardView`: una vista que ya trae
adentro todo el manejo de OpenGL, la distorsión y el head tracking, y que solo te pide que le
digas qué dibujar en cada ojo.

**Elegimos el API de Java**, por una razón práctica: con ese camino el NDK y CMake hacen falta
**una sola vez**, para generar el `.aar`. La app en sí queda sin nada de C++ y todo el renderer
se escribe en Kotlin, igual que el resto del proyecto. Además encaja con la decisión de
commitear el `.aar`: quien hace `git pull` compila sin instalar nada.

Antes de escribir una línea de código verificamos algo que podía tirar el plan abajo. Nuestro
overlay vive dentro de un `Service`, y varias APIs de Android no funcionan desde ahí porque un
servicio no tiene una pantalla asociada. Leyendo el fuente del SDK confirmamos que resuelve la
pantalla por un camino que sí sirve desde un servicio. Esa verificación resultó **incompleta**, y
más adelante nos costó un crash — está contado abajo.

**Problemas encontrados y soluciones:**

1. **El SDK de Google no compila su propia capa nativa.**
   *Es el hallazgo más importante de la fase y no está documentado en ningún lado.*

   Compilamos el SDK siguiendo las instrucciones de Google, integramos todo, y la app compilaba
   sin un solo error. Pero al abrir el overlay se cerraba al instante: faltaba una librería
   nativa, `libcardboard_sdk_jni.so`. El mensaje de error no menciona al SDK por ningún lado, así
   que es de esos problemas en los que uno pierde horas buscando la falla en el código propio.

   El motivo es que el archivo que le dice al SDK qué compilar construye **una sola librería
   nativa: la del plugin de Unity**. Nunca compila la capa nativa del API de Java, que es
   justamente el camino que elegimos. El resultado es un `.aar` a medias: trae las clases Java
   que necesitamos, pero no la implementación por debajo que esas clases salen a cargar. Lo
   confirmamos revisando los símbolos del binario, donde del API de Java no aparecía ninguno.

   **La solución** fue agregar a mano, en nuestro clon del SDK, esa segunda librería que Google
   no construye: reusa las mismas fuentes del SDK y le suma el archivo de la capa Java.

   Dos detalles que parecen menores y no lo son:
   - La librería nueva **no** tiene que llevar el filtro de símbolos que usa la otra. Ese filtro
     esconde todo lo que no esté explícitamente permitido, así que si se lo aplicás la librería
     se genera igual pero los métodos siguen sin resolver — y el error es idéntico, mucho más
     difícil de rastrear.
   - El nombre tiene que ser exactamente `cardboard_sdk_jni`, porque está escrito a mano dentro
     del propio código del SDK.

   Para verificar que salió bien: el `.aar` tiene que quedar con **dos** librerías nativas por
   arquitectura en vez de una. Pasa de 566 KB a 988 KB, y el APK de 12,9 a 14,6 MB.

   **Ojo con esto a futuro:** el parche vive en el clon del SDK, que no es un repositorio nuestro
   y por lo tanto no se versiona. Queda escrito acá porque es la única forma de reproducirlo.
   Quien actualice el SDK va a tener que aplicarlo de nuevo a mano.

2. **El SDK nos obligó a modernizar el proyecto entero, y también el Android Studio.**

   El SDK exige Android 8.0 como mínimo, compilar contra la API 35 y Java 17. El proyecto venía
   bastante más atrás: API 34, Android 7 como mínimo, Java 8 y una versión del plugin de Gradle
   que ni siquiera acepta la API 35. Hubo que subir todo: Gradle 8.9, plugin de Android 8.7.3,
   API 35, mínimo Android 8.0 y Java 17.

   **Importante para todo el equipo:** esto no es negociable ni se puede dejar a medias. El `.aar`
   del SDK lleva grabada adentro la exigencia de compilar contra la API 35, y el build falla si
   uno intenta bajar a 34. De ahí se encadena todo lo demás: la API 35 requiere el plugin de
   Android 8.6 o superior, y ese plugin requiere **Android Studio Ladybug (2024.2.1) como
   mínimo**. Con versiones anteriores el proyecto ni siquiera se puede abrir: Android Studio lo
   rechaza antes de sincronizar.

   Lo descubrimos de la peor manera: el proyecto compilaba perfecto por línea de comandos
   mientras Android Studio marcaba todo en rojo y se negaba a sincronizar. **Si te pasa eso,
   no busques el problema en el código** — fijate primero la versión del IDE.

   *Al día de hoy el equipo trabaja con Android Studio Ladybug o superior.*

   **La otra consecuencia a asumir:** la app deja de instalarse en Android 7. Tampoco hay
   alternativa, lo impone el SDK. Los celulares de prueba tienen que ser Android 8.0 o superior.

   Un detalle menor pero que hace perder tiempo: un `.aar` guardado a mano no arrastra las
   librerías de las que depende, así que hay que declararlas una por una en nuestro
   `build.gradle.kts`.

3. **El servicio no tiene pantalla, y el SDK sí la necesita.**

   Con la librería nativa ya resuelta, la app se cerraba de golpe al mostrar el overlay. Esta vez
   ni siquiera dejaba un error de Java legible: el proceso moría directamente.

   La causa terminó siendo la verificación incompleta que mencionamos más arriba. Para saber a
   qué densidad dibujar, el SDK necesita datos de la pantalla, y el camino que usa para pedirlos
   solo funciona desde una `Activity`. Nuestro overlay corre dentro de un `Service`, que **no
   está asociado a ninguna pantalla**, así que ese pedido falla — y como el que estaba pidiendo
   era código nativo, en vez de una excepción prolija se lleva puesto el proceso entero.

   Nos había pasado por alto porque revisamos la clase principal del SDK, vimos que ahí resolvía
   la pantalla de una forma que sí sirve desde un servicio, y dimos el tema por cerrado. Pero el
   dato de densidad se pide desde otra clase distinta, que usa el camino que no sirve.

   *La lección, que vale para lo que viene:* cuando se trabaja desde un `Service`, no alcanza con
   revisar la clase por la que entrás. Hay que seguir todos los caminos que consultan la
   pantalla, incluidos los que el código nativo llama de vuelta hacia Java.

   **La solución** fue construir un contexto que sí esté asociado a la pantalla y pasárselo al
   SDK. Importa que sea el mismo en los dos puntos donde el SDK lo recibe, porque se lo guarda la
   primera vez y lo reusa después.

4. **El SDK nos metía permisos que no pedimos.**

   Al integrar el `.aar`, la app pasaba a pedir permiso de **cámara**, de **NFC** y de
   almacenamiento, y quedaba declarando dos pantallas del SDK que nunca abrimos. Todo eso es para
   escanear el QR del visor y detectarlo por NFC, funciones que en esta fase no usamos. Le
   pedimos explícitamente al sistema de build que las descarte, y verificamos sobre el resultado
   final que efectivamente no quedaran. No es un detalle cosmético: una app que pide la cámara
   sin motivo es una app que el usuario —y la cátedra— tiene derecho a mirar con desconfianza.

### Sobre el QR del visor, y por qué decidimos no usarlo

El QR impreso en los visores Cardboard no es un número de modelo: es la geometría óptica de esa
caja en particular —la distancia entre las lentes, la distancia de la lente a la pantalla, el
campo visual y los coeficientes de la curva de distorsión—. El SDK lo usa para calcular cuánto
deformar la imagen.

El visor de prueba es un **VR BOX genérico**, sin QR. Sin un perfil guardado, el SDK usa por
defecto los valores del Cardboard original de Google (2014) y **construye y aplica la distorsión
igual**. O sea, el objetivo de la fase se cumple: lo que se pierde no es la corrección, sino su
precisión. Se nota como una deformación residual en los bordes y una leve sensación de imagen
corrida, porque estamos corrigiendo para unas lentes que no son exactamente las nuestras. El head
tracking no usa estos datos, así que no se ve afectado en nada.

Estos son los valores concretos que el SDK está usando, útiles para comparar contra cualquier
visor con una regla:

| Parámetro | Valor asumido |
|---|---|
| Distancia entre centros de lentes | 60 mm |
| Distancia lente → pantalla | 42 mm |
| Centro de lente desde el borde del celular | 35 mm |
| Campo visual | 40° en los cuatro lados |
| Coeficientes de distorsión | 0.441 / 0.156 |

**Un atajo que conviene probar antes que cargar un perfil:** el VR BOX tiene dos ruedas de ajuste
arriba, una para la distancia de las lentes a la pantalla y otra para la separación entre lentes.
Teniéndolas, el problema se puede resolver al revés: en vez de cargarle al SDK el perfil de la
caja, se ajusta la caja a lo que el SDK asume —60 mm de separación, 42 mm a la pantalla—. Es diez
minutos con una regla. La separación es la sospechosa principal, porque 60 mm es angosto para un
adulto promedio (lo habitual son 62 a 65).

La alineación vertical, en cambio, no es problema: el SDK asume el centro de lente a 35 mm del
borde, y en el celular de prueba acostado la mitad del ancho da 36,5 mm. Milímetro y medio de
diferencia.

*Pendiente para una etapa posterior:* si el ajuste físico no alcanza, cargar el perfil real del
visor. Se puede escanear el QR de un modelo equivalente, o medir la caja con el generador de
perfiles de Google (`wwgc.firebaseapp.com`), que además deja calibrar la distorsión mirando por
las lentes. El QR se puede mostrar en el monitor de la PC, no hace falta imprimir nada.

### Cómo quedó armado el renderizado

El reparto de tareas con el SDK es el siguiente: **la distorsión de barril no la escribimos
nosotros**. El SDK arma un framebuffer con las dos mitades, nos pide que dibujemos el contenido
de cada ojo, y después aplica sobre eso la deformación que corresponde a cada lente. Nuestro
trabajo se reduce a entregarle la imagen capturada.

La clase nueva es `CardboardOverlayRenderer`, y hace tres cosas:

- **Sube el frame capturado a una textura de OpenGL**, una sola vez por cuadro. Esto importa
  porque el SDK nos llama dos veces por cuadro, una por ojo, y subir la imagen dos veces sería
  tirar la mitad del trabajo a la basura.
- **Dibuja la imagen en cada ojo respetando la proporción.** La pantalla capturada es muy
  apaisada y la mitad que le toca a cada ojo es casi cuadrada, así que si uno la estira sin más,
  sale deformada a lo alto. Hay un cálculo que la encaja manteniendo la proporción, con banda
  negra donde sobra.
- **Lee la orientación de la cabeza** y la deja en el log cada 60 cuadros. En esta fase la imagen
  **no se mueve con la cabeza** —es lo planeado— así que el log es la única forma de comprobar
  que el sensor está leyendo bien: al girar el celular, los ángulos cambian de forma coherente y
  vuelven al valor original.

Hubo un detalle sutil que costó entender y vale dejarlo anotado: **no hay que limpiar la pantalla
al dibujar cada ojo.** La orden de limpiar ignora la división en mitades y borra todo, así que
limpiar antes de dibujar el ojo derecho hacía desaparecer el izquierdo. El SDK ya limpia una sola
vez, antes de los dos.

Del lado de la Fase 1.5 no se tocó nada: el servicio de captura quedó sin una sola modificación, y
en la ventana del overlay se conservaron exactamente los mismos parámetros —opacidad, banderas,
manejo de los bordes de la pantalla—, que habían costado bastante de afinar.

**Un riesgo que anticipamos y no se dio:** la vista de OpenGL que trae el SDK funciona "perforando"
la ventana que la contiene, y eso podía chocar con la configuración de opacidad total del overlay
y dar pantalla negra. Lo probamos en el celular y anda bien tal como está.

### Limitaciones conocidas al cierre de la Fase 2

1. **Las apps que se fuerzan en vertical rompen la vista estéreo.**

   Probando con Netflix, que arranca en vertical y no acepta rotar, se ve clarito en nuestro
   propio log:

   ```
   14:53:06.756  superficie estéreo=2340x1080, por ojo=1170x1080   ← horizontal, todo bien
   14:53:06.952  superficie estéreo=1080x2340, por ojo=540x2340    ← rotó a vertical
   ```

   Lo que pasa es que **la app que está adelante decide la orientación de toda la pantalla**, y
   nuestro overlay no tiene voto: no es una pantalla de la app, es una ventana flotante, y sigue
   al sistema. El lock de landscape que habíamos puesto en la Fase 1.5 solo gobierna nuestra
   propia pantalla de inicio, no ésta situación (agregamos esa precisión al punto 2 de aquella
   fase, donde estaba escrito como si resolviera el problema en general).

   La captura en sí **se adapta bien**: detecta el cambio y se reconstruye con las medidas
   nuevas. Lo que queda mal es la división en dos ojos, porque el SDK siempre parte la pantalla
   por la mitad a lo ancho. En vertical eso da dos tiras altas y flacas, con la imagen encogida a
   la mitad. Inservible dentro del visor, donde el celular está físicamente acostado.

   *Diferido a una etapa posterior*, con el comportamiento deseado ya definido: mostrar las apps
   verticales corrigiendo el tamaño —aunque se vean más chicas— y volver a corregir cuando se
   elija pantalla completa.

2. **La captura se puede cerrar de golpe justo al rotar.**

   Vimos dos cierres inesperados, los dos en el mismo lugar: el momento en que se convierte el
   frame capturado en imagen. Son dos caras del mismo problema. Cuando la pantalla cambia de
   orientación, el hilo principal rehace la captura y libera el lector de frames, pero el hilo que
   está leyendo puede estar justo en la mitad de copiar los píxeles de un frame que acaba de
   desaparecerle bajo los pies.

   **Es código de la Fase 1.5, no algo que haya traído la Fase 2**, y el disparador es el mismo
   evento que causa la limitación anterior: la rotación. Conviene tenerlo presente porque la
   rotación no es el único disparador — también salta con cambios de resolución o de tasa de
   refresco.

3. **No se pueden sacar capturas de pantalla de nuestra propia app.**

   El mecanismo que usamos para que el overlay no se capture a sí mismo (y no se produzca el
   efecto de imagen dentro de imagen, infinito) hace dos cosas atadas: excluye la ventana de la
   grabación, que es lo que queríamos, y de paso bloquea los screenshots, que no. No se pueden
   separar. También afecta a las capturas por consola.

   Para el informe final esto importa, porque la evidencia del hallazgo de Netflix de la Fase 1.5
   justamente no se puede documentar con un screenshot. Evaluamos tres salidas: desactivar la
   protección solo en las compilaciones de prueba, volcar la imagen a un archivo desde el propio
   renderizador, o sacarle una foto a la pantalla con otro celular. **Por ahora, ninguna.**

   Vale una observación: en el modo "una sola app" —el que usamos en la práctica— esa protección
   probablemente sea innecesaria, porque se espeja únicamente la app elegida y el overlay no forma
   parte de eso. No lo comprobamos.

**Plan detallado:** se elaboró un plan técnico previo a la implementación, cubriendo la
compilación del SDK nativo y la checklist de primera prueba en el visor físico. Está
guardado por ahora en la máquina de Jony, pendiente subirlo al repo (sugerido:
`docs/planes/fase-2-plan.md`) para que quede accesible a todo el equipo.

**Resultado: fase cerrada.** Los dos objetivos se cumplieron y se verificaron en el dispositivo:

- **Distorsión de lente:** el SDK la construye y la aplica por ojo. Se ve correctamente a través
  del visor. La calibración es aproximada por decisión de alcance —se usan los valores del
  Cardboard original, no los del VR BOX— y queda registrado arriba cómo mejorarla.
- **Head tracking:** verificado moviendo el celular sobre cada eje y comprobando en el log que se
  mueve el ángulo que corresponde. En esta fase la imagen no sigue a la cabeza, que es lo
  planeado.

El pipeline de captura de la Fase 1.5 quedó intacto, sin una sola modificación. Las tres
limitaciones conocidas de arriba están todas diferidas a etapas posteriores, con criterio
acordado.

**Equipo de prueba:** Samsung con Android 16, visor VR BOX genérico.

---

<a id="diagnostico"></a>
## Cómo diagnosticamos

Vale la pena dejar escrito esto aparte, porque en este proyecto **el debugger paso a paso sirvió
poco**. Casi todos los problemas aparecieron en lugares donde no se puede frenar la ejecución: un
hilo de renderizado que corre 60 veces por segundo, una ventana flotante que vive fuera de la app,
o directamente código nativo en C++. Estas fueron las herramientas que sí funcionaron.

**1. Logs propios, puestos a propósito y con un solo tag.**

Todo el proyecto loguea bajo el tag `CardboardCapture`, así que un `adb logcat -s CardboardCapture`
deja ver el flujo completo sin el ruido del sistema. Los logs no son genéricos: cada uno existe
para responder una pregunta concreta.

- Las medidas en cada paso de la cadena (pantalla real → `VirtualDisplay` → bitmap → superficie
  estéreo → mitad por ojo). Cuando algo se ve deformado, comparar esos números dice enseguida en
  qué eslabón se rompió. Fue así como quedó demostrado en dos líneas que el problema de las apps
  verticales era la rotación del sistema y no nuestra captura.
- El muestreo de contenido del frame: en vez de confiar en la vista, se samplea una grilla de
  10×10 píxeles y se cuenta cuántos tienen contenido. Eso distingue **"la captura falló"** de
  **"la captura anduvo y el contenido es negro"**, que a simple vista son idénticos. Fue lo que
  confirmó el comportamiento de las apps con DRM.
- Los ángulos de la cabeza cada 60 cuadros. Loguear en cada cuadro satura el log y no aporta nada.

**2. Leer el código fuente del SDK antes de escribir el nuestro.**

Suena obvio pero fue determinante. La existencia misma del API de Java —el camino que terminamos
usando— no está en la documentación oficial: apareció leyendo el repositorio. Y la verificación de
si el SDK funcionaba desde un servicio se hizo leyendo el fuente, no probando. El costo de esa
lectura es bajo comparado con descubrirlo después de escribir todo.

**3. Abrir los artefactos compilados y mirar adentro.**

El hallazgo más difícil de la fase —que el SDK no compila su propia capa nativa— no se encontró
leyendo código ni con el debugger, sino **abriendo el `.aar` como si fuera un zip** y listando qué
librerías traía, y después escaneando los símbolos del binario para ver qué funciones exportaba
realmente. El código Java estaba completo; el problema estaba en un archivo que no existía.

Regla que sacamos: cuando el error dice que falta algo, verificar primero si ese algo está
físicamente en el paquete, antes de buscar la causa en la lógica.

**4. Revisar el resultado del build, no solo lo que escribimos.**

Al sacar los permisos que metía el SDK no alcanzaba con escribir la instrucción de borrado: hay
que abrir el manifest que **genera** el build y confirmar que efectivamente no quedaron. Lo mismo
con el `.apk` final y sus librerías. Lo que uno declara y lo que termina empaquetado no siempre
coinciden.

**5. Distinguir los dos tipos de cierre inesperado.**

No es lo mismo un error de Java —que deja un stack trace legible y la app se reinicia— que un
cierre a nivel nativo, donde el proceso muere sin dejar nada en el log de la aplicación. Para el
segundo hay que buscar el informe que el sistema escribe aparte, que incluye el rastro completo
combinando las capas nativa y Java. Los dos problemas más duros de esta fase fueron de ese
segundo tipo, y si uno filtra el log solo por el nombre de la app, no aparecen.

**6. Limpiar el log antes de cada prueba.**

`adb logcat -c` antes de reproducir un problema. Con un buffer de 300.000 líneas acumuladas,
encontrar el evento correcto es más difícil que el problema en sí.

---

<a id="convenciones"></a>
## Convenciones del equipo

- **Ramas:** `usuario/feature/descripción` (ej. `jona/feature/cardboard-sdk`).
- **Commits:** al cerrar cada hito funcional, no solo al final de una fase completa.
- **Pull Requests:** toda rama se mergea a `main` vía PR, revisado por los otros dos
  integrantes antes de aceptar.
- **Antes de ramificar una fase nueva:** `git checkout main && git pull` para partir
  siempre de la versión más actualizada.

---

<a id="proximos-pasos"></a>
## Próximos pasos

**Lo que sigue ahora:**

- [ ] **Fase 3 — tomar los toques.** Interacción por mirada o botón, con
      `AccessibilityNodeInfo.performAction` como estrategia principal (plan B: `dispatchGesture`
      más un toggle de `FLAG_NOT_TOUCHABLE`, para apps que no expongan nodos de accesibilidad
      individuales).
- [ ] Validar `performAction` contra 2 o 3 apps candidatas al demo antes de fijar la estrategia
      de interacción definitiva.
- [ ] Commitear `app/libs/sdk-debug.aar`, que hoy figura como no versionado, para que el resto
      del equipo pueda compilar sin instalar NDK ni CMake.

**Diferido, con el criterio ya acordado:**

- [ ] **Apps verticales:** mostrarlas corrigiendo el tamaño —aunque se vean más chicas— y volver
      a corregir cuando se elija pantalla completa.
- [ ] Blindar la carrera que puede cerrar la captura al rotar (limitación 2 de la Fase 2).
- [ ] Afinar la óptica. Primero lo barato: ajustar con una regla las dos ruedas del VR BOX a lo
      que el SDK asume (60 mm entre lentes, 42 mm a la pantalla). Si con eso no alcanza, recién
      ahí cargar un perfil real del visor.
- [ ] Resolver cómo sacar evidencia visual para el informe, hoy bloqueada por la protección de
      captura de la propia app.

**Del proyecto en general:**

- [ ] Definir con la cátedra el alcance de la rama de AR (ARCore).
- [ ] Actualizar este documento al cierre de cada hito.
