plugins {
    id("com.android.application")
}

// One per build that leaves this machine.
val appVersionCode = 1
val appVersionName = "0.0.1"

// A build made only to be tried on a phone: -Ptry=d1 gives 0.0.1d1.
val trial = (findProperty("try") as String?).orEmpty()

android {
    namespace = "org.foopgp.foomms"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.foopgp.foomms"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName + trial
    }
    buildFeatures {
        // The provider's door is an AIDL interface, which the Android plugin
        // does not compile unless asked.
        aidl = true
    }
    buildTypes {
        // An application of its own, which speaks to foodjis-dev: the two
        // trial applications sit on a phone beside the two published ones.
        getByName("debug") {
            applicationIdSuffix = ".dev"
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
