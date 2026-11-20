plugins {
    id("com.android.application")
    id("kotlin-android")
    alias(libs.plugins.google.ksp)
    id("kotlin-parcelize")
    id("jacoco")
}

android {
    compileSdk = 35

    defaultConfig {
        namespace = "com.parishod.watomatic"
        applicationId = "com.parishod.watomatic"
        minSdk = 24
        targetSdk = 35
        versionCode = 36
        versionName = "1.36"

        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.schemaLocation", "$projectDir/schemas")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2048m"
                it.forkEvery = 8
            }
        }
    }

    //Disable split language resources on .aab, necessary to allow the language changing
    //option to work
    bundle {
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugConfig")
            enableUnitTestCoverage = true
        }
        getByName("release") {
            // Enables code shrinking, obfuscation, and optimization for only
            // your project's release build type.
            isMinifyEnabled = true

            // Enables resource shrinking, which is performed by the
            // Android Gradle plugin.
            isShrinkResources = true

            // Includes the default ProGuard rules files that are packaged with
            // the Android Gradle plugin. To learn more, go to the section about
            // R8 configuration files.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime)
    implementation(libs.activity)
    implementation(libs.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    ksp(libs.room.compiler)
    implementation(libs.core.ktx)
    implementation(libs.gson)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.converter.scalars)
    implementation(libs.logging.interceptor)
    implementation(libs.sequence.layout)
    implementation(libs.browser)
    implementation(libs.security.crypto)
    implementation(libs.billing)
    implementation(libs.play.services.auth)
}

// JaCoCo coverage verification – enforces minimum thresholds
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")
    group = "Verification"
    description = "Enforces minimum JaCoCo coverage thresholds for model/utility classes."

    val excludes = listOf(
        "**/R.class", "**/R\$*.class",
        "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "android/**/*.*",
        "**/databinding/**", "**/*_MembersInjector.class",
        "**/*_Factory.class", "**/*Directions*.*",
        "**/*\$\$serializer.class",
        // Exclude UI classes that cannot be unit-tested
        "**/activity/**", "**/fragment/**",
        "**/service/NotificationService.*", "**/service/ReplyService.*",
        "**/model/adapters/**",
        "**/billing/**", "**/backend/**",
        "**/viewmodel/**"
    )

    val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
        exclude(excludes)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(excludes)
    }

    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) {
            include("**/*.exec", "**/*.ec")
        }
    )

    violationRules {
        rule {
            limit {
                // Minimum instruction coverage for testable model/utility classes.
                // Note: Robolectric tests do not contribute to JaCoCo offline
                // instrumentation, so the reported number is lower than actual
                // coverage. This threshold catches major regressions.
                minimum = "0.05".toBigDecimal()
            }
        }
    }
}

// JaCoCo unit test coverage report for the Debug variant
tasks.register<JacocoReport>("jacocoUnitTestReport") {
    dependsOn("testDebugUnitTest")
    group = "Reporting"
    description = "Generates JaCoCo unit test coverage report for Debug variant."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val excludes = listOf(
        "**/R.class", "**/R\$*.class",
        "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "android/**/*.*",
        "**/databinding/**", "**/*_MembersInjector.class",
        "**/*_Factory.class", "**/*Directions*.*",
        "**/*\$\$serializer.class"
    )

    val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
        exclude(excludes)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(excludes)
    }

    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) {
            include("**/*.exec", "**/*.ec")
        }
    )
}