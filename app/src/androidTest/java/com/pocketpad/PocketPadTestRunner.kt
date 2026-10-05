package com.pocketpad

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that boots [HiltTestApplication] instead of the production
 * [PocketPadApplication]. Referenced by `testInstrumentationRunner` in app/build.gradle.kts.
 *
 * In this build the Hilt Gradle plugin's androidTest transform does not swap the application class
 * and an androidTest manifest override does not reach the app-under-test process, so
 * @HiltAndroidTest classes otherwise fail with "cannot use a @HiltAndroidApp application but found
 * com.pocketpad.PocketPadApplication".
 *
 * HiltTestApplication is final, so it is instantiated directly rather than subclassed. The runner
 * remains an Instrumentation subclass; only the Application instance is swapped.
 */
class PocketPadTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}