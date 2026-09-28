package com.devbangs.onedevs.data.backend

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/** What came back from asking someone to sign in. */
sealed interface SignInOutcome {
    data class Success(val session: Session) : SignInOutcome

    /** They dismissed the sheet. Not an error, and not worth an error message. */
    data object Cancelled : SignInOutcome

    /** No Google account on the device, or Play services cannot offer one. */
    data object NoAccounts : SignInOutcome

    data class Failed(val reason: String) : SignInOutcome
}

/**
 * A nonce, in the two forms this flow needs.
 *
 * Google is given the SHA-256 hash and puts it in the token it mints; Supabase
 * is given the original and hashes it to check they match. They are named
 * apart rather than sharing one variable because passing the wrong one is the
 * classic failure here, and it reports itself as an unhelpful token error.
 */
private class Nonce {
    val raw: String = ByteArray(32)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    val hashed: String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/**
 * Signs in through the account sheet Google shows inside the app.
 *
 * No browser opens and nothing redirects, which is why the Supabase project
 * URL never appears anywhere the person can see it. [context] must be the
 * Activity -- Credential Manager has to show UI, and an application context
 * has no window to show it in.
 */
suspend fun signInWithGoogle(
    context: Context,
    backend: Backend,
    webClientId: String,
): SignInOutcome {
    if (webClientId.isBlank()) {
        return SignInOutcome.Failed("No Google client id in secrets.properties")
    }

    val nonce = Nonce()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                // false so every account on the device is offered. Filtering to
                // previously authorised ones is right for a silent re-sign-in
                // and wrong for a first one, which shows an empty sheet.
                .setFilterByAuthorizedAccounts(false)
                .setNonce(nonce.hashed)
                .build(),
        )
        .build()

    val response = try {
        CredentialManager.create(context).getCredential(context, request)
    } catch (cancelled: GetCredentialCancellationException) {
        return SignInOutcome.Cancelled
    } catch (none: NoCredentialException) {
        // Not always what it sounds like. This is also raised when Play
        // services cannot offer a credential at that moment, so the message it
        // maps to says what happened rather than diagnosing the device.
        return SignInOutcome.NoAccounts
    } catch (e: GetCredentialException) {
        return SignInOutcome.Failed(e.message ?: e::class.java.simpleName)
    }

    val credential = response.credential
    if (credential !is CustomCredential ||
        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return SignInOutcome.Failed("Unexpected credential: ${credential.type}")
    }

    val idToken = try {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (e: IllegalArgumentException) {
        return SignInOutcome.Failed("Malformed Google credential: ${e.message}")
    }

    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
    val session = backend.signInWithGoogle(
        idToken = idToken,
        nonce = nonce.raw,
        displayName = googleCredential.displayName ?: googleCredential.id,
        photoUrl = googleCredential.profilePictureUri?.toString(),
    )
        ?: return SignInOutcome.Failed("The backend refused the token")
    return SignInOutcome.Success(session)
}
