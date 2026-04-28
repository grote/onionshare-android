import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Date

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

val hiltVersion = "2.59.2"
val composeVersion = "2026.04.01"
val tor by configurations.creating

fun versionCodeEpoch(): Int = (Date().time / 1000).toInt()

fun gitCommit(): String {
    val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.waitFor()
    return process.inputStream.use { it.readBytes().decodeToString().trim() }
}

android {
    namespace = "org.onionshare.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.onionshare.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 26
        versionName = "0.3.0-beta"

        vectorDrawables {
            useSupportLibrary = true
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "releaseType"

    productFlavors {
        create("stable") {
            dimension = "releaseType"
        }
        create("fdroid") {
            dimension = "releaseType"
            applicationIdSuffix = ".fdroid"
            // version codes get multiplied by 10 and an ABI suffix gets added to the code
            // if 'splitApk' property is set
        }
        create("nightly") {
            dimension = "releaseType"
            applicationIdSuffix = ".nightly"
            versionCode = versionCodeEpoch()
            versionNameSuffix = " (${gitCommit()})"
        }
    }

    splits {
        abi {
            // can not be defined per flavor, so we use a property to turn this on for F-Droid
            isEnable = project.hasProperty("splitApk")
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*",
                // Due to https://github.com/Kotlin/kotlinx.coroutines/issues/2023
                "META-INF/licenses/*",
                "**/attach_hotspot_windows.dll",
            )
        }
    }

    lint {
        disable += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(kotlin("reflect"))

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.android.material:material:1.13.0")

    implementation(platform("androidx.compose:compose-bom:$composeVersion"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")

    // Tor
    implementation("org.briarproject:onionwrapper-android:0.1.4")
    add(tor.name, "org.briarproject:tor-android:0.4.8.22")
    add(tor.name, "org.briarproject:lyrebird-android:0.6.2")
    implementation("org.briarproject:moat-api:0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val ktorVersion = "2.3.13"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-pebble:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:jul-to-slf4j:2.0.17")
    implementation("com.github.tony19:logback-android:3.0.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.slf4j:slf4j-jdk14:2.0.17")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.0")
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
}

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val baseAbiVersionCode = abiCodes[abi]
            if (baseAbiVersionCode != null) {
                output.versionCode.set(output.versionCode.map { 10 * it + baseAbiVersionCode })
            }
        }
    }
}

val torLibsDir = "src/main/jniLibs"

val cleanTorBinaries = tasks.register("cleanTorBinaries") {
    outputs.dir(torLibsDir)
    doLast {
        project.delete(project.fileTree(torLibsDir))
    }
}

tasks.named("clean") {
    dependsOn(cleanTorBinaries)
}

val unpackTorBinaries = tasks.register("unpackTorBinaries") {
    outputs.dir(torLibsDir)
    dependsOn(cleanTorBinaries)
    doLast {
        project.copy {
            from(tor.resolve().map { project.zipTree(it) })
            into(torLibsDir)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(unpackTorBinaries)
}

apply(from = "${rootProject.rootDir}/gradle/ktlint.gradle")
