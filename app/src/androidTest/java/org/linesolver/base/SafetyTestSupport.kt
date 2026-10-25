package org.linesolver.base

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Existing regression workflows must enter through the real acknowledgment UI. */
fun ComposeContentTestRule.acceptSafetyIfShown() {
    if(onAllNodesWithTag("accept-safety").fetchSemanticsNodes().isNotEmpty())onNodeWithTag("accept-safety").performClick()
}
