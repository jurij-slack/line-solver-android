package org.linesolver.base

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.ui.ApplicationInformation

class ApplicationInformationTest {
    @get:Rule val rule=createComposeRule()
    @Test fun configuredContactAndVersionAreDisplayed() {
        // Reserved invalid domain: UI fixture only, never packaged as developer contact.
        rule.setContent{MaterialTheme{ApplicationInformation("test-version","fixture@example.invalid","Test developer")}}
        rule.onNodeWithText("Line Solver").assertExists()
        rule.onNodeWithTag("app-version").assertTextEquals("Version test-version")
        rule.onNodeWithTag("developer-contact").assertTextEquals("fixture@example.invalid")
        rule.onNodeWithTag("developer-name").assertTextEquals("Test developer")
    }
}
