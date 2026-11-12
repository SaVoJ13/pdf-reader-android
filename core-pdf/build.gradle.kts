plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.savoj.volumpdf.core.pdf"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        minSdk = 28
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
        unitTests.isReturnDefaultValues = true
        unitTests.all { t ->
            // Важкі тести великих/пошкоджених файлів (пакет `large`) запускаються лише явно:
            //   ./gradlew --max-workers=1 :core-pdf:testDebugUnitTest -PlargeFiles --tests "*large.ClassName"
            // Купа: -PtestHeap=512m (за замовчуванням 512m). Звичайний :core-pdf:test їх не чіпає.
            if (project.hasProperty("largeFiles")) {
                t.maxHeapSize = (project.findProperty("testHeap") as String?) ?: "512m"
                t.testLogging {
                    events("passed", "failed", "skipped")
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
                t.filter.isFailOnNoMatchingTests = false
            } else {
                t.filter.excludeTestsMatching("*.large.*")
            }
        }
    }
}

dependencies {
    api("androidx.pdf:pdf-viewer-fragment:1.0.0-beta01")
    api("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.15.2")
}
