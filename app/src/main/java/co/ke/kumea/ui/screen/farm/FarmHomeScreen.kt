package co.ke.kumea.ui.screen.farm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import co.ke.kumea.data.local.PlantingEntity
import co.ke.kumea.domain.model.Crops
import co.ke.kumea.util.Area
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.KumeaNReceivedEntity
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmHomeScreen(
    farmId: String,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    /** §2.7 — the activity log's own entry point, separate from the two ledgers. */
    onAddObservation: () -> Unit,
    /** Farm-level now — planting is an entity on Farm (§2.3). */
    onAddPlanting: () -> Unit,
    /** (fieldId, harvestId) — a null harvestId records a new one, else corrects it. */
    onRecordHarvest: (String, String?) -> Unit,
    showSaveBeat: Boolean = false,
    onSaveBeatConsumed: () -> Unit = {},
    viewModel: FarmHomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val kumeaNReceived by viewModel.kumeaNReceived.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val primaryField by viewModel.primaryField.collectAsStateWithLifecycle()
    val latestHarvest by viewModel.latestHarvest.collectAsStateWithLifecycle()
    val latestPlanting by viewModel.latestPlanting.collectAsStateWithLifecycle()

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
            // Add record is the one verb that stays down here: it is the
            // catch-all for anything that is not a season milestone.
            //
            // Planting and harvest MOVED UP into Zone 2 (KWAP-03 §5.1/§5.3).
            // They are the same two wizards, unchanged — what changed is the
            // frame. At the bottom of an empty page they read as chores for a
            // bookkeeper; on the season timeline they read as the two moments
            // the season is actually made of, which is also exactly what the
            // impact report is computed from.
            if (field != null) {
                VerbBar(onAddRecord = onAddNote, onAddObservation = onAddObservation)
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
                    // ── THE FARM PAGE IS A SEASON, NOT A LEDGER (KWAP-03 §5.3) ──
                    //
                    // The meat was never missing here, it was buried and facing
                    // the wrong way: the planting and harvest wizards are the
                    // two best-designed screens in the product AND exactly the
                    // two data points the impact report needs, and they sat in a
                    // verb bar at the bottom of an empty page, framed as chores
                    // for a farmer who is a passive research participant this
                    // season rather than a bookkeeper.

                    // ZONE 1 — Your Kumea N. Replaces the sales card.
                    item {
                        KumeaNZone(
                            received = kumeaNReceived,
                            onOpenInfo = { showKumeaNSheet = true },
                        )
                    }

                    // ZONE 2 — Your season. A timeline, not an activity log; it
                    // re-hosts the two existing wizards rather than rebuilding
                    // them. Same code, correct frame.
                    item {
                        SeasonZone(
                            farm = farm,
                            field = field,
                            harvest = latestHarvest,
                            planting = latestPlanting,
                            received = kumeaNReceived,
                            hasFieldVisit = notes.isNotEmpty(),
                            onAddPlanting = onAddPlanting,
                            onAddHarvest = { field?.let { onRecordHarvest(it.id, null) } },
                        )
                    }

                    // ── THE RECORD ITSELF (KWAP-03-V2 §2.1) ──────────────────
                    //
                    // This card existed all along and was called from nowhere —
                    // orphaned in the KWAP-03 rewrite. That was the whole of the
                    // "tick appears, no record" bug: the timeline said Harvest ✓
                    // and the feed below it lists notes ONLY, so a farm with one
                    // harvest and no notes read "no activity yet" underneath its
                    // own tick.
                    //
                    // It renders from `latestHarvest` — the SAME flow the tick is
                    // derived from — which is the rule this ticket exists to
                    // write in, not just the fix.
                    if (field != null && (latestHarvest != null || latestPlanting != null)) {
                        item {
                            SeasonRecordCard(
                                field = field,
                                harvest = latestHarvest,
                                planting = latestPlanting,
                                onEditHarvest = latestHarvest?.let { h ->
                                    { onRecordHarvest(field.id, h.id) }
                                },
                            )
                        }
                    }

                    // ZONE 3 — What Kumea N did. Visibly locked until harvest:
                    // a locked state motivates, an empty page doesn't.
                    item {
                        ImpactZone(hasHarvest = latestHarvest != null)
                    }

                    // The money line-card and its "Full breakdown →" ledger link
                    // used to sit here. Removed (§5.4): no money surface on the
                    // farmer page this season. LedgerScreen still exists and
                    // still belongs to the agent persona.

                    // ④ Shughuli feed — the detail behind Zone 2's timeline.
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
                                    // Was "Record your first activity, purchase,
                                    // or sale" — two of those three no longer
                                    // exist on this page (§5.4).
                                    stringResource(R.string.no_shughuli_yet),
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
private fun VerbBar(onAddRecord: () -> Unit, onAddObservation: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // TWO ENTRY POINTS, because they are two different things
                // (§2.6/§2.7). "Add record" is the two money ledgers; "Add
                // observation" is the activity log, which carries no money. The
                // split is what lets the Activity form drop its cost field
                // without losing anywhere to record a field visit.
                VerbButton(
                    label = stringResource(R.string.verb_add_record),
                    iconRes = R.drawable.ic_record,
                    onClick = onAddRecord,
                    modifier = Modifier.weight(1f),
                )
                VerbButton(
                    label = stringResource(R.string.verb_add_observation),
                    iconRes = R.drawable.ic_hoe,
                    onClick = onAddObservation,
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
private fun KumeaNZone(
    received: List<KumeaNReceivedEntity>,
    onOpenInfo: () -> Unit,
) {
    PaperCard(onClick = onOpenInfo, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_sachet),
                    contentDescription = null,
                    tint = LeafGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.zone_your_kumea_n),
                    style = MaterialTheme.typography.titleSmall,
                    color = DeepLeaf,
                    modifier = Modifier.weight(1f),
                )
                Text("→", style = MaterialTheme.typography.bodyMedium, color = LeafGreen)
            }

            Spacer(Modifier.height(8.dp))

            if (received.isEmpty()) {
                // NOT A PRICE. This replaced "Biofix for your beans / KSh 1,500
                // per sachet" — a sales card on the page of a farmer who is
                // being given the product for free (§5.3, §5.4).
                Text(
                    stringResource(R.string.kumea_n_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                )
            } else {
                received.forEach { record ->
                    Text(
                        text = stringResource(
                            R.string.kumea_n_received_line,
                            record.qty,
                            record.packSizeG,
                            record.strainCode,
                            record.batchNumber,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                    Text(
                        text = stringResource(R.string.kumea_n_received_when, shortDate(record.occurredAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkMuted,
                    )
                    if (record.pendingSync) {
                        Spacer(Modifier.height(2.dp))
                        SyncBadge(pending = true)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * ZONE 2 — "Your season" (KWAP-03 §5.3). A timeline, not an activity log.
 *
 * Every row is either a fact with a date or an invitation with a button, and
 * the two are visually different. That distinction is the whole idea: an empty
 * activity log says "you have not done your homework", where a timeline with two
 * ticks and three open circles says "here is where your season has got to".
 *
 * The planting and harvest wizards are NOT rebuilt here. `onAddPlanting` and
 * `onAddHarvest` are the same navigation calls the verb bar used to make.
 *
 * ── 🔴 THE TICK AND THE RECORD COME FROM THE SAME QUERY (KWAP-03-V2 §2.1) ────
 *
 * Every row below derives `done` and `detail` from ONE read: Received from
 * `received`, Planted from `field.plantedAt`, Harvest from `harvest`. Keep it
 * that way. If completion state is ever derived from a different source than
 * the thing it summarises, the two disagree, and the farmer is shown a ✓ next
 * to a record they cannot find — which is precisely the trust-breaking bug this
 * ticket was opened to fix. (That bug was not in this composable: the tick was
 * right and the record simply had no renderer. The rule is what stops the next
 * one, which would be.)
 */
@Composable
private fun SeasonZone(
    farm: FarmEntity?,
    field: FieldEntity?,
    harvest: HarvestEntity?,
    planting: PlantingEntity?,
    received: List<KumeaNReceivedEntity>,
    hasFieldVisit: Boolean,
    onAddPlanting: () -> Unit,
    onAddHarvest: () -> Unit,
) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.zone_your_season),
                style = MaterialTheme.typography.titleSmall,
                color = DeepLeaf,
            )
            Spacer(Modifier.height(12.dp))

            // Registration is always done — the farm exists, so this row is
            // always a tick. It anchors the timeline with a completed step.
            TimelineRow(
                label = stringResource(R.string.season_registered),
                done = true,
                detail = farm?.createdAt?.let(::shortDate),
            )
            TimelineRow(
                label = stringResource(R.string.season_kumea_n_received),
                done = received.isNotEmpty(),
                detail = received.firstOrNull()?.occurredAt?.let(::shortDate),
            )
            // Reads `plantings`, not the retired `fields.plantedAt`. Tick and
            // detail both come from `planting` — same query, §2.1's rule.
            TimelineRow(
                label = stringResource(R.string.season_planted),
                done = planting != null,
                detail = planting?.plantedOn?.let(::shortLocalDate),
                actionLabel = if (planting == null) stringResource(R.string.season_add) else null,
                onAction = onAddPlanting,
            )
            // Officer observations arrive as notes; there is no button, because
            // a farmer cannot record a visit to their own shamba.
            TimelineRow(
                label = stringResource(R.string.season_field_visit),
                done = hasFieldVisit,
            )
            TimelineRow(
                label = stringResource(R.string.season_harvest),
                done = harvest != null,
                detail = harvest?.harvestDate?.let(::shortDate),
                actionLabel = if (field != null && harvest == null) {
                    stringResource(R.string.season_add)
                } else null,
                onAction = onAddHarvest,
                isLast = true,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    label: String,
    done: Boolean,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    isLast: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (done) "✓" else "○",
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) LeafGreen else InkMuted,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) Ink else InkMuted,
            modifier = Modifier.weight(1f),
        )
        when {
            detail != null -> Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
            )
            actionLabel != null -> TextButton(onClick = onAction) {
                Text(actionLabel, color = LeafGreen)
            }
        }
    }
    if (!isLast) Spacer(Modifier.height(10.dp))
}

/**
 * ZONE 3 — "What Kumea N did" (KWAP-03 §5.3, §6.3).
 *
 * A LOCKED STATE, ON PURPOSE. The results screen is a later ticket — harvest is
 * months away and there is no data to render — but an absent zone and a locked
 * one say completely different things. Locked says the app is going to tell you
 * something, and gives the season a destination; absent says the app has nothing
 * for you, which is what the page said before this ticket.
 */
@Composable
private fun ImpactZone(hasHarvest: Boolean) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.zone_what_kumea_n_did),
                style = MaterialTheme.typography.titleSmall,
                color = if (hasHarvest) DeepLeaf else InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // Both states are placeholders — §6.3 builds the real screen
                // when there is data. The second exists so a farmer who HAS
                // harvested is not told to come back after harvesting.
                stringResource(
                    if (hasHarvest) R.string.impact_coming_soon else R.string.impact_locked
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
        }
    }
}

/**
 * "13 Aug" from a plain "YYYY-MM-DD". `plantings.plantedOn` is a DATE, not an
 * instant — running it through [shortDate] would fail to parse and render blank.
 */
private fun shortLocalDate(iso: String): String = runCatching {
    val date = LocalDate.parse(iso.take(10))
    "${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
}.getOrDefault("")

/** "13 Aug" from a UTC ISO-8601 instant, in EAT. */
private fun shortDate(iso: String): String = runCatching {
    val date = Instant.parse(iso).toLocalDateTime(TimeZone.of("Africa/Nairobi")).date
    "${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
}.getOrDefault("")

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
            // The price line ("KSh 1,500 per 150 g sachet") was here and is
            // deleted (§5.4). The acres → sachets guidance above stays: it is
            // agronomy, and it is useful. The price was the sales pitch, and
            // this cohort is not being sold anything.
        }
    }
}

/*
 * MoneyLineCard USED TO LIVE HERE. Deleted with KWAP-03 §5.4 — "Invested so far
 * / No transactions yet", the net figure, and the "Full breakdown →" link into
 * the ledger are all gone from the FARMER's farm page.
 *
 * Not a cosmetic trim. There is no commercial spine this season: nothing is sold
 * through the app, and every sachet in the KWAP cohort is free research product.
 * A card reading "KES 0 invested" to a farmer who was given the product is worse
 * than no card — it asks a question the app cannot answer honestly.
 *
 * `LedgerScreen`, `OrderCreateScreen` and the rest of the commercial surface are
 * untouched and still reachable: they belong to the agent persona. What changed
 * is only which page they appear on.
 */

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
private fun SeasonRecordCard(
    field: FieldEntity,
    harvest: HarvestEntity?,
    planting: PlantingEntity?,
    onEditHarvest: (() -> Unit)? = null,
) {
    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onEditHarvest != null) Modifier.clickable(onClick = onEditHarvest)
                else Modifier,
            ),
    ) {
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

            planting?.let { p ->
                FactRow(
                    iconRes = R.drawable.ic_calendar,
                    text = stringResource(R.string.proof_planted, p.plantedOn.take(10)),
                )
                // Planted area, not farm area — the distinction §2.4 exists for,
                // surfaced so the farmer can see the number the report divides by.
                FactRow(
                    iconRes = R.drawable.ic_sprout,
                    text = stringResource(
                        R.string.proof_planted_detail,
                        Crops.label(p.crop) ?: p.crop,
                        Area.formatCenti(p.plantedAreaCenti),
                    ),
                )
            }
            harvest?.let { h ->
                val unitLabel = when (h.unit) {
                    HarvestUnits.BAGS -> stringResource(R.string.unit_bags)
                    HarvestUnits.KG -> stringResource(R.string.unit_kg)
                    HarvestUnits.GOROGORO -> stringResource(R.string.unit_gorogoro)
                    else -> h.unit
                }
                // §2.1's row: quantity in the farmer's own unit, canonical kg in
                // parentheses, date. The kg is what the impact report actually
                // sums, so showing it here is also the farmer's chance to say
                // "that's not right" while they still remember the harvest.
                // Suppressed when the unit IS kg — "720 kg (720 kg)" reads as a
                // bug — and when the conversion is UNKNOWN, where a pre-v13 bags
                // row has no honest kilogram to show.
                val canonicalKg = h.qtyKgCenti
                    .takeIf { it > 0 && h.unit != HarvestUnits.KG }
                    ?.let(Quantity::formatCenti)
                FactRow(
                    iconRes = R.drawable.ic_scale,
                    text = if (canonicalKg != null) {
                        stringResource(
                            R.string.proof_harvested_canonical,
                            Quantity.formatCenti(h.quantityCenti),
                            unitLabel,
                            canonicalKg,
                            shortDate(h.harvestDate),
                        )
                    } else {
                        stringResource(
                            R.string.proof_harvested_dated,
                            Quantity.formatCenti(h.quantityCenti),
                            unitLabel,
                            shortDate(h.harvestDate),
                        )
                    },
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
                // The card is tappable but a card does not look tappable, and a
                // farmer who thinks a wrong figure is permanent will not correct
                // it. Says so only when there is actually something to edit.
                if (onEditHarvest != null) {
                    Text(
                        stringResource(R.string.record_tap_to_edit),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
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
