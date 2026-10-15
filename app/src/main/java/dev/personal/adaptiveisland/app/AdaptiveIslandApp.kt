package dev.personal.adaptiveisland.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.personal.adaptiveisland.capabilities.DeviceProfile

@Composable
fun AdaptiveIslandApp(
    profile: DeviceProfile,
    viewModel: FeatureControlViewModel,
) {
    val features by viewModel.features.collectAsState()
    val topGapDp by viewModel.topGapDp.collectAsState()
    val pillHeightDp by viewModel.pillHeightDp.collectAsState()
    val idleWidthDp by viewModel.idleWidthDp.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageName = context.packageName

    FeatureControlScreen(
        profile = profile,
        features = features,
        topGapDp = topGapDp,
        pillHeightDp = pillHeightDp,
        idleWidthDp = idleWidthDp,
        onToggle = viewModel::setEnabled,
        onOwnership = viewModel::setOwnership,
        onSetTopGapDp = viewModel::setTopGapDp,
        onSetPillHeightDp = viewModel::setPillHeightDp,
        onSetIdleWidthDp = viewModel::setIdleWidthDp,
        onResetGeometry = viewModel::resetGeometry,
        onRequestOverlay = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            context.startActivity(intent)
        },
    )
}
