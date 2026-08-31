package com.snapper.android.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapper.android.R
import com.snapper.android.ui.CardDivider
import com.snapper.android.ui.NavigationCard
import com.snapper.android.ui.ScreenHeader
import com.snapper.android.ui.SectionHeader
import com.snapper.android.ui.SettingsCard
import com.snapper.android.ui.theme.SnapperTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnapperTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

private const val ORIGINAL_POST_URL = "https://x.com/jontelang/status/1673474541736042496"
private const val ORIGINAL_DEVELOPER_URL = "https://x.com/jontelang"

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { versionLabel(context) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 40.dp),
        ) {
            ScreenHeader(
                title = "About Snapper",
                onBack = onBack,
            )


            SectionHeader(title = "Build")
            SettingsCard {
                InfoRow("Snapper for Android", version)
            }


            SectionHeader(title = "Original Snapper 3")
            SettingsCard {
                InfoRow(
                    stringResource(R.string.about_credits),
                    "Original Snapper 3 was created by @jontelang. This Android build is an " +
                        "independent compatibility project and is not affiliated with or " +
                        "endorsed by the original developer.",
                )
            }

            NavigationCard(
                title = "Original Snapper 3 demo",
                modifier = Modifier.padding(top = 10.dp),
                minHeight = 70.dp,
                verticalPadding = 10.dp,
                accessibilityLabel = "Original Snapper 3 demo. Opens an external link.",
            ) { openExternal(context, ORIGINAL_POST_URL) }
            NavigationCard(
                title = "Original developer · @jontelang",
                modifier = Modifier.padding(top = 10.dp),
                minHeight = 70.dp,
                verticalPadding = 10.dp,
                accessibilityLabel = "Original developer · @jontelang. Opens an external link.",
            ) { openExternal(context, ORIGINAL_DEVELOPER_URL) }

        }
    }
}

@Composable
private fun InfoRow(title: String, detail: String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private fun versionLabel(context: Context): String {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
    )
    val versionName = requireNotNull(info.versionName) { "Application versionName is missing" }
    require(versionName.isNotBlank()) { "Application versionName is blank" }
    return context.getString(R.string.about_version) + " " + versionName +
        " · build " + info.longVersionCode
}

private fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (noHandler: ActivityNotFoundException) {
        Toast.makeText(context, "No app is available to open this link", Toast.LENGTH_LONG).show()
    }
}
