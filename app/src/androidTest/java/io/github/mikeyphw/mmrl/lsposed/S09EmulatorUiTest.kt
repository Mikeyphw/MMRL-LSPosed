package io.github.mikeyphw.mmrl.lsposed

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI-only UI instrumentation. It intentionally never invokes Connect, VectorMutation,
 * RootService, any Vector CLI, or the S08 physical-device acceptance classes.
 * No test in this class requires root, Vector, lab module, or real hooking.
 */
@RunWith(AndroidJUnit4::class)
class S09EmulatorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun assertNoImplicitVectorConnection() {
        compose.runOnUiThread {
            val session = ViewModelProvider(compose.activity)[VectorUiSession::class.java]
            assertTrue("Root connection must be explicit", session.vector is VectorState.Disconnected)
            assertTrue("No privileged write may run from rendering UI", !session.writeBusy)
        }
    }

    @Test fun homeStartsDisconnectedWithoutRoot() {
        compose.onNodeWithText("All your modules, one place.").assertExists()
        compose.onNodeWithText("Not connected").assertExists()
        compose.onNodeWithTag("vector_connect").assertExists()
        assertNoImplicitVectorConnection()
    }

    @Test fun browseModulesAndSearchAreUnprivileged() {
        compose.onNodeWithText("Browse modules").performClick()
        compose.onNodeWithText("Search modules").assertExists()
        compose.onNodeWithTag("module_search").performTextInput("ci-test-absent-module")
        compose.onNodeWithTag("module_search").assertTextContains("ci-test-absent-module")
        assertNoImplicitVectorConnection()
    }

    @Test fun navigationShowsAppsDiscoverAndDownloads() {
        compose.onNodeWithTag("nav_apps").performClick()
        compose.onNodeWithText("App targets").assertExists()
        compose.onNodeWithTag("nav_discover").performClick()
        compose.onNodeWithText("Discover modules").assertExists()
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithText("Future release and artifact downloads will show provenance, hashes and installation consent.").assertExists()
        assertNoImplicitVectorConnection()
    }

    @Test fun settingsOpenAndCloseWithoutRoot() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("AMOLED").assertExists()
        compose.onNodeWithTag("nav_modules").assertDoesNotExist()
        assertNoImplicitVectorConnection()
    }
}
