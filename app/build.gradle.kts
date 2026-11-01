import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Firma de release (opcional) -------------------------------------------
// Igual que en Gear360App: variables de entorno (GitHub Actions) o un archivo
// local keystore.properties. Si no hay keystore, el release se firma con la
// clave de debug de esta maquina para que el APK igual se pueda instalar.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName) ?: keystoreProps.getProperty(propName)

val ksFile = signingValue("KEYSTORE_FILE", "storeFile")
val ksPassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("KEY_ALIAS", "keyAlias")
val ksKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasSigningConfig = ksFile != null && ksPassword != null &&
    ksAlias != null && ksKeyPassword != null

android {
    namespace = "cl.relojnoche"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.relojnoche"
        minSdk = 26
        targetSdk = 35
        val buildNumber = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionCode = buildNumber + 1
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                val ksPath = File(ksFile!!)
                storeFile = if (ksPath.isAbsolute) ksPath
                            else File(rootProject.projectDir, ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 + recorte de recursos: APK chico y animaciones mas fluidas.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "relojnoche-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Auto-actualización: consulta GitHub Releases y descarga el APK.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
