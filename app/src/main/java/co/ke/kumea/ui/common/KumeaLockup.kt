package co.ke.kumea.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.ke.kumea.R
import co.ke.kumea.ui.theme.LogoCharcoal
import co.ke.kumea.ui.theme.LogoForest

/**
 * Kumea logo lockups (kumea-logo-spec.md, locked 12 Jul 2026). Poppins is
 * bundled for the wordmark only — body text stays on the system sans.
 */
val PoppinsFamily = FontFamily(Font(R.font.poppins_semibold, FontWeight.SemiBold))

/**
 * Stacked lockup: mark, wordmark, tagline. Splash screens only — the tagline
 * never appears inside working screens (Build-3 v2 §2).
 */
@Composable
fun KumeaStackedLockup(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.kumea_mark),
            contentDescription = null,
            modifier = Modifier.size(width = 176.dp, height = 198.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            letterSpacing = 1.sp,
            color = LogoForest,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tagline),
            fontSize = 15.sp,
            letterSpacing = 0.5.sp,
            color = LogoCharcoal,
        )
    }
}

/** Horizontal lockup: mark + wordmark, no tagline. Headers (Welcome screen). */
@Composable
fun KumeaHorizontalLockup(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.kumea_mark),
            contentDescription = null,
            modifier = Modifier.size(width = 36.dp, height = 40.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontFamily = PoppinsFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            letterSpacing = 0.5.sp,
            color = LogoForest,
        )
    }
}
