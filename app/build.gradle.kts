plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "de.kanalfadenbeobachter"
    compileSdk = 36
    defaultConfig { applicationId = "de.kanalfadenbeobachter"; minSdk = 26; targetSdk = 36; versionCode = 7; versionName = "0.3.2" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    val schluesselpfad = providers.environmentVariable("KANALFADEN_SCHLUESSEL").orNull
    if (schluesselpfad != null) {
        signingConfigs.getByName("debug") {
            storeFile = file(schluesselpfad)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
