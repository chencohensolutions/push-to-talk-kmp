import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.apollo)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // iOS comes after phase 6 (docs/design.md). The targets stay so common code keeps to what Kotlin/Native can
    // compile; iosMain holds only the actuals common code expects. They are skipped on Linux and Windows.
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }

    android {
       namespace = "com.chencohen.ptt.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // UDP for the DHT lookup (discovery/DhtClient.kt).
            implementation(libs.ktor.network)
            implementation(libs.apollo.runtime)
            implementation(libs.apollo.engine.ktor)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.okhttp.tls)
            // CryptoEngine's Android implementation; the host tests run it on libsignal-client's desktop library.
            implementation(libs.libsignal.android)
            // The encrypted store (store/SqlCipherStore.kt).
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
            // The live talk path (talk/LiveKitTransport.kt) and calls: LiveKit's client over the server's /rtc.
            api(libs.livekit.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

apollo {
    service("ptt") {
        // Schema copied from push-to-talk-server-go/graph/schema (./gradlew :sharedLogic:syncServerSchema);
        // operations live next to it.
        srcDir("src/commonMain/graphql")
        packageName.set("com.chencohen.ptt.graphql")
        // RFC 3339 timestamps from gqlgen's Time scalar.
        mapScalarToKotlinString("Time")
        // Keep generated models out of the public API (and the iOS framework header).
        generateAsInternal.set(true)
    }
}

// The server repo owns the GraphQL schema (gqlgen is schema-first) and the /ws frames (proto/ptt.proto). Copy them
// here and commit the result:
//   ./gradlew :sharedLogic:syncServerSchema
// Override the server checkout location with -PpttServerDir=<path> (default: sibling push-to-talk-server-go).
val serverDir: String = findProperty("pttServerDir") as String? ?: "../push-to-talk-server-go"
val serverSchemaDir: File = rootProject.file("$serverDir/graph/schema")
val serverProto: File = rootProject.file("$serverDir/proto/ptt.proto")

// Separate task because Copy skips its own actions (NO-SOURCE) when the source folder is missing.
val checkServerSchemaDir = tasks.register("checkServerSchemaDir") {
    val schemaDir = serverSchemaDir
    doLast {
        if (!schemaDir.isDirectory) {
            throw GradleException("Server schema not found at $schemaDir. Pass -PpttServerDir=<path to push-to-talk-server-go>.")
        }
    }
}

// The frames are decoded by hand-written classes (relay/Frames.kt, FramesTest checks them against the server's
// encoding); the .proto file is kept beside them as the reference.
val syncServerProto = tasks.register<Copy>("syncServerProto") {
    description = "Copies proto/ptt.proto from push-to-talk-server-go into src/commonMain/proto."
    dependsOn(checkServerSchemaDir)
    from(serverProto)
    into(layout.projectDirectory.dir("src/commonMain/proto"))
}

tasks.register<Copy>("syncServerSchema") {
    group = "apollo"
    description = "Copies the GraphQL schema (and proto/ptt.proto) from push-to-talk-server-go into src/commonMain."
    dependsOn(checkServerSchemaDir, syncServerProto)

    from(serverSchemaDir) {
        include("*.graphql")
        // Apollo only treats *.graphqls as schema; *.graphql files are operations.
        rename { it.removeSuffix(".graphql") + ".graphqls" }
    }
    into(layout.projectDirectory.dir("src/commonMain/graphql"))
}

// Each test's name and result in the build's output, so CI's log shows that the libsignal tests ran.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
