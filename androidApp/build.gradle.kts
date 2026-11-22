import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)
    // Phase 3: woken by the server's pushes (FCM), and its notifications.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.core.telecom)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    // libsignal needs it (its AAR asks for core library desugaring).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

android {
    namespace = "com.chencohen.ptt"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // AGP strips the native libraries only with an installed NDK; libsignal_jni.so comes with its debug info
    // (130 MB per ABI, 10 MB stripped). This is the NDK on the Mac; CI's SDK installs it on demand.
    // Override with -PndkVersion=<installed version>.
    ndkVersion = findProperty("ndkVersion") as String? ?: "30.0.16248370"

    defaultConfig {
        applicationId = "com.chencohen.ptt"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = findProperty("versionName") as String? ?: "1.3.2"
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 14
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Firebase's settings from google-services.json, as the resources its own plugin would make (google_app_id
        // and the rest), so Firebase starts by itself without the plugin. They are public identifiers, not secrets.
        googleServices(file("google-services.json"), applicationId!!).forEach { (name, value) -> resValue("string", name, value) }
        // The phones (arm64) and the emulators (x86_64); libsignal and SQLCipher ship more.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // libsignal-client's desktop libraries (macOS, Windows, Linux), for the JVM only; Android uses the AAR's.
            excludes += listOf("**/*.dylib", "**/*.dll", "/libsignal_jni*.so")
        }
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
    }
    // The release key, from a keystore.properties outside git (storeFile, storePassword, keyAlias, keyPassword):
    // -PreleaseKeystore=<file>, else keystore.properties here, else ~/.talk-release/keystore.properties; or the
    // TALK_KEYSTORE, TALK_KEYSTORE_PASSWORD, TALK_KEY_ALIAS and TALK_KEY_PASSWORD environment variables. Without
    // one, the release build is unsigned (CI still builds it).
    val releaseKey = releaseKeystore()
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }
    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

/** The values the google-services plugin would put in resources, read from [json] for the app [packageName]. */
@Suppress("UNCHECKED_CAST")
fun googleServices(json: File, packageName: String): List<Pair<String, String>> {
    if (!json.exists()) return emptyList()
    val root = JsonSlurper().parse(json) as Map<String, Any>
    val project = root["project_info"] as Map<String, Any>
    val client = (root["client"] as List<Map<String, Any>>).first {
        ((it["client_info"] as Map<String, Any>)["android_client_info"] as Map<String, Any>)["package_name"] == packageName
    }
    val apiKey = ((client["api_key"] as List<Map<String, Any>>).first())["current_key"] as String
    return listOf(
        "google_app_id" to ((client["client_info"] as Map<String, Any>)["mobilesdk_app_id"] as String),
        "gcm_defaultSenderId" to (project["project_number"] as String),
        "google_api_key" to apiKey,
        "google_crash_reporting_api_key" to apiKey,
        "project_id" to (project["project_id"] as String),
        "google_storage_bucket" to (project["storage_bucket"] as String? ?: ""),
    )
}

/** The release key's settings, or null when this machine has none (see signingConfigs). */
fun releaseKeystore(): Map<String, String>? {
    System.getenv("TALK_KEYSTORE")?.let { store ->
        return mapOf(
            "storeFile" to store,
            "storePassword" to System.getenv("TALK_KEYSTORE_PASSWORD"),
            "keyAlias" to (System.getenv("TALK_KEY_ALIAS") ?: "talk"),
            "keyPassword" to (System.getenv("TALK_KEY_PASSWORD") ?: System.getenv("TALK_KEYSTORE_PASSWORD")),
        )
    }
    val candidates = listOfNotNull(
        (findProperty("releaseKeystore") as String?)?.let(::file),
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), ".talk-release/keystore.properties"),
    )
    val found = candidates.firstOrNull { it.isFile } ?: return null
    val props = Properties().apply { found.inputStream().use(::load) }
    return props.stringPropertyNames().associateWith { props.getProperty(it) }
}
