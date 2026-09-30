import { signedAssertion, subscriptionState } from "./play.ts";

function assert(ok: boolean, what: string) {
  if (!ok) throw new Error(what);
}

const pem = (der: ArrayBuffer) =>
  `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...new Uint8Array(der)))}\n-----END PRIVATE KEY-----\n`;

const unb64url = (s: string) => {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
};

Deno.test("the assertion is a JWT Google can verify", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const key = pem(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const jwt = await signedAssertion({ client_email: "sa@x.iam.gserviceaccount.com", private_key: key }, 1000);
  const [h, c, s] = jwt.split(".");
  const claims = JSON.parse(new TextDecoder().decode(unb64url(c)));
  assert(JSON.parse(new TextDecoder().decode(unb64url(h))).alg === "RS256", "alg");
  assert(claims.iss === "sa@x.iam.gserviceaccount.com" && claims.exp === 4600, "claims");
  assert(claims.scope === "https://www.googleapis.com/auth/androidpublisher", "scope");
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    pair.publicKey,
    unb64url(s),
    new TextEncoder().encode(`${h}.${c}`),
  );
  assert(ok, "signature verifies");
});

Deno.test("grace period still counts as paid, on hold does not", () => {
  assert(subscriptionState("SUBSCRIPTION_STATE_IN_GRACE_PERIOD") === "active", "grace");
  assert(subscriptionState("SUBSCRIPTION_STATE_CANCELED") === "cancelled", "cancelled");
  assert(subscriptionState("SUBSCRIPTION_STATE_ON_HOLD") === "expired", "hold");
  assert(subscriptionState("SUBSCRIPTION_STATE_PENDING") === "pending", "pending");
});
