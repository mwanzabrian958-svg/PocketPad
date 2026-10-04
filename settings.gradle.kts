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

if (relocatedBuildRoot != null) {
    val buildRoot = relocatedBuildRoot
    gradle.allprojects {
        layout.buildDirectory.set(File(buildRoot, project.name))
    }
}
