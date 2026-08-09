package co.ke.kumea.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Minimal swipe-down-to-refresh wrapper.
 *
 * Material3 1.2.x (Compose BOM 2024.09.00) predates `PullToRefreshBox`, and the
 * Ticket 2.3b constraints forbid adding the Material2 `material` artifact just for
 * its `pullRefresh` modifier — so this is a small zero-dependency implementation
 * built on a [NestedScrollConnection]. It surfaces a [CircularProgressIndicator]
 * as the content is over-scrolled past a threshold, then fires [onRefresh].
 *
 * The inner [content] must be vertically scrollable (e.g. a LazyColumn) for the
 * overscroll to register.
 */
@Composable
fun PullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPullPx = thresholdPx * 1.6f
    var pull by remember { mutableFloatStateOf(0f) }

    val connection = remember(isRefreshing, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Retract our own pull distance first when the user scrolls back up.
                if (available.y < 0 && pull > 0f) {
                    val consumed = available.y.coerceAtLeast(-pull)
                    pull = (pull + consumed).coerceAtLeast(0f)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Consume leftover downward overscroll at the top to build the pull.
                if (!isRefreshing && available.y > 0f) {
                    pull = (pull + available.y * 0.5f).coerceAtMost(maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!isRefreshing && pull >= thresholdPx) {
                    onRefresh()
                }
                animate(pull, 0f) { value, _ -> pull = value }
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        // Pin the indicator while refreshing; otherwise track the drag distance.
        val indicatorPx = if (isRefreshing) thresholdPx else pull
        if (isRefreshing || pull > 0f) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { (indicatorPx / 2f).toDp() }),
                strokeWidth = 2.dp,
            )
        }
    }
}
