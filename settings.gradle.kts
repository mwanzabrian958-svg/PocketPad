pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketPad"
include(":app")

// Cloud-sync folders (OneDrive/Dropbox) turn build outputs into "online-only"
// placeholders that Gradle's file snapshotter rejects with "not a regular file",
// breaking :app:compileDebugUnitTestKotlin and transformDebugClassesWithAsm.
// Setting POCKETPAD_BUILD_DIR relocates all build output off the synced folder.
val relocatedBuildRoot: File? = providers
    .environmentVariable("POCKETPAD_BUILD_DIR")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?: run {
        val projectPath = settingsDir.absolutePath
        if (projectPath.contains("OneDrive", ignoreCase = true) ||
            projectPath.contains("Dropbox", ignoreCase = true) ||
            projectPath.contains("Google Drive", ignoreCase = true)) {
            val fallback = File(System.getProperty("java.io.tmpdir"), "PocketPadBuild")
            println("PocketPad: Cloud-sync folder detected. Automatically relocating build directory to: ${fallback.absolutePath}")
            fallback
        } else {
            null
        }
    }

if (relocatedBuildRoot != null) {
    val buildRoot = relocatedBuildRoot
    gradle.allprojects {
        layout.buildDirectory.set(File(buildRoot, project.name))
    }
}
