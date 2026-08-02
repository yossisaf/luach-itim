import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

// ── Auto-incrementing version number ───────────────────────────────────────
// Reads composeApp/../version.properties, bumps the patch number by one on
// every Gradle configuration pass (e.g. 0.9.0 → 0.9.1 → 0.9.2 → ...), and
// writes it straight back. Used for both the Android app's versionName and
// the Desktop distributable's packageVersion, so every build you run — debug
// install or MSI/EXE package — gets its own unique, ever-increasing number.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) FileInputStream(versionPropsFile).use { load(it) }
}
val verMajor = versionProps.getProperty("versionMajor", "0").toInt()
val verMinor = versionProps.getProperty("versionMinor", "9").toInt()
val verPatchOld = versionProps.getProperty("versionPatch", "0").toInt()
val verPatchNew = verPatchOld + 1
versionProps.setProperty("versionMajor", verMajor.toString())
versionProps.setProperty("versionMinor", verMinor.toString())
versionProps.setProperty("versionPatch", verPatchNew.toString())
FileOutputStream(versionPropsFile).use { versionProps.store(it, "Auto-incremented app version — do not edit versionPatch manually") }

val appVersionName = "$verMajor.$verMinor.$verPatchNew"
val appVersionCode = verMajor * 100_000 + verMinor * 1_000 + verPatchNew

// ── Generated VersionInfo.kt (used by the About screen) ────────────────────
// Kotlin Multiplatform has no BuildConfig equivalent shared across targets, so
// the same appVersionName computed above is written into a tiny generated .kt
// file added to commonMain - Android and Desktop then both display one real,
// always-current version string instead of one hand-typed in the UI that can
// silently drift out of sync with the actual build.
val generatedVersionDir = layout.buildDirectory.dir("generated/versionInfo/kotlin")
val generateVersionInfo = tasks.register("generateVersionInfo") {
    val outputDirProvider = generatedVersionDir
    val versionNameForFile = appVersionName
    // Without this, Gradle has no declared input to compare between builds -
    // it sees the output directory already exists and skips the task as
    // "up-to-date" on every build after the first, so the generated file
    // kept whatever version string was in it from that very first build
    // forever after, even as appVersionName kept auto-incrementing above.
    inputs.property("versionName", versionNameForFile)
    outputs.dir(outputDirProvider)
    doLast {
        val dir = outputDirProvider.get().asFile.resolve("com/luachitim/util")
        dir.mkdirs()
        dir.resolve("VersionInfo.kt").writeText(
            """
            |package com.luachitim.util
            |
            |// Auto-generated at build time from version.properties - do not edit by hand.
            |object VersionInfo {
            |    const val VERSION_NAME = "$versionNameForFile"
            |}
            |""".trimMargin()
        )
    }
}
tasks.matching { it.name.contains("Kotlin", ignoreCase = false) && it.name.startsWith("compile") }
    .configureEach { dependsOn(generateVersionInfo) }

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        val jvmMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmMain)
        desktopMain.dependsOn(jvmMain)

        commonMain.get().kotlin.srcDir(generatedVersionDir)

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.pdfbox.android)
            implementation(libs.kosherjava)
            implementation(libs.coroutines.android)
            // Provides the Theme.Material3.DayNight.NoActionBar base theme used in
            // styles.xml (this is the app's actual root Android theme, unrelated to
            // Compose's own Material3 - it must stay even without the old picker).
            implementation("com.google.android.material:material:1.12.0")
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.pdfbox.desktop)
            implementation(libs.kosherjava)
        }
    }
}

android {
    namespace = "com.luachitim"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "com.luachitim"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    packaging {
        resources {
            // General META-INF conflicts
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            // BouncyCastle signature files (from pdfbox-android)
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/*.EC"
            // Duplicate service files
            excludes += "META-INF/services/javax.xml.parsers.DocumentBuilderFactory"
            excludes += "META-INF/services/javax.xml.parsers.SAXParserFactory"
            excludes += "META-INF/services/javax.xml.transform.TransformerFactory"
            // Other common conflicts
            excludes += "mozilla/public-suffix-list.txt"
            excludes += "org/apache/fontbox/**"
        }
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/*.so"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Renames the built APK from the generic "composeApp-debug.apk" to something
// that actually tells you what it is at a glance, e.g.
// "LuachItimLvina-1.4.7-debug.apk" / "LuachItimLvina-1.4.7-release.apk".
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("LuachItimLvina-$appVersionName-${variant.buildType}.apk")
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.luachitim.generated.resources"
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // Msi and Exe both show up under Gradle ▸ composeApp ▸ Tasks ▸ compose desktop
            // as packageMsi / packageExe — no extra wiring needed beyond listing them here.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // A Hebrew packageName was tried and confirmed to break packageExe/
            // packageMsi (jpackage/WiX's default codepage doesn't support
            // non-Latin-1 text here - see JDK-8290519). Reverted to this safe
            // ASCII identifier; the Hebrew "description" field below and the
            // window title already cover the visible Hebrew branding safely.
            packageName = "LuachItimLvina"
            packageVersion = appVersionName
            description = "Luach Itim LeBina - Hebrew calendar and weekly Torah portion"
            windows {
                menuGroup = "LuachItimLvina"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
        }
    }
}
