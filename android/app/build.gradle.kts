plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
}

// Load keystore credentials from keystore.properties (gitignored) with env-var
// fallback so CI/CD pipelines can inject secrets without a local file.
import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.khatabook"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.khatabook"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile") ?: System.getenv("STORE_FILE") ?: "../keystore.jks")
            storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("STORE_PASSWORD") ?: ""
            keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS") ?: ""
            keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    // The production API URL is never checked in. It must be supplied at build
    // time via -PapiBaseUrl=... or the API_BASE_URL env var, and it must be
    // HTTPS — the release network security config rejects cleartext outright,
    // so an http:// value would only fail at runtime instead of at build time.
    //
    // Read here (configuration time) but only *validated* in taskGraph.whenReady
    // below: buildTypes {} blocks are evaluated for every build type on every
    // invocation regardless of which task was requested, so throwing directly
    // inside release {} would fail `assembleDebug` too.
    val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: System.getenv("API_BASE_URL")

    buildTypes {
        debug {
            // Emulator host-loopback alias, reachable without any device-specific
            // setup. Real-device/LAN testing goes through the debug-only developer
            // settings dialog (MobileAuthScreen), never through checked-in config.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl ?: ""}\"")
        }
        // Signed like a real release (same keystore, minified) but points at a
        // plain-HTTP LAN backend for on-device manual QA before a real HTTPS
        // production URL exists. Intentionally a SEPARATE build type from
        // `release` rather than a weakened version of it: `release`'s
        // HTTPS-only guarantee (network_security_config.xml's blanket
        // cleartextTrafficPermitted="false", and the build-time check below)
        // is untouched, so this exists without softening what a true
        // production release enforces. Remove this build type once a real
        // HTTPS backend is available — it should not ship to real users.
        create("releaseLan") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            val lanApiBaseUrl = (project.findProperty("lanApiBaseUrl") as String?) ?: System.getenv("LAN_API_BASE_URL")
            buildConfigField("String", "API_BASE_URL", "\"${lanApiBaseUrl ?: ""}\"")
        }
    }

    gradle.taskGraph.whenReady {
        // Exactly "Release" tasks (assembleRelease, bundleRelease, ...) — not
        // "ReleaseLan", which intentionally carries a cleartext LAN URL and is
        // validated separately below.
        val buildingRelease = allTasks.any { it.project == project && Regex("Release$").containsMatchIn(it.name) }
        if (buildingRelease) {
            if (apiBaseUrl.isNullOrBlank()) {
                throw GradleException(
                    "Release builds require the production API URL. Pass -PapiBaseUrl=https://your-api-host/ " +
                        "or set the API_BASE_URL environment variable."
                )
            }
            if (!apiBaseUrl.startsWith("https://")) {
                throw GradleException(
                    "apiBaseUrl/API_BASE_URL must be an HTTPS URL for release builds, got: $apiBaseUrl"
                )
            }
        }

        val buildingReleaseLan = allTasks.any { it.project == project && it.name.contains("ReleaseLan") }
        if (buildingReleaseLan) {
            val lanApiBaseUrl = (project.findProperty("lanApiBaseUrl") as String?) ?: System.getenv("LAN_API_BASE_URL")
            if (lanApiBaseUrl.isNullOrBlank()) {
                throw GradleException(
                    "releaseLan builds require -PlanApiBaseUrl=http://<your-lan-ip>:3000/ or the LAN_API_BASE_URL env var."
                )
            }
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
      // Needed for BuildConfig.DEBUG, which gates HTTP body logging so bearer
      // tokens and OTP codes are never written to logcat in a release build.
      buildConfig = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
        unitTests.all {
            it.jvmArgs(
                "-XX:+EnableDynamicAgentLoading",
                "-Djdk.attach.allowAttachSelf=true",
                "-Dnet.bytebuddy.experimental=true"
            )
        }
        // Robolectric needs the merged manifest/resources to bootstrap its
        // shadow Android framework (Room's real SQLite implementation runs
        // through it) — off by default, and Room/plain-JUnit tests that
        // don't need it are unaffected either way.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Multidex support
  implementation("androidx.multidex:multidex:2.0.1")

  // Reads EXIF orientation so Scan Khata can correct sideways camera/gallery
  // photos before sending them to Gemini — camera JPEGs are frequently
  // stored "upright pixels + rotation flag" rather than pre-rotated.
  implementation("androidx.exifinterface:exifinterface:1.3.7")

  // Persistent local-first store: KhatabookRepository's primary working
  // database (see data/local/). PostgreSQL remains the authoritative cloud
  // copy; Room is the on-device source of truth for normal offline reads.
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.androidx.room.testing)

  // Core Android & Material dependencies
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.startup:startup-runtime:1.1.1")
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Security & Data
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("com.google.code.gson:gson:2.10.1")

  // Compose & Material Icons Extended
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Shadows the Android framework (SQLite included) so tests that build a
  // real, in-memory Room database can run as fast JVM unit tests instead of
  // requiring a device/emulator — see the databaseFactory overrides in
  // KhatabookRepositoryTest/CacheReconciliationTest/OutboxRollbackTest.
  testImplementation(libs.robolectric)
  // ApplicationProvider, used by InMemoryTestDatabase.kt to get a context
  // Room can build against under Robolectric.
  testImplementation(libs.androidx.test.core)
  // Compose UI testing under Robolectric, so interaction bugs in a screen
  // (a control that renders but does not respond) can be caught by the
  // normal test run rather than only by someone tapping it on a device.
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  implementation("io.coil-kt:coil-compose:2.5.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
  testImplementation("org.mockito:mockito-inline:5.2.0")

  // Networking (Retrofit & OkHttp)
  implementation("com.squareup.retrofit2:retrofit:2.9.0")
  implementation("com.squareup.retrofit2:converter-gson:2.9.0")
  implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

  // SMS User Consent API, for auto-filling the OTP screen without declaring
  // the RECEIVE_SMS/READ_SMS permission Play Store restricts heavily.
  implementation("com.google.android.gms:play-services-auth:21.3.0")
}