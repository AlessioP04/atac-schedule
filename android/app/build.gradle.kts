import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Chiave di firma della release: dalle variabili d'ambiente (CI, vedi .github/workflows/release.yml)
 * oppure da android/keystore.properties (build locali, file ignorato da git). Senza, si usa la chiave di debug.
 */
val releaseKey: Map<String, String>? = run {
    val env = System.getenv()
    if (env["ANDROID_KEYSTORE_FILE"] != null) {
        return@run mapOf(
            "storeFile" to env.getValue("ANDROID_KEYSTORE_FILE"),
            "storePassword" to env.getValue("ANDROID_KEYSTORE_PASSWORD"),
            "keyAlias" to env.getValue("ANDROID_KEY_ALIAS"),
        )
    }
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return@run null
    val props = Properties().apply { file.inputStream().use(::load) }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

/** Versione: dal tag (es. -PappVersion=0.2.0 nel workflow di release), altrimenti quella di sviluppo. */
val appVersion = (findProperty("appVersion") as String?) ?: "0.2.0"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "io.github.alessiop04.atacschedule"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.alessiop04.atacschedule"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey["keyPassword"] ?: releaseKey.getValue("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "gzip" // già compresso
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

/**
 * Inserisce nell'APK il database compresso prodotto dalla pipeline (../out), se presente:
 * l'app lo decomprime al primo avvio.
 * Senza database l'app funziona lo stesso: al primo avvio lo scarica dalla release "db" su GitHub.
 */
abstract class BundleTimetableTask : DefaultTask() {
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val database: RegularFileProperty

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val db = database.orNull?.asFile
        if (db == null || !db.exists()) {
            logger.warn("timetable.db.gz non trovato: l'APK non conterrà orari (lancia pipeline/build_db.py)")
            return
        }
        // non ".gz": AGP decomprime da solo gli asset .gz durante il merge
        db.copyTo(out.resolve("timetable.db.gzip"))
        manifest.orNull?.asFile?.takeIf { it.exists() }?.copyTo(out.resolve("timetable.json"))
    }
}

val bundleTimetable = tasks.register<BundleTimetableTask>("bundleTimetable") {
    val outDir = rootProject.layout.projectDirectory.dir("../out")
    database.set(outDir.file("timetable.db.gz").asFile.takeIf { it.exists() }?.let { outDir.file("timetable.db.gz") })
    manifest.set(outDir.file("manifest.json").asFile.takeIf { it.exists() }?.let { outDir.file("manifest.json") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleTimetable, BundleTimetableTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
