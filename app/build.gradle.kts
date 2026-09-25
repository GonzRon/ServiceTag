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
        // `targetSdk` stays 36 on purpose: `targetSdk 37` plus the `DISPATCH_NFC_MESSAGE`
        // permission on the dispatch activity is Phase 7 (spec §5.8), not 1.2.
        targetSdk = 36
        // 1.4.0 is a MINOR: new user-facing capability (operating seasons, maintenance service
        // policy and a maintenance break, operational condition and derived health), with a
        // forward-only backup-format bump — schema 8 / format 8, the new app reading every older
        // archive and 1.3.x refusing a format-8 archive rather than dropping rows
        // (docs/versioning.md). `versionCode` is +1 on every release and is never reset; it must
        // agree with the tag `servicetag-v1.4.0` or the release workflow refuses to publish.
        // VersionAgreementTest asserts these two against the schema and format numbers.
        versionCode = 16
        versionName = "1.4.0"
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
    sourceSets.getByName("androidTest").assets.srcDir("../core/src/test/resources/golden")
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
    // B06 (#21): the periodic backstop only. No `:core` dependency on it — the port it drives is
    // provider-neutral and knows nothing about a worker (invariant 48).
    implementation(libs.androidx.work.runtime.ktx)

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
    androidTestImplementation(libs.androidx.work.testing)
}

// B05 fix round 1, finding 4: ManifestContractTest reads the merged manifest for one fact (the
// exact-alarm and permission-set assertions), and a unit test must not depend on another task's
// output existing or being current to pass. Build wiring, not product code — the manifest-merger
// output path itself is untouched — so it stays inside this brief's scope even though this file
// is otherwise on the brief's Untouched list.
//
// `withType<Test>()`, not `tasks.named("testDebugUnitTest")` (B05 fix round 2, finding 24): the
// narrower wiring only covered the debug variant, so a plain `:app:test` — which also runs
// `testReleaseUnitTest` — hit MergedManifestContractTest's own "no merged manifest on disk"
// failure for a variant nothing gates on (CI and the release workflow both run
// `:app:testDebugUnitTest` specifically). Every unit-test task in this module reads the same
// debug-built manifest; none of this brief's assertions are variant-specific.
tasks.withType<Test>().configureEach {
    dependsOn("processDebugManifest")
}

// #62: the boundary proofs need a sharer with another UID on the device before any connected class
// runs, so the connected task installs the test-only sender first. Test wiring only: `:app` takes
// no dependency on the sender and no release task reaches it.
tasks.named { it == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":share-test-sender:installDebug")
}

// #62: ReleaseProofPolicyTest reads files outside this module (the scanned roots and the release
// runbook). They are declared as inputs of every unit-test task here, so a harness planted under
// one of them re-runs the test instead of being answered by an up-to-date or cached result.
tasks.withType<Test>().configureEach {
    val root = rootProject.layout.projectDirectory
    inputs.files(
        // The excludes mirror tools/*/.gitignore (and the root's build/), so the inputs are what
        // the test's `git ls-files --exclude-standard` scan reads: a bundle or schedules run that
        // writes private/, out/ or an archive neither re-runs this suite nor misses the cache.
        root.dir("tools").asFileTree.matching {
            exclude(
                "**/.venv/**", "**/__pycache__/**", "**/.pytest_cache/**", "**/*.pyc",
                "**/private/**", "**/out/**", "**/*.zip", "**/build/**",
            )
        },
        root.dir("share-test-sender").asFileTree.matching { exclude("build/**") },
        root.dir(".github").asFileTree,
        root.file("docs/release-proofs.md"),
        layout.projectDirectory.dir("src/androidTest").asFileTree,
        // VersionAgreementTest reads these too. Without them a README-only commit was answered by
        // the cached result: 966aa91 broke three of its cases while CI stayed green.
        root.file("README.md"),
        root.file("docs/versioning.md"),
        root.dir("docs/design").asFileTree,
    ).withPropertyName("releaseProofPolicyScope").withPathSensitivity(PathSensitivity.RELATIVE)
}
