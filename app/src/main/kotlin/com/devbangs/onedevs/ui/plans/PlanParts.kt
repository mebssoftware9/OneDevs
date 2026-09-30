package com.devbangs.onedevs.ui.plans

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.plans.PurchaseOutcome

// Pieces the plan screens share: Plans, Ghostline and the Lab's upgrade sheet.

/** The Activity a Compose context belongs to; Play's sheet needs one. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Ink on the Ghostline card, which is dark in both themes. */
internal val GhostInk = Color.White
internal val GhostMuted = Color.White.copy(alpha = 0.72f)
internal val GhostWash = Color.White.copy(alpha = 0.12f)

internal fun outcomeText(outcome: PurchaseOutcome): Int = when (outcome) {
    PurchaseOutcome.ProActive -> R.string.plans_done_pro
    PurchaseOutcome.GhostlineStarted -> R.string.gl_started
    PurchaseOutcome.Pending -> R.string.plans_pending
    PurchaseOutcome.Cancelled -> R.string.plans_cancelled
    PurchaseOutcome.Unconfirmed -> R.string.plans_unconfirmed
    is PurchaseOutcome.Refused -> when (outcome.reason) {
        "already_running" -> R.string.gl_err_running
        "not_your_testing_app" -> R.string.gl_err_app
        else -> R.string.plans_refused
    }
    PurchaseOutcome.Unavailable -> R.string.plans_unavailable
}

@Composable
internal fun Feature(text: String, tick: Color, ink: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = tick,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = ink)
    }
}

@Composable
internal fun ActionButton(text: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = when {
            !enabled -> scheme.onSurfaceVariant
            filled -> scheme.onPrimary
            else -> scheme.primary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (enabled && filled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
    )
}
