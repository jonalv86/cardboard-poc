import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

/**
 * Baja el modelo de voz de Vosk y lo deja como asset en [outputDir]/model-es, con el archivo uuid que
 * StorageService.sync compara para decidir si vuelve a copiarlo al almacenamiento de la app.
 */
abstract class DownloadVoskModel : DefaultTask() {
    @get:Input
    abstract val modelName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun download() {
        val zip = File(temporaryDir, "${modelName.get()}.zip")
        val url = URI("https://alphacephei.com/vosk/models/${modelName.get()}.zip").toURL()
        url.openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val modelDir = outputDir.dir("model-es").get().asFile
        files.delete { delete(outputDir) }
        files.copy {
            from(archives.zipTree(zip))
            into(modelDir)
            // El zip trae todo dentro de una carpeta con el nombre del modelo.
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
            includeEmptyDirs = false
        }
        File(modelDir, "uuid").writeText(modelName.get())
        zip.delete()
    }
}

val downloadVoskModel = tasks.register<DownloadVoskModel>("downloadVoskModel") {
    modelName.set("vosk-model-small-es-0.42")
    outputDir.set(layout.buildDirectory.dir("generated/voskModel"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadVoskModel, DownloadVoskModel::outputDir)
    }
}

android {
    namespace = "com.pps.cardboardpoc"
    // El .aar del Cardboard SDK se compila contra la API 35 y declara minSdk 26.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pps.cardboardpoc"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // El Cardboard SDK no se publica en ningún repositorio Maven: el .aar se compila desde
    // github.com/googlevr/cardboard y se copia acá a mano. Un .aar local no arrastra sus
    // dependencias, así que las cuatro que siguen van declaradas de más.
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.play.services.vision)
    implementation(libs.protobuf.javalite)

    // Pantalla virtual propia para el visor: Shizuku presta los permisos de adb para crearla como
    // "confiable", y la interfaz del DisplayManager que lo permite es oculta.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    // Cliente adb embebido: CeluVR se conecta al adbd local (depuración inalámbrica) y lanza el servidor
    // de la pantalla virtual sin depender de una computadora. sun-security-android arma el certificado adb.
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    // El TLS del emparejamiento usa exportKeyingMaterial, que el Conscrypt de la plataforma no expone
    // con la firma esperada en Android nuevo; con este Conscrypt propio, libadb lo detecta y lo usa.
    implementation(libs.conscrypt.android)

    // Reconocimiento de voz offline para los comandos de voz durante la sesión.
    implementation(libs.vosk.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}