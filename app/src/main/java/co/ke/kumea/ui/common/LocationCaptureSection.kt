package co.ke.kumea.ui.common

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.location.LocationCapturer
import co.ke.kumea.data.location.LocationFix
import kotlin.math.abs

/**
 * "Where is this shamba?", as words rather than a map (KWAP-03 §5.1).
 *
 * NO MAP, AND THAT IS A DECISION RATHER THAN A SHORTCUT. Tiles need network and
 * offline-first is non-negotiable, so a map would be a blank grey square exactly
 * where the app is used. A draggable pin is arguably worse than nothing: it
 * invites a confident wrong answer, where a visible accuracy figure invites a
 * second attempt.
 *
 * Shared by both registration flows so they cannot drift apart.
 */
@Composable
fun LocationCaptureSection(
    controller: LocationCaptureController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        // shouldShowRequestRationale goes false once the user has chosen "don't
        // ask again" — the difference between "not now" and "never", which
        // deserve different words and different options.
        val canAskAgain = activity != null && LocationCapturer.PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        controller.onPermissionResult(granted = granted, canAskAgain = canAskAgain)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.location_label), style = MaterialTheme.typography.labelLarge)

        when (state.status) {
            LocationStatus.IDLE -> {
                // The rationale sits ABOVE the button, in the farmer's terms,
                // rather than inside a system dialog nobody reads.
                Text(
                    stringResource(R.string.location_rationale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        if (controller.needsPermission()) {
                            permissionLauncher.launch(LocationCapturer.PERMISSIONS)
                        } else {
                            controller.start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.use_location))
                }
            }

            LocationStatus.SEARCHING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.location_searching),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // The live accuracy IS the progress bar. ±48 → ±19 → ±8
                        // is legibly "working"; a bare spinner for the same
                        // sixty seconds reads as broken.
                        Text(
                            text = state.fix?.let { accuracyLabel(it) }
                                ?: stringResource(R.string.location_searching_wait),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                TextButton(onClick = controller::cancel) {
                    Text(stringResource(R.string.location_skip))
                }
            }

            LocationStatus.FOUND, LocationStatus.ACCEPTED -> {
                val fix = state.fix
                if (fix != null) {
                    // Words, not pixels: readable coordinates, the accuracy, and
                    // the time it was taken — a fix has an age.
                    Text(coordinateLabel(fix), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = accuracyLabel(fix) + " · " + stringResource(
                            R.string.location_captured_at,
                            fix.capturedAt.substringAfter('T').take(5),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isVague) {
                        // Accepting a vague fix is allowed — never block the
                        // save — but it is said plainly rather than rounded away.
                        Text(
                            stringResource(R.string.location_vague_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.standingHere,
                            onCheckedChange = controller::setStandingHere,
                        )
                        Text(
                            stringResource(R.string.location_standing_here),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    if (state.status == LocationStatus.ACCEPTED) {
                        Text(
                            stringResource(R.string.location_set),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = controller::retry) {
                            Text(stringResource(R.string.location_retry))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = controller::accept, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.location_use_this))
                            }
                            TextButton(onClick = controller::retry) {
                                Text(stringResource(R.string.location_retry))
                            }
                        }
                    }
                }
            }

            LocationStatus.DENIED -> {
                // Honest label, and the farm still saves without coordinates.
                Text(
                    stringResource(R.string.location_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { permissionLauncher.launch(LocationCapturer.PERMISSIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.location_try_again_permission))
                }
            }

            LocationStatus.BLOCKED -> {
                Text(
                    stringResource(R.string.location_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.location_open_settings))
                }
            }

            LocationStatus.UNAVAILABLE -> {
                Text(
                    stringResource(R.string.location_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.location_open_settings))
                }
            }
        }
    }
}

/** "±8 m", or "±unknown" when the chipset did not report one. */
@Composable
private fun accuracyLabel(fix: LocationFix): String =
    if (fix.accuracyM == Float.MAX_VALUE) stringResource(R.string.location_accuracy_unknown)
    else stringResource(R.string.location_accuracy, fix.accuracyM.toInt())

/**
 * "0.1874° N, 35.1021° E" — hemisphere letters rather than a minus sign, which
 * is both more readable and unambiguous on a small screen.
 */
private fun coordinateLabel(fix: LocationFix): String {
    val ns = if (fix.lat >= 0) "N" else "S"
    val ew = if (fix.lng >= 0) "E" else "W"
    return "%.4f° %s, %.4f° %s".format(abs(fix.lat), ns, abs(fix.lng), ew)
}
