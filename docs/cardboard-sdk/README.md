# Recompilar el Cardboard SDK

`app/libs/sdk-debug.aar` es el [Cardboard SDK de Google](https://github.com/googlevr/cardboard) compilado
del tag **`v1.35.0`** (commit `5969239e7c87f4cd64c8ec170ce1e7f4eb559e37`) con el parche de esta carpeta,
`cardboard_sdk_jni.patch`.

**Para compilar CeluVR no hace falta nada de esto.** Sirve solo si hay que actualizar el SDK.

## Por qué hace falta el parche

El `sdk/CMakeLists.txt` de Google compila una sola librería nativa: la del plugin de Unity. Nunca compila
`libcardboard_sdk_jni.so`, la capa nativa del API de Java, que es la que usa CeluVR. Sin ella el `.aar`
compila igual y la app también, pero el visor se cierra al abrirse con un `UnsatisfiedLinkError`.

El parche agrega esa segunda librería, con las mismas fuentes del SDK más `cardboard_jni.cc`. Dos detalles
que no son de forma:

- **No lleva el version script `cardboard_api.lds`.** Ese archivo tiene `local: *` y escondería los
  símbolos `Java_com_google_cardboard_sdk_*`. El error sería el mismo, y mucho más difícil de rastrear.
- **Se tiene que llamar exactamente `cardboard_sdk_jni`:** el nombre está escrito en el código Java del SDK.

La historia completa está en la bitácora, Fase 2, problema 1.

## Pasos

1. Desde el SDK Manager de Android Studio, instalá el **NDK `29.0.14206865`**, **CMake** y **Android SDK
   Platform 35**.
2. Cloná el SDK **fuera** de este proyecto, en el tag:

   ```bash
   git clone --branch v1.35.0 https://github.com/googlevr/cardboard.git
   ```

3. Desde la carpeta `cardboard`, aplicá el parche:

   ```bash
   git apply <ruta a CardboardPOC>/docs/cardboard-sdk/cardboard_sdk_jni.patch
   ```

4. Compilá el módulo del SDK:

   ```bash
   ./gradlew :sdk:assembleDebug
   ```

5. Copiá `sdk/build/outputs/aar/sdk-debug.aar` a `app/libs/` de CeluVR.
6. Verificá que el `.aar` traiga **dos** librerías por arquitectura (ver abajo).
7. Anotá en la bitácora, Fase 2, el tag y el commit del SDK que usaste.

Usamos el `.aar` de debug porque el de release del SDK sale ofuscado, y no queremos depender de que sus
reglas conserven todo lo que CeluVR usa.

**Si actualizás a otro tag** y el `git apply` falla, el `CMakeLists.txt` de Google cambió: agregá a mano el
bloque `add_library(cardboard_sdk_jni ...)` del parche al final del archivo. Las listas de fuentes
(`${cardboard_v1_srcs}` y las demás) tienen que coincidir con las que usa la librería de Unity.

## Verificar el `.aar`

Un `.aar` es un zip. Listá sus librerías nativas:

```bash
unzip -l app/libs/sdk-debug.aar | grep '\.so$'
```

En Windows, `tar -tf app\libs\sdk-debug.aar | findstr .so`.

Tienen que salir cuatro, dos por arquitectura:

```
jni/arm64-v8a/libGfxPluginCardboard.so
jni/arm64-v8a/libcardboard_sdk_jni.so
jni/armeabi-v7a/libGfxPluginCardboard.so
jni/armeabi-v7a/libcardboard_sdk_jni.so
```

Si falta `libcardboard_sdk_jni.so`, el parche no se aplicó. El `.aar` bueno pesa unos 988 KB; sin el
parche, unos 566 KB.

Para confirmar que la librería exporta el API de Java, extraela y mirá sus símbolos con el `llvm-nm` del
NDK (`<NDK>/toolchains/llvm/prebuilt/<sistema>/bin/`):

```bash
llvm-nm -D --defined-only libcardboard_sdk_jni.so | grep Java_com_google_cardboard_sdk
```

Tiene que listar unas 20 funciones. Si no lista ninguna, la librería quedó con el version script.

**No hay versión x86_64:** el `sdk/build.gradle` de Google compila solo armeabi-v7a y arm64-v8a
(`abiFilters`). Por eso un emulador x86_64 necesita traducción ARM (ver "Para compilar" en el README).
