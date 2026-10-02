// Google Play Developer API, spoken to with a service account.
//
// Secrets (supabase secrets set ...):
//   GOOGLE_SERVICE_ACCOUNT_JSON  the key file of a service account invited
//                                to Play Console with "View financial data"
//                                and "Manage orders and subscriptions"
//   PLAY_PACKAGE_NAME            defaults to com.devbangs.onedevs

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

export const packageName = () => Deno.env.get("PLAY_PACKAGE_NAME") ?? "com.devbangs.onedevs";

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

// One access token per scope: Android Publisher for purchases, Play Integrity for verdicts.
const cached = new Map<string, { token: string; until: number }>();

function b64url(bytes: Uint8Array | string): string {
  const raw = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let s = "";
  for (const b of raw) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): Uint8Array<ArrayBuffer> {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** A signed JWT asking Google for an access token. Exported for tests. */
export async function signedAssertion(sa: ServiceAccount, now = Math.floor(Date.now() / 1000), scope = SCOPE) {
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope,
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  return `${header}.${claims}.${b64url(new Uint8Array(sig))}`;
}

export async function accessToken(scope = SCOPE): Promise<string> {
  const hit = cached.get(scope);
  if (hit && hit.until > Date.now() + 60_000) return hit.token;
  const raw = Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("GOOGLE_SERVICE_ACCOUNT_JSON is not set");
  const sa = JSON.parse(raw) as ServiceAccount;
  const res = await fetch(sa.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: await signedAssertion(sa, undefined, scope),
    }),
  });
  if (!res.ok) throw new Error(`Google token refused: ${res.status} ${await res.text()}`);
  const body = await res.json();
  cached.set(scope, { token: body.access_token, until: Date.now() + body.expires_in * 1000 });
  return body.access_token;
}

async function google(path: string, method = "GET"): Promise<{ status: number; body: any }> {
  const res = await fetch(`${API}/${packageName()}/${path}`, {
    method,
    headers: { Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json" },
    body: method === "POST" ? "{}" : undefined,
  });
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { status: res.status, body };
}

export interface SubscriptionFacts {
  state: "active" | "cancelled" | "expired" | "pending";
  expires: string | null;
  account: string | null;
  products: string[];
  orderId: string | null;
  acknowledged: boolean;
  raw: unknown;
}

/** Maps Google's subscription states onto the four the database keeps. */
export function subscriptionState(s: string): SubscriptionFacts["state"] {
  switch (s) {
    case "SUBSCRIPTION_STATE_ACTIVE":
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
      return "active";
    case "SUBSCRIPTION_STATE_CANCELED":
      return "cancelled";
    case "SUBSCRIPTION_STATE_PENDING":
      return "pending";
    default:
      return "expired";
  }
}

export async function subscription(token: string): Promise<SubscriptionFacts | null> {
  const r = await google(`purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`);
  if (r.status !== 200) return null;
  const items: any[] = r.body.lineItems ?? [];
  const expiries = items.map((i) => i.expiryTime).filter(Boolean).sort();
  return {
    state: subscriptionState(r.body.subscriptionState),
    expires: expiries.at(-1) ?? null,
    account: r.body.externalAccountIdentifiers?.obfuscatedExternalAccountId ?? null,
    products: items.map((i) => i.productId),
    orderId: r.body.latestOrderId ?? null,
    acknowledged: r.body.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    raw: r.body,
  };
}

export async function acknowledgeSubscription(product: string, token: string) {
  await google(`purchases/subscriptions/${product}/tokens/${encodeURIComponent(token)}:acknowledge`, "POST");
}

export interface ProductFacts {
  purchased: boolean;
  pending: boolean;
  account: string | null;
  profile: string | null;
  orderId: string | null;
  acknowledged: boolean;
  raw: unknown;
}

export async function product(productId: string, token: string): Promise<ProductFacts | null> {
  const r = await google(`purchases/products/${productId}/tokens/${encodeURIComponent(token)}`);
  if (r.status !== 200) return null;
  return {
    purchased: r.body.purchaseState === 0,
    pending: r.body.purchaseState === 2,
    account: r.body.obfuscatedExternalAccountId ?? null,
    profile: r.body.obfuscatedExternalProfileId ?? null,
    orderId: r.body.orderId ?? null,
    acknowledged: r.body.acknowledgementState === 1,
    raw: r.body,
  };
}

export async function acknowledgeProduct(productId: string, token: string) {
  await google(`purchases/products/${productId}/tokens/${encodeURIComponent(token)}:acknowledge`, "POST");
}
