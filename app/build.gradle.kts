import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.firebase.crashlytics")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.room")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

fun signingValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.takeIf(String::isNotBlank)

val releaseStorePath = signingValue("ANITABI_STORE_FILE")
val releaseStorePassword = signingValue("ANITABI_STORE_PASSWORD")
val releaseKeyAlias = signingValue("ANITABI_KEY_ALIAS")
val releaseKeyPassword = signingValue("ANITABI_KEY_PASSWORD")
val navigationApiKey = (
    signingValue("ANITABI_NAVIGATION_API_KEY")
        ?: localProperties.getProperty("ANITABI_NAVIGATION_API_KEY")
    ).orEmpty()
val amapApiKey = (
    signingValue("ANITABI_AMAP_API_KEY")
        ?: localProperties.getProperty("ANITABI_AMAP_API_KEY")
    ).orEmpty()
val amapApiKeyConfigured = amapApiKey.isNotBlank()
val amapSdkArtifact = "com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0"
val releaseSigningValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningReady = releaseSigningValues.all { it != null }
if (releaseSigningValues.any { it != null } && !releaseSigningReady) {
    throw GradleException("Release signing requires all four ANITABI_* signing values")
}
val releaseStoreFile = releaseStorePath?.let(::file)?.canonicalFile
if (releaseStoreFile != null) {
    require(!releaseStoreFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "The release keystore must be stored outside the project workspace"
    }
}

android {
    namespace = "cn.anitabi.navigator"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.anitabi.navigator"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.2.5"

        testInstrumentationRunner = "cn.anitabi.navigator.TestAnitabiRunner"
        manifestPlaceholders["NAVIGATION_API_KEY"] = navigationApiKey
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey.ifBlank { "ANITABI_AMAP_KEY_MISSING" }
        buildConfigField("boolean", "AMAP_API_KEY_CONFIGURED", amapApiKeyConfigured.toString())
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

gradle.taskGraph.whenReady {
    val requestsReleaseArtifact = allTasks.any { task ->
        task.project == project && task.name in setOf("assembleRelease", "bundleRelease", "packageRelease")
    }
    if (requestsReleaseArtifact && !releaseSigningReady) {
        throw GradleException(
            "Release signing is not configured. Keep the keystore outside the workspace and set ANITABI_* values.",
        )
    }
    if (requestsReleaseArtifact && navigationApiKey.isBlank()) {
        throw GradleException(
            "Release builds require ANITABI_NAVIGATION_API_KEY from an ignored local property or environment variable.",
        )
    }
    if (requestsReleaseArtifact && !amapApiKeyConfigured) {
        throw GradleException(
            "Release builds require ANITABI_AMAP_API_KEY from an ignored local property or environment variable.",
        )
    }
    if (
        requestsReleaseArtifact &&
        !rootProject.file("app/src/main/assets/approved_regions/territory_regions_v1.json").isFile
    ) {
        throw GradleException("Release builds require the approved territory region asset and review evidence.")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.16.0")

    implementation(composeBom)
    implementation(firebaseBom)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.google.android.libraries.navigation:navigation:7.8.0")
    implementation(amapSdkArtifact)
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

room {
    schemaDirectory("$projectDir/schemas")
}
