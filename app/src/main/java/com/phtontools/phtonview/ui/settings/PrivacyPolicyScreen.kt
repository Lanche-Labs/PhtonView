package com.phtontools.phtonview.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrivacyPolicyPage(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PhtonView Privacy Policy",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Last updated: 2026-07-02",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        PolicySection(title = "1. Information We Collect") {
            PolicyParagraph(
                "PhtonView is designed to keep your data on your device. We do not collect personal information such as your name, email address, photos, or camera contents."
            )
            PolicyParagraph(
                "If you choose to enable the User Experience Improvement Program, the app will collect anonymous technical logs from the moment you open the app until you close it. These logs may include:"
            )
            PolicyBullet("App version and build number")
            PolicyBullet("Device model and Android version")
            PolicyBullet("USB/PTP communication events and error messages")
            PolicyBullet("Feature usage events (e.g. live view start/stop)")
        }

        PolicySection(title = "2. How We Use Information") {
            PolicyParagraph(
                "The anonymous logs collected through the User Experience Improvement Program are used solely to diagnose compatibility issues, improve stability, and guide future development of PhtonView."
            )
            PolicyParagraph(
                "Logs are submitted automatically as GitHub issues and are publicly visible. They do not contain your photos, personal files, or camera serial numbers."
            )
        }

        PolicySection(title = "3. Your Choices") {
            PolicyParagraph(
                "The User Experience Improvement Program is opt-in. You can agree or decline when the app first launches, and you can enable or disable it at any time in Settings > General > User Experience Improvement Program."
            )
            PolicyParagraph(
                "When the feature is disabled, no logs are collected or transmitted."
            )
        }

        PolicySection(title = "4. Data Sharing") {
            PolicyParagraph(
                "Anonymous logs are submitted to GitHub via the GitHub Issues API. Please refer to GitHub's Privacy Policy for information on how GitHub handles data."
            )
            PolicyParagraph(
                "We do not sell, rent, or share your personal data with third parties."
            )
        }

        PolicySection(title = "5. Security") {
            PolicyParagraph(
                "Log transmission uses HTTPS. Because the logs are submitted as public GitHub issues, please do not enable the program if your use case requires complete confidentiality of technical events."
            )
        }

        PolicySection(title = "6. Changes to This Policy") {
            PolicyParagraph(
                "We may update this Privacy Policy from time to time. Changes will be posted within the app and, where appropriate, notified to you."
            )
        }

        PolicySection(title = "7. Contact") {
            PolicyParagraph(
                "If you have any questions about this Privacy Policy, please open an issue on the PhtonView GitHub repository."
            )
        }
    }
}

@Composable
private fun PolicySection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        content()
    }
}

@Composable
private fun PolicyParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        lineHeight = 22.sp
    )
}

@Composable
private fun PolicyBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        lineHeight = 22.sp,
        modifier = Modifier.padding(start = 12.dp)
    )
}
