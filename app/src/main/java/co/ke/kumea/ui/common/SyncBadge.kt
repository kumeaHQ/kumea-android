package co.ke.kumea.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.ke.kumea.R
import co.ke.kumea.ui.theme.InkMuted
import co.ke.kumea.ui.theme.Teal

/**
 * The persistent row badge of the felt-state system (Build-3 v2 §8): neutral
 * "on phone" while a row waits to sync, teal ✓ "synced" once it has. Replaces
 * every "Saving…" — a completed local write is not a pending spinner.
 */
@Composable
fun SyncBadge(pending: Boolean, modifier: Modifier = Modifier) {
    val fg = if (pending) InkMuted else Teal
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(
                if (pending) R.drawable.ic_phone_saved else R.drawable.ic_cloud_check,
            ),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(if (pending) R.string.on_phone else R.string.synced),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}
