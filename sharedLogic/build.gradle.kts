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
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.okhttp.tls)
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

// The server repo owns the GraphQL schema (gqlgen is schema-first). Copy it here and commit the result:
//   ./gradlew :sharedLogic:syncServerSchema
// Override the server checkout location with -PpttServerDir=<path> (default: sibling push-to-talk-server-go).
val serverSchemaDir: File = rootProject.file(
    (findProperty("pttServerDir") as String? ?: "../push-to-talk-server-go") + "/graph/schema"
)

// Separate task because Copy skips its own actions (NO-SOURCE) when the source folder is missing.
val checkServerSchemaDir = tasks.register("checkServerSchemaDir") {
    val schemaDir = serverSchemaDir
    doLast {
        if (!schemaDir.isDirectory) {
            throw GradleException("Server schema not found at $schemaDir. Pass -PpttServerDir=<path to push-to-talk-server-go>.")
        }
    }
}

tasks.register<Copy>("syncServerSchema") {
    group = "apollo"
    description = "Copies the GraphQL schema from push-to-talk-server-go into src/commonMain/graphql."
    dependsOn(checkServerSchemaDir)

    from(serverSchemaDir) {
        include("*.graphql")
        // Apollo only treats *.graphqls as schema; *.graphql files are operations.
        rename { it.removeSuffix(".graphql") + ".graphqls" }
    }
    into(layout.projectDirectory.dir("src/commonMain/graphql"))
}
