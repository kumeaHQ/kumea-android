package co.ke.kumea.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.ke.kumea.R
import co.ke.kumea.data.local.HarvestConversions
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.domain.model.BaselineInput

private data class UnitOption(val key: String, val labelResId: Int)

private val baselineUnits = listOf(
    // The harvest wizard's own vocabulary, deliberately: the baseline exists to
    // be compared against a harvest, and a comparison across two vocabularies is
    // a conversion waiting to be got wrong.
    UnitOption(HarvestUnits.BAGS, R.string.unit_bags),
    UnitOption(HarvestUnits.KG, R.string.unit_kg),
    UnitOption(HarvestUnits.GOROGORO, R.string.unit_gorogoro),
)

/** The two bag sizes that actually circulate. A bag is asked, never assumed. */
val BAG_SIZES_CENTI = listOf(
    HarvestConversions.BAG_50KG_CENTI,
    HarvestConversions.BAG_90KG_CENTI,
)

/**
 * "What did you harvest here last season?" (KWAP-03 §4.1, decision 1).
 *
 * SKIPPABLE BY CONSTRUCTION — no validation, no required marker, and a save that
 * ignores it entirely when it is blank. It is asked at registration only because
 * it cannot be asked later: by December, recall of a harvest fourteen months
 * back, after a season of being told a product would help, produces the number
 * the farmer thinks we want rather than the one that happened.
 *
 * Shared by both registration flows so the question, and therefore the data, is
 * the same one in each.
 */
@Composable
fun BaselineSection(
    input: BaselineInput,
    onQtyChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onBagSizeChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.baseline_question), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.baseline_optional),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = input.qty,
            onValueChange = onQtyChange,
            label = { Text(stringResource(R.string.baseline_qty)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            baselineUnits.forEach { unit ->
                FilterChip(
                    selected = input.unit == unit.key,
                    onClick = { onUnitChange(unit.key) },
                    label = { Text(stringResource(unit.labelResId)) },
                )
            }
        }

        // ONE EXTRA TAP THAT SAVES THE DATASET. A bag is 50 kg or 90 kg
        // depending on crop and county, so without this the recalled figure
        // cannot be compared with anything — including this farm's own harvest.
        if (input.unit == HarvestUnits.BAGS) {
            Text(stringResource(R.string.bag_size_question), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BAG_SIZES_CENTI.forEach { centi ->
                    FilterChip(
                        selected = input.bagSizeCenti == centi,
                        onClick = { onBagSizeChange(centi) },
                        label = { Text(stringResource(R.string.bag_size_kg, (centi / 100).toInt())) },
                    )
                }
            }
            if (input.needsBagSize) {
                Text(
                    stringResource(R.string.bag_size_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
