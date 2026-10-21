import java.util.Properties

plugins {
    id("com.android.application")
}

// One per build that leaves this machine.
val appVersionCode = 12
val appVersionName = "1.2.3"

// A build made only to be tried on a phone: -Ptry=d1 gives 0.0.1d1.
val trial = (findProperty("try") as String?).orEmpty()

// A trial build dressed as the published one, for the store's pictures:
// -Pdemo=1.0.0demo names it foomms, at that version.
val demo = (findProperty("demo") as String?).orEmpty()

android {
    namespace = "org.foopgp.foomms"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.foopgp.foomms"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = demo.ifEmpty { appVersionName + trial }
    }
    buildFeatures {
        // The provider's door is an AIDL interface, which the Android plugin
        // does not compile unless asked.
        aidl = true
        // The trial build's name is chosen here: see demo.
        resValues = true
    }
    // The upload key, if this machine holds one: `keystore.properties` beside
    // the sources, never committed, names it and carries its two passwords
    // (storeFile, storePassword, keyAlias, keyPassword), as foodjis's does.
    // foodjis's own: Play then signs both with keys of the same account, and
    // foodjis lets in, without asking, what is signed as it is.
    val distribution: Properties? =
        rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }
        }
    signingConfigs {
        distribution?.let { held ->
            create("distribution") {
                // Trimmed: a Properties value keeps the space that follows it.
                storeFile = rootProject.file(held.getProperty("storeFile").trim())
                storePassword = held.getProperty("storePassword")
                keyAlias = held.getProperty("keyAlias").trim()
                keyPassword = held.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // An application of its own, which speaks to foodjis-dev: the two
        // trial applications sit on a phone beside the two published ones.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            // It says so in its name, as foodjis-dev does.
            resValue("string", "app_name", if (demo.isEmpty()) "foomms-dev" else "foomms")
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Nothing but the platform: no cryptography lives here, foodjis does it, and
// an MMS is put together by hand.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
