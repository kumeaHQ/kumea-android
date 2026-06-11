package co.ke.kumea.data.sync

/**
 * Per-repository push outcome (P1-T5a). The sync worker logged "0 pushed, no
 * error" while silently swallowing 401s — this report makes every push outcome
 * loud so a sync that leaves rows pending must say WHY.
 *
 * `attempted` counts only rows that produced a real push outcome
 * (succeeded + failed). A DEFERRED row (FK parent not on the server yet) is
 * tracked separately and is NOT counted as attempted — it is waiting, not failed
 * — matching the mandated report shape:
 *
 *   [Repo]: found N, attempted M, succeeded X, failed Y (status), deferred Z (reason)
 *
 * Invariant the worker relies on: any pending row that didn't succeed is either
 * `failed` (with HTTP status) or `deferred` (with reason). Nothing vanishes.
 */
data class PushReport(
    val repo: String,
    val found: Int = 0,
    val attempted: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    /** HTTP statuses of the failed pushes, e.g. ["401", "500"]. */
    val failures: List<String> = emptyList(),
    val deferred: Int = 0,
    /** Reasons for each deferral, e.g. ["FK parent not synced yet"]. */
    val deferReasons: List<String> = emptyList(),
) {
    /** The mandated one-line report. Status/reason shown only when non-empty. */
    fun line(): String = buildString {
        append("$repo: found $found, attempted $attempted, succeeded $succeeded, failed $failed")
        if (failed > 0 && failures.isNotEmpty()) append(" (${failures.joinToString(", ")})")
        if (deferred > 0) {
            append(", deferred $deferred")
            if (deferReasons.isNotEmpty()) append(" (${deferReasons.distinct().joinToString("; ")})")
        }
    }
}

/**
 * Mutable accumulator a repository fills while iterating its pending rows, then
 * `build()`s into a [PushReport]. Every non-2xx MUST go through `failed()` or
 * `deferred()` — there is no silent path.
 */
class PushReportBuilder(private val repo: String) {
    var found: Int = 0
    private var attempted = 0
    private var succeeded = 0
    private var failed = 0
    private val failures = mutableListOf<String>()
    private var deferred = 0
    private val deferReasons = mutableListOf<String>()

    /** A confirmed 2xx push. */
    fun succeeded() { attempted++; succeeded++ }

    /** A non-2xx that is not a deferral — surfaced with its HTTP status. */
    fun failed(status: String) { attempted++; failed++; failures += status }

    /** FK parent not on the server yet — left pending, retried next cycle. */
    fun deferred(reason: String) { deferred++; deferReasons += reason }

    fun build(): PushReport =
        PushReport(repo, found, attempted, succeeded, failed, failures.toList(), deferred, deferReasons.toList())
}
