package co.ke.kumea.ui.screen.farm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.FieldEntity
import co.ke.kumea.data.local.HarvestEntity
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.local.ReplantIntent
import co.ke.kumea.ui.common.PaperCard
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.ui.common.SyncBadge
import co.ke.kumea.ui.theme.Clay
import co.ke.kumea.ui.theme.ClayWash
import co.ke.kumea.ui.theme.DeepLeaf
import co.ke.kumea.ui.theme.HarvestGold
import co.ke.kumea.ui.theme.Ink
import co.ke.kumea.ui.theme.InkMuted
import co.ke.kumea.ui.theme.LeafGreen
import co.ke.kumea.ui.theme.LeafWash
import co.ke.kumea.ui.theme.Teal
import co.ke.kumea.util.Money
import co.ke.kumea.util.Quantity
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmHomeScreen(
    farmId: String,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    onOpenLedger: () -> Unit,
    onAddPlantingDate: (String) -> Unit,
    onRecordHarvest: (String) -> Unit,
    showSaveBeat: Boolean = false,
    onSaveBeatConsumed: () -> Unit = {},
    viewModel: FarmHomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val primaryField by viewModel.primaryField.collectAsStateWithLifecycle()
    val latestHarvest by viewModel.latestHarvest.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showKumeaNSheet by remember { mutableStateOf(false) }

    LaunchedEffect(farmId) { viewModel.init(farmId) }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.onErrorShown()
        }
    }

    // The save beat (§8): show the felt-state chip for 3s after returning from
    // a save. Content is derived live, so if the sync lands mid-beat the chip
    // swaps from neutral "saved to phone" to teal "synced" on its own.
    var beatVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showSaveBeat) {
        if (showSaveBeat) {
            beatVisible = true
            onSaveBeatConsumed()
            delay(3_000)
            beatVisible = false
        }
    }

    val farm = ui.farm
    val field = primaryField
    val anyPending = notes.any { it.pendingSync } ||
        field?.pendingSync == true ||
        latestHarvest?.pendingSync == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        farm?.let { f ->
                            // Deep Leaf text on paper — the colored slab is retired.
                            Text(
                                text = f.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = DeepLeaf,
                            )
                            val subtitle = buildString {
                                f.cropType?.let { append(cropLabel(it)) }
                                f.acres?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(stringResource(R.string.acres_fmt, it))
                                }
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkMuted,
                                )
                            }
                        } ?: Text(stringResource(R.string.my_farms), color = DeepLeaf)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // One action system: the three season verbs, always reachable.
            // FAB and SELL/MONEY toolbar actions are deleted; sales are
            // recorded through Add record, money through the money line-card.
            if (field != null) {
                VerbBar(
                    onPlanting = { onAddPlantingDate(field.id) },
                    onHarvest = { onRecordHarvest(field.id) },
                    onAddRecord = onAddNote,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            PullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ① Season Record — captured facts only, a stage without data
                    // simply does not appear (partial seasons still get their record).
                    if (field != null && (field.plantedAt != null || latestHarvest != null)) {
                        item {
                            SeasonRecordCard(field = field, harvest = latestHarvest)
                        }
                    }

                    // ② Kumea N — one link-row; the card content lives behind it.
                    item {
                        KumeaNLinkRow(onOpen = { showKumeaNSheet = true })
                    }

                    // ③ Money — one line-card; the ledger holds the breakdown.
                    item {
                        MoneyLineCard(ui = ui, anyPending = anyPending, onTap = onOpenLedger)
                    }

                    // ④ Shughuli feed.
                    item {
                        Text(
                            stringResource(R.string.shughuli),
                            style = MaterialTheme.typography.titleSmall,
                            color = DeepLeaf,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    if (notes.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.add_first_note),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkMuted,
                                )
                            }
                        }
                    } else {
                        items(notes, key = { it.id }) { note ->
                            NoteRow(note)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = beatVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                SaveBeatChip(pending = anyPending)
            }
        }
    }

    if (showKumeaNSheet) {
        KumeaNSheet(
            acres = farm?.acres ?: 0.5,
            sachetsNeeded = farm?.acres?.let { maxOf(1, ceil(it).toInt()) } ?: 1,
            onDismiss = { showKumeaNSheet = false },
        )
    }
}

/**
 * The three season verbs (Build-3 v2 §4): planting date, record harvest,
 * add record. Leaf Wash tonal, equal thirds — every verb one tap away.
 */
@Composable
private fun VerbBar(
    onPlanting: () -> Unit,
    onHarvest: () -> Unit,
    onAddRecord: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VerbButton(
                    label = stringResource(R.string.planting_action),
                    iconRes = R.drawable.ic_sprout,
                    onClick = onPlanting,
                    modifier = Modifier.weight(1f),
                )
                VerbButton(
                    label = stringResource(R.string.harvest_action),
                    iconRes = R.drawable.ic_sack,
                    onClick = onHarvest,
                    modifier = Modifier.weight(1f),
                )
                VerbButton(
                    label = stringResource(R.string.verb_add_record),
                    iconRes = R.drawable.ic_record,
                    onClick = onAddRecord,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VerbButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = LeafWash,
        contentColor = DeepLeaf,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Kumea N guidance leaves the feed: one quiet link-row opens the sheet. */
@Composable
private fun KumeaNLinkRow(onOpen: () -> Unit) {
    PaperCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sachet),
                contentDescription = null,
                tint = LeafGreen,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.kumea_n_link_row),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Text("→", style = MaterialTheme.typography.bodyMedium, color = LeafGreen)
        }
    }
}

/** The old Kumea N card content, now behind the link-row. Rate per canon §4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KumeaNSheet(acres: Double, sachetsNeeded: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_sachet),
                    contentDescription = null,
                    tint = LeafGreen,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.kumea_n_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = DeepLeaf,
                )
            }
            Text(stringResource(R.string.kumea_n_desc), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.kumea_n_sachets_needed, sachetsNeeded, 150),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.kumea_n_price), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Money as one quiet line-card: label + net figure + teal sync dot. Verdict
 * color only after harvest; pre-harvest costs are an investment in Ink, never
 * red. "Full breakdown →" opens the ledger.
 */
@Composable
private fun MoneyLineCard(ui: FarmHomeUiState, anyPending: Boolean, onTap: () -> Unit) {
    val hasHarvested = ui.totalInCents > 0L
    val hasAnyActivity = ui.totalInCents > 0L || ui.totalOutCents > 0L
    val balance = ui.totalInCents - ui.totalOutCents

    PaperCard(onClick = onTap, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    when {
                        !hasHarvested -> stringResource(R.string.invested)
                        balance >= 0 -> stringResource(R.string.profit)
                        else -> stringResource(R.string.loss)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = InkMuted,
                )
                if (!hasAnyActivity) {
                    Text(
                        stringResource(R.string.no_transactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkMuted,
                    )
                } else {
                    val figureColor = when {
                        !hasHarvested -> Ink
                        balance >= 0 -> LeafGreen
                        else -> MaterialTheme.colorScheme.error
                    }
                    val figure = when {
                        !hasHarvested -> Money.formatCents(ui.totalOutCents)
                        balance >= 0 -> Money.formatCents(balance)
                        else -> "−" + Money.formatCents(-balance)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = figure,
                            style = MaterialTheme.typography.titleMedium,
                            color = figureColor,
                        )
                        if (anyPending) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Teal, CircleShape),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.full_breakdown),
                style = MaterialTheme.typography.labelMedium,
                color = LeafGreen,
            )
        }
    }
}

/** Icon-led feed row: `+` is Leaf (a sale), `−` is Clay (an input — never red). */
@Composable
private fun NoteRow(note: NoteEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (iconRes, iconTint, washColor) = when (note.type) {
            NoteType.SALE -> Triple(R.drawable.ic_market, LeafGreen, LeafWash)
            NoteType.PURCHASE -> Triple(categoryIcon(note.costCategory), Clay, ClayWash)
            NoteType.ACTIVITY -> Triple(R.drawable.ic_hoe, InkMuted, MaterialTheme.colorScheme.surfaceVariant)
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(washColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = note.body, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.occurredAt.take(10),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted,
                )
                Spacer(Modifier.width(8.dp))
                SyncBadge(pending = note.pendingSync)
            }
        }
        note.amountCents?.let { cents ->
            val isSale = note.type == NoteType.SALE
            Text(
                text = (if (isSale) "+" else "−") + Money.formatCents(cents),
                style = MaterialTheme.typography.titleSmall,
                color = if (isSale) LeafGreen else Clay,
            )
        }
    }
}

// Kumea N writes OTHER until the server's enum has a value for it, so a Kumea N
// purchase shows the generic icon here and reads "Other" in the ledger
// breakdown. That is the honest cost of not inventing a wire value — the amount
// is right, only the label is coarse. Give it back when RB ships the enum.
private fun categoryIcon(category: CostCategory?): Int = when (category) {
    CostCategory.SEED -> R.drawable.ic_seed_bag
    CostCategory.FERTILISER -> R.drawable.ic_fertiliser
    CostCategory.SPRAY -> R.drawable.ic_jerrycan
    CostCategory.LABOUR -> R.drawable.ic_hoe
    CostCategory.TRANSPORT -> R.drawable.ic_matatu
    CostCategory.OTHER, null -> R.drawable.ic_ellipsis
}

/** The save beat: neutral paper/ink while on phone, teal once synced. */
@Composable
private fun SaveBeatChip(pending: Boolean) {
    val fg = if (pending) Ink else Teal
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (pending) MaterialTheme.colorScheme.outline else Teal),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (pending) R.drawable.ic_phone_saved else R.drawable.ic_cloud_check,
                ),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (pending) R.string.saved_on_phone else R.string.synced),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

@Composable
private fun cropLabel(type: String): String = when (type) {
    "beans" -> stringResource(R.string.crop_beans)
    "maize" -> stringResource(R.string.crop_maize)
    "soya" -> stringResource(R.string.crop_soya)
    else -> type.replaceFirstChar { it.uppercase() }
}

/**
 * The Season Record (Build-3 v2 §7): the artifact. Gold top rule + one gold
 * icon — the whole gold budget. Facts only, each on its own icon row; no
 * acreage (per-acre reads are agent territory). The stamp is honest: teal
 * "synced · HH:MM" only when nothing is pending, otherwise "on phone".
 * NEVER "Imethibitishwa" — nothing verifies farmer-entered data.
 */
@Composable
private fun SeasonRecordCard(field: FieldEntity, harvest: HarvestEntity?) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(HarvestGold),
        )
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            field.plantedAt?.let {
                FactRow(
                    iconRes = R.drawable.ic_calendar,
                    text = stringResource(R.string.proof_planted, it.take(10)),
                )
            }
            harvest?.let { h ->
                val unitLabel = when (h.unit) {
                    HarvestUnits.BAGS -> stringResource(R.string.unit_bags)
                    HarvestUnits.KG -> stringResource(R.string.unit_kg)
                    HarvestUnits.GOROGORO -> stringResource(R.string.unit_gorogoro)
                    else -> h.unit
                }
                FactRow(
                    iconRes = R.drawable.ic_scale,
                    text = stringResource(
                        R.string.proof_harvested,
                        Quantity.formatCenti(h.quantityCenti),
                        unitLabel,
                    ),
                )
                when (h.replantIntent) {
                    ReplantIntent.YES -> {
                        val monthNames = stringArrayResource(R.array.month_names)
                        val monthLabel = h.replantMonth
                            ?.substringAfter("-")?.toIntOrNull()
                            ?.let { monthNames.getOrNull(it - 1) }
                        FactRow(
                            iconRes = R.drawable.ic_sprout,
                            text = if (monthLabel != null) {
                                stringResource(R.string.proof_replanting, monthLabel)
                            } else {
                                stringResource(R.string.proof_replanting_yes)
                            },
                        )
                    }
                    ReplantIntent.NO -> FactRow(
                        iconRes = R.drawable.ic_sprout,
                        text = stringResource(R.string.proof_not_replanting),
                    )
                    else -> Unit
                }
            }

            // Honesty stamp, bottom-right.
            val synced = !field.pendingSync && harvest?.pendingSync != true
            val stampTime = if (synced) syncedStampTime(field.updatedAt, harvest?.updatedAt) else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(
                        if (synced) R.drawable.ic_cloud_check else R.drawable.ic_phone_saved,
                    ),
                    contentDescription = null,
                    tint = Teal,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (synced && stampTime != null) {
                        stringResource(R.string.synced_at, stampTime)
                    } else if (synced) {
                        stringResource(R.string.synced)
                    } else {
                        stringResource(R.string.on_phone)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Teal,
                )
            }
        }
    }
}

@Composable
private fun FactRow(iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = InkMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

/** Latest server-confirmed updatedAt, as HH:MM in the device zone (EAT). */
private fun syncedStampTime(vararg isoTimes: String?): String? =
    isoTimes.filterNotNull().maxOrNull()?.let { iso ->
        runCatching { Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()) }
            .getOrNull()
            ?.let { "%02d:%02d".format(it.hour, it.minute) }
    }
