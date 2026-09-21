import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.config/servicetag/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// A signing config needs all four values. A partial properties file must fail to SIGN, not fail to
// CONFIGURE: with only some keys present the old `isNotEmpty()` guard built a release config whose
// storeFile was null, and the whole build died at configuration time.
val hasSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keystoreProps.getProperty(it)?.isNotBlank() == true }

// This app's identity, typed once. The namespace, the applicationId, the NFC Forum external-type
// domain and the Application Record all read it, so no two of them can be edited apart.
val appId = "com.loosecannon.servicetag"

// The single source of truth for this app's tag identity (C9, target §4.8). It produces the
// manifest filter path AND the BuildConfig fields the app builds its TagIdentity from, so the
// two cannot drift. android:path stays an EXACT match, never pathPrefix. The domain and the AAR
// package stay separate vals even though both read [appId]: one is an NFC Forum domain, the other
// an Android package name (C9).
val tagExternalDomain = appId   // NFC Forum external-type domain
val tagTypeName = "tag"
val tagAarPackage: String? = appId  // null would mean "no AAR" (O13/P21)

android {
    namespace = appId
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["ndefTagPath"] = "/$tagExternalDomain:$tagTypeName"
        buildConfigField("String", "NDEF_EXTERNAL_DOMAIN", "\"$tagExternalDomain\"")
        buildConfigField("String", "NDEF_TYPE_NAME", "\"$tagTypeName\"")
        buildConfigField("String", "NDEF_AAR_PACKAGE", tagAarPackage?.let { "\"$it\"" } ?: "null")
    }

    signingConfigs {
        if (hasSigningKeys) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningKeys) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    testOptions {
        // The write controller logs every folded cause with `android.util.Log.w` (R4). On the JVM
        // the mockable android.jar throws `RuntimeException("Stub!")` from every method unless the
        // stubs are told to return defaults, which would turn a logged cause into a lost state
        // update. No unit test here asserts on a stub throwing.
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.jvmArgs("--enable-native-access=ALL-UNNAMED") }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":nfc-android"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    // No sqlite-bundled on the production classpath: AndroidSQLiteDriver comes from
    // androidx.sqlite:sqlite-framework, which room3-runtime-android already pulls in, and the
    // platform ships SQLite anyway. Bundling it only added four .so files the app never calls.
    // The JVM tests are the exception and take sqlite-bundled-jvm below.
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room3.testing)
    testImplementation(libs.sqlite.bundled.jvm)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
