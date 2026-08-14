@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package co.ke.kumea.ui.screen.field

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.ui.common.BAG_SIZES_CENTI
import co.ke.kumea.data.local.ReplantIntent
import co.ke.kumea.ui.common.PaperCard
import co.ke.kumea.ui.theme.DeepLeaf
import co.ke.kumea.ui.theme.GoldInk
import co.ke.kumea.ui.theme.GoldWash
import co.ke.kumea.ui.theme.HarvestGold
import co.ke.kumea.ui.theme.InkMuted
import co.ke.kumea.ui.theme.KumeaButtonShape
import co.ke.kumea.util.Area
import co.ke.kumea.util.Quantity

/**
 * Build-2 T3: the Proof-of-Loop capture. Five steps, one question per screen,
 * ONE atomic HarvestEntity written at final save only (no partial rows can
 * ever sync). State machine lives in HarvestWizardViewModel — Build-3 touched
 * styling only; the flow is byte-identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarvestWizardScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: HarvestWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEdit) R.string.harvest_title_edit else R.string.harvest_title,
                        ),
                        color = DeepLeaf,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.stepBack()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GoldDots(currentOrdinal = state.step.ordinal)

            // Edit mode reads the existing record first. Rendering the steps
            // against empty state for that frame would show the farmer a blank
            // wizard where their own figures should be.
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                when (state.step) {
                    HarvestStep.QUANTITY -> QuantityStep(viewModel, state)
                    HarvestStep.SANITY -> SanityStep(viewModel, state)
                    HarvestStep.UNIT -> UnitStep(viewModel, state)
                    HarvestStep.SPLIT -> SplitStep(viewModel, state)
                    HarvestStep.REPLANT -> ReplantStep(viewModel, state)
                    HarvestStep.REVIEW -> ReviewStep(viewModel, state)
                }
            }

            state.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Five gold dots — one per step, filled only once its step is complete
 * (recorded steps only; no fake progress). The current step wears a gold ring.
 */
@Composable
private fun GoldDots(currentOrdinal: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        // Derived from the enum, not a literal 5 — §2.8 added a sixth step and a
        // hardcoded count would have silently stopped drawing the last dot.
        repeat(HarvestStep.entries.size) { i ->
            val dotModifier = when {
                i < currentOrdinal -> Modifier.background(HarvestGold, CircleShape)
                i == currentOrdinal -> Modifier
                    .border(1.5.dp, HarvestGold, CircleShape)
                else -> Modifier.background(MaterialTheme.colorScheme.outline, CircleShape)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(10.dp)
                    .then(dotModifier),
            )
        }
    }
}

/**
 * THE YIELD SANITY LINE (KWAP-03-V2 §2.8, decision 10).
 *
 * The one cross-check in the whole app, on the one number the impact report is
 * computed from. Everything else is captured once and trusted (decision 6); a
 * yield is worth a second look because a farmer who means 8 gorogoro and taps
 * bags is out by a factor of 45, and nobody will be able to tell in December.
 *
 * It shows the derived arithmetic rather than asking the farmer to re-enter
 * anything: total kilograms, the planted area it was divided by, and the result.
 * "Change" goes back to the quantity and adjusts NOTHING on its own.
 */
@Composable
private fun SanityStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    val kgCenti = state.qtyKgCenti
    val perAcreCenti = state.kgPerAcreCenti
    val areaCenti = state.plantedAreaCenti

    StepTitle(stringResource(R.string.harvest_sanity_title), R.drawable.ic_scale)

    if (kgCenti != null && perAcreCenti != null && areaCenti != null) {
        Text(
            stringResource(
                R.string.harvest_sanity_body,
                Quantity.formatCenti(kgCenti),
                Area.formatCenti(areaCenti),
                Quantity.formatCenti(perAcreCenti),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }

    Text(
        stringResource(R.string.harvest_sanity_question),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = viewModel::stepNext,
            shape = KumeaButtonShape,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.harvest_sanity_yes)) }
        OutlinedButton(
            onClick = viewModel::reviseQuantity,
            shape = KumeaButtonShape,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.harvest_sanity_change)) }
    }
}

/** Step title: the question plus its one gold-lined subject icon. */
@Composable
private fun StepTitle(text: String, iconRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = HarvestGold,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.headlineSmall, color = DeepLeaf)
    }
}

@Composable
private fun QuantityStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    StepTitle(stringResource(R.string.harvest_how_much), R.drawable.ic_scale)
    OutlinedTextField(
        value = state.quantityText,
        onValueChange = viewModel::onQuantityChange,
        label = { Text(stringResource(R.string.harvest_quantity)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.headlineMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("0.5", "1", "2", "3").forEach { preset ->
            FilterChip(
                selected = state.quantityText == preset,
                onClick = { viewModel.onQuantityChange(preset) },
                label = { Text(preset) },
            )
        }
    }
    Text(
        stringResource(R.string.harvest_change_later),
        style = MaterialTheme.typography.bodySmall,
        color = InkMuted,
    )
    Button(
        onClick = viewModel::stepNext,
        enabled = Quantity.parseToCenti(state.quantityText)?.let { it > 0 } == true,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.next)) }
}

@Composable
private fun UnitStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    StepTitle(stringResource(R.string.harvest_which_unit), R.drawable.ic_sack)
    // Three first-class cards — gorogoro with equal visual dignity (storyboard).
    UnitCard(stringResource(R.string.unit_bags), HarvestUnits.BAGS, R.drawable.ic_sack, viewModel, state)
    UnitCard(stringResource(R.string.unit_kg), HarvestUnits.KG, R.drawable.ic_scale, viewModel, state)
    UnitCard(stringResource(R.string.unit_gorogoro), HarvestUnits.GOROGORO, R.drawable.ic_tin, viewModel, state)

    // ONE EXTRA TAP THAT SAVES THE ENTIRE DATASET (KWAP-03 §4.4).
    //
    // Asked here rather than as its own wizard step because it is part of
    // choosing the unit — "bags, and what size?" is one question a farmer
    // answers in one breath — and because a sixth gold dot for a question only
    // one of the three units asks would make the wizard look longer than it is.
    //
    // It is REQUIRED, not optional: without it "5 bags" cannot be turned into
    // kilograms, and a harvest that cannot be turned into kilograms cannot be
    // divided by acres, compared against the baseline, or added to any other
    // farm's. A bag is 50 or 90 kg and no default is right for both.
    if (state.unit == HarvestUnits.BAGS) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bag_size_question),
            style = MaterialTheme.typography.titleMedium,
            color = DeepLeaf,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BAG_SIZES_CENTI.forEach { centi ->
                FilterChip(
                    selected = state.bagSizeCenti == centi,
                    onClick = { viewModel.onBagSizeSelected(centi) },
                    label = { Text(stringResource(R.string.bag_size_kg, (centi / 100).toInt())) },
                )
            }
        }
    }

    Button(
        onClick = viewModel::stepNext,
        enabled = state.unitStepComplete,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.next)) }
}

/** Unit card: icon + label; selected = Gold Wash + ✓ + weight, never color alone. */
@Composable
private fun UnitCard(
    label: String,
    unit: String,
    iconRes: Int,
    viewModel: HarvestWizardViewModel,
    state: HarvestWizardState,
) {
    val selected = state.unit == unit
    PaperCard(
        onClick = { viewModel.onUnitSelected(unit) },
        containerColor = if (selected) GoldWash else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (selected) GoldInk else InkMuted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = GoldInk)
            }
        }
    }
}

@Composable
private fun SplitStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    StepTitle(stringResource(R.string.harvest_where_went), R.drawable.ic_market)
    Text(
        // Same unit for both amounts, stated up front — no percentage language.
        stringResource(R.string.harvest_same_unit, unitLabelLocalized(state.unit)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.keptText,
            onValueChange = viewModel::onKeptChange,
            label = { Text(stringResource(R.string.harvest_kept)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.soldText,
            onValueChange = viewModel::onSoldChange,
            label = { Text(stringResource(R.string.harvest_sold)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = viewModel::stepNext,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.next))
    }
    OutlinedButton(
        onClick = viewModel::skipSplit,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.harvest_skip))
    }
}

@Composable
private fun ReplantStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    StepTitle(stringResource(R.string.replant_question), R.drawable.ic_sprout)
    Button(
        onClick = { viewModel.onReplantSelected(ReplantIntent.YES) },
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.replant_yes)) }
    OutlinedButton(
        onClick = { viewModel.onReplantSelected(ReplantIntent.NO) },
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.replant_no)) }

    if (state.replantIntent == ReplantIntent.YES) {
        Text(stringResource(R.string.replant_which_month), style = MaterialTheme.typography.titleMedium)
        val monthNames = stringArrayResource(R.array.month_names)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            viewModel.upcomingMonths().take(3).forEach { ym ->
                MonthChip(ym, monthNames, viewModel, state)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            viewModel.upcomingMonths().drop(3).forEach { ym ->
                MonthChip(ym, monthNames, viewModel, state)
            }
        }
        Button(
            onClick = viewModel::stepNext,
            enabled = state.replantMonth != null,
            shape = KumeaButtonShape,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.next)) }
    }
}

@Composable
private fun unitLabelLocalized(unit: String?): String = when (unit) {
    HarvestUnits.BAGS -> stringResource(R.string.unit_bags)
    HarvestUnits.KG -> stringResource(R.string.unit_kg)
    HarvestUnits.GOROGORO -> stringResource(R.string.unit_gorogoro)
    else -> ""
}

@Composable
private fun MonthChip(ym: String, monthNames: Array<String>, viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    // ym = "2026-08"; label from the localized month-name array.
    val monthIndex = ym.substringAfter("-").toInt() - 1
    FilterChip(
        selected = state.replantMonth == ym,
        onClick = { viewModel.onReplantMonthSelected(ym) },
        label = { Text(monthNames[monthIndex]) },
    )
}

/**
 * Review: a miniature Season Record — the receipt the artifact completes.
 * Gold rule + gold icon, fact rows; the save button stays Leaf Green (gold
 * marks the subject, green marks the action).
 */
@Composable
private fun ReviewStep(viewModel: HarvestWizardViewModel, state: HarvestWizardState) {
    val monthNames = stringArrayResource(R.array.month_names)
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(HarvestGold),
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_sack),
                    contentDescription = null,
                    tint = HarvestGold,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.season_record),
                    style = MaterialTheme.typography.titleMedium,
                    color = DeepLeaf,
                )
            }
            Text(
                stringResource(
                    R.string.harvest_review_line,
                    state.quantityText,
                    unitLabelLocalized(state.unit),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            state.replantMonth?.let { ym ->
                Text(
                    stringResource(R.string.replant_review_line, monthNames[ym.substringAfter("-").toInt() - 1]),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.replantIntent == ReplantIntent.NO) {
                Text(stringResource(R.string.replant_review_no), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    Button(
        onClick = viewModel::save,
        enabled = !state.isSaving,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.harvest_save)) }
    OutlinedButton(
        onClick = { viewModel.stepBack() },
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.edit))
    }
}
