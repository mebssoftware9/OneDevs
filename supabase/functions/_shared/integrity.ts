// Play Integrity: what Google says an integrity token means, and whether
// OneDevs answers the session that sent it.
//
// The Google Cloud project behind GOOGLE_SERVICE_ACCOUNT_JSON must be the one
// linked to the app under Play Console > App integrity, with the Play
// Integrity API enabled. PLAY_CERT_DIGESTS (optional) lists the app signing
// certificate's SHA-256 as Google reports it, comma-separated.

import { accessToken, packageName } from "./play.ts";

const SCOPE = "https://www.googleapis.com/auth/playintegrity";

/** The parts of Google's decoded verdict OneDevs decides on. */
export interface Payload {
  requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string };
  appIntegrity?: { appRecognitionVerdict?: string; certificateSha256Digest?: string[] };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  accountDetails?: { appLicensingVerdict?: string };
}

export interface Judgement {
  passed: boolean;
  reason: string | null;
  summary: { app: string; device: string[]; licence: string };
}

/** Google's own reading of a token, or null if it would not give one. */
export async function decode(token: string): Promise<Payload | null> {
  const res = await fetch(`https://playintegrity.googleapis.com/v1/${packageName()}:decodeIntegrityToken`, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken(SCOPE)}`, "Content-Type": "application/json" },
    body: JSON.stringify({ integrity_token: token }),
  });
  if (!res.ok) return null;
  const body = await res.json();
  return body?.tokenPayloadExternal ?? null;
}

/**
 * Whether a verdict is one OneDevs answers: made for this package and this
 * request, no more than five minutes old, by the app as Google Play
 * distributes it, on a genuine device. Pure, so it is tested without Google.
 */
export function judge(
  p: Payload,
  expect: { packageName: string; requestHash: string; certDigests: string[]; now: number },
): Judgement {
  const summary = {
    app: p.appIntegrity?.appRecognitionVerdict ?? "UNEVALUATED",
    device: p.deviceIntegrity?.deviceRecognitionVerdict ?? [],
    licence: p.accountDetails?.appLicensingVerdict ?? "UNEVALUATED",
  };
  const fail = (reason: string): Judgement => ({ passed: false, reason, summary });
  const request = p.requestDetails ?? {};
  if (request.requestPackageName !== expect.packageName) return fail("wrong_package");
  if (!request.requestHash || request.requestHash !== expect.requestHash) return fail("wrong_request");
  const at = Number(request.timestampMillis);
  if (!Number.isFinite(at) || Math.abs(expect.now - at) > 5 * 60_000) return fail("stale");
  if (summary.app !== "PLAY_RECOGNIZED") return fail("app_not_recognized");
  if (expect.certDigests.length > 0) {
    const certs = p.appIntegrity?.certificateSha256Digest ?? [];
    if (!certs.some((c) => expect.certDigests.includes(c))) return fail("wrong_signature");
  }
  if (!summary.device.includes("MEETS_DEVICE_INTEGRITY")) return fail("device_not_trusted");
  return { passed: true, reason: null, summary };
}

/** SHA-256 of a string as lowercase hex: the request hash the app sends. */
export async function sha256Hex(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** The session id inside an access token the auth server has already accepted. */
export function sessionOf(jwt: string): string | null {
  const part = jwt.split(".")[1];
  if (!part) return null;
  try {
    const text = atob(part.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((part.length + 3) % 4));
    const id = JSON.parse(text)?.session_id;
    return typeof id === "string" && /^[0-9a-f-]{36}$/i.test(id) ? id : null;
  } catch {
    return null;
  }
}
