package com.devbangs.onedevs.ui.profile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.SignInOutcome
import com.devbangs.onedevs.data.backend.signInWithGoogle
import com.devbangs.onedevs.ui.components.Waiting
import com.devbangs.onedevs.ui.plans.findActivity
import com.devbangs.onedevs.ui.settings.LegalLinks
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The door wears the icon's colours in both themes, as the store listing does:
// it is the first thing anyone sees after the Play page.
private val NavyTop = Color(0xFF04115E)
private val NavyBottom = Color(0xFF000533)
private val Indigo = Color(0xFF3A1CF5)
private val Electric = Color(0xFF1048FF)
private val Cyan = Color(0xFF5CF4FF)
private val CyanCore = Color(0xFFEBFFFF)
private val DevsBlue = Color(0xFF6FB6FF)
private val LaunchBrush = Brush.horizontalGradient(listOf(Cyan, Color(0xFF4D8DFF)))
private val Muted = Color.White.copy(alpha = 0.72f)
private val Quiet = Color.White.copy(alpha = 0.55f)
private val ErrorInk = Color(0xFFFFB4AB)

// Google's sign-in button, light version: white, the full-colour G, #1F1F1F
// text in the system sans.
private val GoogleInk = Color(0xFF1F1F1F)

/**
 * The door.
 *
 * Everything behind it belongs to an account: DevCoins are earned and spent,
 * missions are joined, listings are owned. A signed-out visitor browsing a
 * board of apps they cannot test would be the app describing itself falsely,
 * so there is nothing to browse until there is someone to browse as.
 */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val noGoogleMessage = stringResource(R.string.account_no_google)

    fun signIn() {
        scope.launch {
            busy = true
            note = null
            val outcome = signInWithGoogle(
                context = context,
                backend = app.backend,
                webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            )
            when (outcome) {
                is SignInOutcome.Success -> app.account.onSignedIn(outcome.session)
                SignInOutcome.Cancelled -> Unit
                SignInOutcome.NoAccounts -> note = noGoogleMessage
                is SignInOutcome.Failed -> note = outcome.reason
            }
            busy = false
        }
    }

    LightBarIcons()
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { brandSky() }) {
        // The sky runs under both bars; the content stays between them, a
        // little above the middle, with the legal line at the foot.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TesterRing()

                Spacer(Modifier.height(18.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append("One") }
                        withStyle(SpanStyle(color = DevsBlue)) { append("Devs") }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.signin_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.signin_headline_end),
                    style = MaterialTheme.typography.headlineSmall.copy(brush = LaunchBrush),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.signin_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp),
                )

                Spacer(Modifier.height(32.dp))
                GoogleButton(busy = busy, onClick = { signIn() })

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.signin_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = Quiet,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp),
                )

                if (note != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = note.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorInk,
                        textAlign = TextAlign.Center,
                    )
                }
                // Lifts the block above the middle of the screen.
                Spacer(Modifier.height(56.dp))
            }
            LegalLine()
        }
    }
}

/** "By signing in, you agree to our Terms of Service & Privacy Policy", both linked. */
@Composable
private fun LegalLine() {
    val terms = stringResource(R.string.legal_terms)
    val privacy = stringResource(R.string.legal_privacy)
    val full = stringResource(R.string.signin_legal, terms, privacy)
    val link = TextLinkStyles(SpanStyle(color = DevsBlue, textDecoration = TextDecoration.Underline))
    val text = remember(full, terms, privacy) {
        buildAnnotatedString {
            append(full)
            full.indexOf(terms).takeIf { it >= 0 }?.let {
                addLink(LinkAnnotation.Url(LegalLinks.TERMS, link), it, it + terms.length)
            }
            full.indexOf(privacy).takeIf { it >= 0 }?.let {
                addLink(LinkAnnotation.Url(LegalLinks.PRIVACY, link), it, it + privacy.length)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Quiet,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 360.dp).padding(top = 12.dp),
    )
}

/** Deep navy, lit from the top right and behind the mark, as the icon is. */
private fun DrawScope.brandSky() {
    if (size.minDimension <= 0f) return
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(NavyTop, NavyBottom)))
    drawRect(glow(Indigo, 0.55f, Offset(w, 0f), w * 0.95f))
    drawRect(glow(Electric, 0.40f, Offset(w / 2, h * 0.30f), w * 0.70f))
    drawRect(glow(Electric, 0.25f, Offset(0f, h), w * 0.85f))
}

private fun glow(color: Color, alpha: Float, at: Offset, radius: Float) =
    Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), at, radius)

/**
 * The mark inside twelve testers, alive: a scanner sweeps the orbit and each
 * tester lights as it passes, signals run round the ring, and the outer dial
 * turns slowly the other way. Twelve, because Google asks for twelve.
 */
@Composable
private fun TesterRing() {
    val time = rememberInfiniteTransition(label = "ring")
    val sweep by time.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "sweep",
    )
    val dial by time.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(36_000, easing = LinearEasing)),
        label = "dial",
    )
    val signal by time.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing)),
        label = "signal",
    )
    val breath by time.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(2_400), RepeatMode.Reverse),
        label = "breath",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 16.dp.toPx()
            val outer = r + 11.dp.toPx()
            val halo = 12.dp.toPx()

            // Light behind the mark, breathing.
            drawCircle(glow(Electric, breath, center, r), r)

            // The outer dial: sixty ticks, every fifth one longer, turning slowly.
            rotate(dial) {
                repeat(60) { k ->
                    val a = k * 6 * PI / 180
                    val long = k % 5 == 0
                    val from = outer - (if (long) 5.dp else 2.5.dp).toPx()
                    drawLine(
                        color = Cyan.copy(alpha = if (long) 0.45f else 0.18f),
                        start = Offset(center.x + from * cos(a).toFloat(), center.y + from * sin(a).toFloat()),
                        end = Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            // The orbit, dashed, and the scanner's wake behind its leading edge.
            drawCircle(
                Color.White.copy(alpha = 0.16f),
                r,
                style = Stroke(
                    width = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
            )
            rotate(sweep - 90f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to Cyan.copy(alpha = 0.55f),
                        center = center,
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Signals travelling the ring: three, at three speeds.
            listOf(1f, 2f, 3f).forEachIndexed { i, speed ->
                val a = ((signal * speed + i / 3f) % 1f) * 2 * PI - PI / 2
                val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                drawCircle(glow(CyanCore, 0.5f, p, 6.dp.toPx()), 6.dp.toPx(), p)
                drawCircle(CyanCore, 1.6.dp.toPx(), p)
            }

            // The twelve testers. Each lights as the scanner reaches it and
            // fades over the next quarter turn.
            repeat(12) { k ->
                val deg = k * 30f
                val a = (deg - 90) * PI / 180
                val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                val behind = ((sweep - deg) % 360f + 360f) % 360f
                val lit = (1f - behind / 90f).coerceIn(0f, 1f)
                drawCircle(glow(Cyan, 0.25f + 0.45f * lit, p, halo * (1f + 0.5f * lit)), halo * 1.5f, p)
                drawCircle(Cyan.copy(alpha = 0.55f + 0.45f * lit), (3.2f + 1.6f * lit).dp.toPx(), p)
                drawCircle(CyanCore, 1.6.dp.toPx(), p)
            }
        }
        // The splash icon is the mark on transparent, cut from the icon master.
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
        )
    }
}

@Composable
private fun GoogleButton(busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(54.dp)
            .shadow(16.dp, shape, ambientColor = Electric, spotColor = Electric)
            .background(Color.White, shape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (busy) {
            Waiting(size = 22.dp, color = Electric)
        } else {
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.signin_cta),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.SansSerif),
                fontWeight = FontWeight.Medium,
                color = GoogleInk,
            )
        }
    }
}

/**
 * White status and navigation icons while the door is open: the sky is dark
 * in both themes. Whatever the bars were set to comes back when it closes.
 */
@Composable
private fun LightBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val navigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (bars != null && status != null && navigation != null) {
                bars.isAppearanceLightStatusBars = status
                bars.isAppearanceLightNavigationBars = navigation
            }
        }
    }
}
