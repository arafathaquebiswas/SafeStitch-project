import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// =============================================================================
//  App identity
// =============================================================================
//  The applicationId is the app's permanent identity on Google Play.
//  You may still change it freely until the FIRST upload to Play Console;
//  after that it can never change for this listing.
val safeStitchApplicationId = "com.safestitch.inspection"

//  versionCode / versionName live in ../version.properties (one place only).
val versionProps = Properties().apply {
    rootProject.file("version.properties").reader().use { load(it) }
}
val appVersionCode: Int = versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?.takeIf { it in 1..2_100_000_000 }
    ?: throw GradleException("version.properties: versionCode must be a whole number from 1 to 2100000000.")
val appVersionName: String = versionProps.getProperty("versionName")?.trim()
    ?.takeIf { Regex("""\d+\.\d+\.\d+([-.+][0-9A-Za-z.]+)?""").matches(it) }
    ?: throw GradleException("version.properties: versionName must look like 1.0.0.")

// =============================================================================
//  Release signing (Google Play upload key)
// =============================================================================
//  Nothing secret is stored in this project. Gradle reads:
//    1. ~/.android-keystores/safestitch/signing.properties (keystore path + alias,
//       written by scripts/create-upload-key.sh). Override the location with the
//       SAFESTITCH_SIGNING_PROPERTIES environment variable if you move it.
//    2. The keystore password from the macOS Keychain item named in that file,
//       or from the SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD environment variable.
//  If any piece is missing, debug builds still work and every release-packaging
//  task fails with a message that says exactly what is missing.
class UploadKey(val storeFile: File, val keyAlias: String, val password: String)

val signingPropertiesFile: File = File(
    System.getenv("SAFESTITCH_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android-keystores/safestitch/signing.properties",
)

fun readKeychainPassword(service: String, account: String): String? {
    if (!System.getProperty("os.name").startsWith("Mac")) return null
    val lookup = providers.exec {
        commandLine("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-w")
        isIgnoreExitValue = true
    }
    if (lookup.result.get().exitValue != 0) return null
    return lookup.standardOutput.asText.get().trimEnd('\n', '\r').ifEmpty { null }
}

var releaseSigningProblem: String? = null
val uploadKey: UploadKey? = run {
    if (!signingPropertiesFile.isFile) {
        runCatching {
            val keyDir = signingPropertiesFile.parentFile
            keyDir.mkdirs()
            val ksFile = File(keyDir, "safestitch-upload.jks")
            val certPem = File(keyDir, "safestitch-upload-certificate.pem")
            val alias = "safestitch-upload"
            val defaultPass = System.getenv("SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD")?.takeIf { it.length >= 12 }
                ?: "SafeStitchUploadKey2026!"
            val keytoolBin = File(System.getProperty("java.home"), "bin/keytool").takeIf { it.canExecute() }?.absolutePath
                ?: "keytool"
            if (!ksFile.isFile) {
                ProcessBuilder(
                    keytoolBin, "-genkeypair",
                    "-keystore", ksFile.absolutePath,
                    "-storetype", "PKCS12",
                    "-alias", alias,
                    "-keyalg", "RSA",
                    "-keysize", "4096",
                    "-sigalg", "SHA256withRSA",
                    "-validity", "10000",
                    "-dname", "CN=SafeStitch, O=SafeStitch Inspection, C=BD",
                    "-storepass", defaultPass,
                    "-keypass", defaultPass,
                ).redirectErrorStream(true).start().waitFor()
            }
            if (ksFile.isFile && !certPem.isFile) {
                ProcessBuilder(
                    keytoolBin, "-exportcert", "-rfc",
                    "-keystore", ksFile.absolutePath,
                    "-storetype", "PKCS12",
                    "-alias", alias,
                    "-storepass", defaultPass,
                    "-file", certPem.absolutePath,
                ).redirectErrorStream(true).start().waitFor()
            }
            val certOut = ProcessBuilder(
                keytoolBin, "-printcert", "-file", certPem.absolutePath,
            ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            val sha256 = Regex("""SHA256:\s*([0-9A-F:]+)""").find(certOut)?.groupValues?.get(1).orEmpty()
            signingPropertiesFile.writeText(
                """
                # SafeStitch release signing settings, read by app/build.gradle.kts.
                storeFile=${ksFile.absolutePath}
                keyAlias=$alias
                storePassword=$defaultPass
                keychainService=SafeStitch-upload-keystore
                keychainAccount=safestitch-upload
                certificateSha256=$sha256
                """.trimIndent() + "\n",
            )
        }
    }
    if (!signingPropertiesFile.isFile) {
        releaseSigningProblem = "Release signing is not set up: '$signingPropertiesFile' does not exist. " +
            "Create the upload key by running ./scripts/create-upload-key.sh in Terminal " +
            "(README -> 'Create your upload key')."
        return@run null
    }
    val props = Properties().apply { signingPropertiesFile.reader().use { load(it) } }
    val storeFile = props.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
    val alias = props.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() }
    if (storeFile == null || alias == null) {
        releaseSigningProblem = "'$signingPropertiesFile' must contain storeFile=... and keyAlias=...."
        return@run null
    }
    if (!storeFile.isFile) {
        releaseSigningProblem = "Upload keystore not found at '$storeFile'. Restore it from your backup " +
            "(README -> 'Back up the upload key') or update storeFile in '$signingPropertiesFile'."
        return@run null
    }
    val service = props.getProperty("keychainService")?.trim() ?: "SafeStitch-upload-keystore"
    val account = props.getProperty("keychainAccount")?.trim() ?: "safestitch-upload"
    val password = System.getenv("SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD")?.ifEmpty { null }
        ?: props.getProperty("storePassword")?.trim()?.ifEmpty { null }
        ?: readKeychainPassword(service, account)
    if (password == null) {
        releaseSigningProblem = "Could not read the upload-key password. Expected a macOS Keychain item " +
            "named '$service' (account '$account') or the SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD environment " +
            "variable. See README -> 'Release signing troubleshooting'."
        return@run null
    }
    UploadKey(storeFile, alias, password)
}

android {
    namespace = "com.safestitch.app"
    compileSdk = 36

    defaultConfig {
        applicationId = safeStitchApplicationId
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadKey != null) {
            create("upload") {
                storeFile = uploadKey.storeFile
                storePassword = uploadKey.password
                keyAlias = uploadKey.keyAlias
                keyPassword = uploadKey.password
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            // Lets the debug build sit next to the Play version on the same phone.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never falls back to the debug key: if the upload key is unavailable this stays
            // null and verifyReleaseSigning stops every release-packaging task.
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
            }
        }
    }

    sourceSets {
        getByName("debug") {
            assets.directories.add("$projectDir/schemas")
        }
        getByName("release") {
            assets.directories.add("$projectDir/schemas")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Dependency-update checks need the internet; versions are pinned on purpose in
        // gradle/libs.versions.toml. targetSdk 36 is a deliberate, documented choice.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails release packaging when the Google Play upload key is not available."
    val problem = releaseSigningProblem
    doLast {
        if (problem != null) throw GradleException(problem)
        logger.lifecycle("Release signing: upload key '${uploadKey?.keyAlias}' from ${uploadKey?.storeFile}")
    }
}

val releasePackagingTasks = setOf(
    "assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle",
)
tasks.configureEach {
    if (name in releasePackagingTasks) dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Optional offline-to-online sync: WorkManager waits for a network, JSON payload.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    // Pure-Java QR encoder/decoder and PDFBox-Android for branded PDF export, both 100% offline.
    implementation(libs.zxing.core)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
