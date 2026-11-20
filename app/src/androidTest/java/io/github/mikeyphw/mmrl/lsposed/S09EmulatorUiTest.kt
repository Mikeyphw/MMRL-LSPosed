package io.github.mikeyphw.mmrl.lsposed

import androidx.compose.ui.test.assertIsNotEnabled
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
        compose.onNodeWithTag("module_filter_toggle").performClick()
        compose.onNodeWithTag("modules_filter_all").assertExists()
        compose.onNodeWithTag("modules_filter_enabled").assertExists()
        compose.onNodeWithTag("modules_clear_search").performClick()
        compose.onNodeWithTag("modules_clear_search").assertDoesNotExist()
        compose.onNodeWithTag("modules_repository").assertExists()
        assertNoImplicitVectorConnection()
    }

    @Test fun navigationShowsAppsDiscoverAndDownloads() {
        compose.onNodeWithTag("nav_apps").performClick()
        compose.onNodeWithText("App targets").assertExists()
        compose.onNodeWithTag("apps_build_index").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Connect to Vector on Home first. No root read happens here automatically.").assertExists()
        compose.onNodeWithTag("nav_discover").performClick()
        compose.onNodeWithText("Discover modules").assertExists()
        compose.onNodeWithTag("discover_search").assertExists()
        compose.onNodeWithTag("discover_query").performTextInput("lsposed")
        compose.onNodeWithTag("discover_query").assertTextContains("lsposed")
        compose.onNodeWithTag("discover_clear_query").performClick()
        compose.onNodeWithTag("discover_clear_query").assertDoesNotExist()
        compose.onNodeWithTag("discover_sort").performClick()
        compose.onNodeWithTag("discover_sort_most_stars").performClick()
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithText("Verified downloads").assertExists()
        compose.onNodeWithTag("downloads_load").assertExists()
        compose.onNodeWithTag("downloads_query").performTextInput("manager.apk")
        compose.onNodeWithTag("downloads_clear_query").performClick()
        compose.onNodeWithText("CI artifacts").performClick()
        compose.onNodeWithTag("downloads_query").performTextInput("421428")
        compose.onNodeWithTag("downloads_clear_query").performClick()
        compose.onNodeWithText("Load recent GitHub Actions runs. Artifacts open on GitHub and may require sign-in.").assertExists()
        compose.onNodeWithText("Releases").performClick()
        compose.onNodeWithText("Load the official releases when you're ready.").assertExists()
        assertNoImplicitVectorConnection()
    }

    @Test fun settingsOpenAndCloseWithoutRoot() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("AMOLED").assertExists()
        compose.onNodeWithTag("auto_vector_connect").assertExists()
        compose.onNodeWithTag("github_token_input").assertExists()
        compose.onNodeWithTag("github_token_check").assertExists()
        compose.onNodeWithTag("nav_modules").assertDoesNotExist()
        // Persist UI selection without requesting Vector/root or changing its state.
        try {
            compose.onNodeWithText("AMOLED").performClick()
            compose.runOnUiThread {
                assertTrue("AMOLED preference must persist",
                    LocalSettings.appearance(compose.activity) == "AMOLED")
            }
        } finally {
            compose.runOnUiThread {
                LocalSettings.setAppearance(compose.activity, "SYSTEM")
            }
        }
        assertNoImplicitVectorConnection()
    }
}
