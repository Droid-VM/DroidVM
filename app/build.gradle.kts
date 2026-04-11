@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
}

fun runGit(vararg args: String): String {
    require(rootDir.resolve(".git").exists()) { "Not a git repository: $rootDir" }
    val proc = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    val exitCode = proc.waitFor()
    require(exitCode == 0) { "git ${args.joinToString(" ")} failed with exit code $exitCode" }
    return output
}

val gitCommitCount = runGit("rev-list", "--count", "HEAD").toInt()
val gitShortSha = runGit("rev-parse", "--short", "HEAD")

val gitDescribe = runGit("describe", "--long", "--tags")
    .removePrefix("v").removePrefix("V")
val generatedVersionName: String = if (gitDescribe.matches(Regex(".*-0-g[0-9a-f]+$"))) {
    gitDescribe.replace(Regex("-0-g[0-9a-f]+$"), "")
} else {
    gitDescribe
        .replace(Regex("([^-]*-g)"), "r\$1")
        .replace("-", ".")
}

val generatedVersionCode: Int = gitCommitCount * 10

println("Version name: $generatedVersionName")
println("Version code: $generatedVersionCode")

android {
    namespace = "cn.classfun.droidvm"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "cn.classfun.droidvm"
        minSdk = 33
        targetSdk = 36
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DDROIDVM_VERSION=${versionName}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        aidl = true
        buildConfig = true
    }
}

abstract class CopyNativeBinAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val cmakeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val cmakeDir = cmakeOutputDir.get().asFile
        if (!cmakeDir.exists()) return
        val binaries = setOf("droidvm", "daemon")
        cmakeDir.walkTopDown()
            .filter { it.name in binaries && it.isFile }
            .forEach { src ->
                val abi = src.parentFile.name
                val dest = File(outDir, "bin/$abi/${src.name}")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
        val libraries = setOf("libsimpledump.so", "libunixhelper.so")
        cmakeDir.walkTopDown()
            .filter { it.name in libraries && it.isFile }
            .forEach { src ->
                val abi = src.parentFile.name
                val dest = File(outDir, "lib/$abi/${src.name}")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyNativeTask = tasks.register<CopyNativeBinAssetsTask>(
            "copyNativeBinAssets${variantName}"
        ) {
            dependsOn("externalNativeBuild${variantName}")
            cmakeOutputDir.set(
                layout.buildDirectory.dir(
                    "intermediates/cmake/${variant.name}/obj"
                )
            )
            outputDir.set(
                layout.buildDirectory.dir(
                    "generated/droidvm_assets/${variant.name}"
                )
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyNativeTask, CopyNativeBinAssetsTask::outputDir
        )
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.annotation.jvm)
    implementation(libs.appcompat)
    implementation(libs.auto.service.annotations)
    implementation(libs.constraintlayout)
    implementation(libs.libsu.core)
    implementation(libs.libsu.nio)
    implementation(libs.libsu.service)
    implementation(libs.material)
    implementation(libs.okhttp3)
    implementation(libs.snakeyaml)
    implementation(libs.termux.emulator)
    implementation(libs.termux.view)
    testImplementation(libs.junit)
    annotationProcessor(libs.auto.service)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
