package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.ke.kumea.R
import co.ke.kumea.domain.model.CropSelection
import co.ke.kumea.domain.model.Crops

/**
 * "What do you grow?" — the grouped multi-select (KWAP-03 §4.2, §5.2), built on
 * the group structure `Crop.kt` has carried since 11 Aug for exactly this.
 *
 * THREE STATES ON ONE CHIP, cycled by tapping: not selected → growing (✓) →
 * interested (★) → not selected. One control rather than two lists, because the
 * question a farmer is answering is a single one about each crop, and a
 * separate "interested" section would be a form to fill rather than a
 * conversation to have.
 *
 * MAIZE IS IN THE LIST AND THAT IS DELIBERATE. The bug in the old three-chip row
 * was never that maize was present — it was that a three-item list implied
 * product need, so picking maize looked like asking for something Kumea N cannot
 * do. A grouped list that describes the whole farm can hold maize honestly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropMultiSelect(
    selection: CropSelection,
    onCropCycle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.crop_type), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.crop_multiselect_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Crops.GROUPS.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.crops.forEach { crop ->
                        val growing = crop.key in selection.growing
                        val interested = crop.key in selection.interested && !growing
                        FilterChip(
                            selected = growing || interested,
                            onClick = { onCropCycle(crop.key) },
                            label = { Text(crop.label) },
                            leadingIcon = when {
                                growing -> {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.crop_growing),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                interested -> {
                                    {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = stringResource(R.string.crop_interested),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }
}
