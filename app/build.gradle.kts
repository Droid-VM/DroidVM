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

// Tolerant variant: returns null instead of throwing, for git calls that may
// legitimately fail (e.g. describe on a checkout with no tags).
fun runGitOrNull(vararg args: String): String? =
    runCatching { runGit(*args) }.getOrNull()

val gitCommitCount = runGit("rev-list", "--count", "HEAD").toInt()
val gitShortSha = runGit("rev-parse", "--short", "HEAD")

// describe fails on a checkout without tags (shallow clone, or tags not fetched);
// fall back to a 0.0.0 base so the build still works and the version is clearly
// marked as tag-less.
val gitDescribe = (runGitOrNull("describe", "--long", "--tags")
    ?: "0.0.0-$gitCommitCount-g$gitShortSha")
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
    packaging {
        jniLibs {
            // Extract native libs to a real on-disk dir so lbx (shipped as
            // liblbx.so) is an executable file the app can run from its own
            // nativeLibraryDir — no root, no daemon needed for a URL fetch.
            useLegacyPackaging = true
        }
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

// Ship lbx as `liblbx.so` in jniLibs so the (non-root) UI process can exec it
// from its nativeLibraryDir — the data-dir copy used by the daemon isn't
// executable by the app's untrusted_app SELinux domain.
abstract class CopyLbxJniLibsTask : DefaultTask() {
    @get:InputDirectory
    abstract val distDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val dist = distDir.get().asFile
        if (!dist.exists()) return
        dist.listFiles()?.filter { it.isDirectory }?.forEach { abiDir ->
            val src = File(abiDir, "lbx")
            if (src.isFile) {
                val dest = File(outDir, "${abiDir.name}/liblbx.so")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyLbxJniTask = tasks.register<CopyLbxJniLibsTask>(
            "copyLbxJniLibs${variantName}"
        ) {
            distDir.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/bin"))
            outputDir.set(
                layout.buildDirectory.dir("generated/lbx_jnilibs/${variant.name}")
            )
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            copyLbxJniTask, CopyLbxJniLibsTask::outputDir
        )
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
