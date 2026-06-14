import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mukapp.mote"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mukapp.mote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libbusybox.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.blurview)
    implementation(libs.prism4j.core) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.flexmark.html2md.converter)
    implementation(libs.ratex.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

androidComponents {
    onVariants { variant ->
        val capitalizedVariantName = variant.name.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
        val bindingSourceDir = layout.buildDirectory.dir(
            "generated/data_binding_base_class_source_out/${variant.name}/out"
        )
        tasks.withType<JavaCompile>().configureEach {
            if (name == "compile${capitalizedVariantName}JavaWithJavac") {
                dependsOn("dataBindingGenBaseClasses$capitalizedVariantName")
                source(bindingSourceDir)
                outputs.cacheIf { false }
            }
        }
    }
}
