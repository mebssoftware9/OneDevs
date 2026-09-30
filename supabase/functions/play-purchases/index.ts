// Verifies a Google Play purchase and grants what it paid for.
//
// The app calls this after every purchase, and again on launch for any
// purchase Play still reports, so a verification lost to a bad connection is
// retried rather than lost. Everything is idempotent on the purchase token.
//
// POST { kind: "subs", productId: "lab_pro", purchaseToken }
// POST { kind: "inapp", productId: "ghostline", purchaseToken }
//
// The listing a Ghostline run is for is read from Google's copy of the
// purchase (obfuscatedProfileId), not from the request, so it cannot be
// changed after paying. The account must match obfuscatedAccountId, so a
// token cannot be replayed from someone else's account.

import * as play from "../_shared/play.ts";
import { json, rpc, userOf } from "../_shared/db.ts";

export const SUBSCRIPTIONS = ["lab_pro"];
export const PRODUCTS = ["ghostline"];

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: false, reason: "method" }, 405);
  const user = await userOf(req);
  if (!user) return json({ ok: false, reason: "signed_out" }, 401);

  let body: { kind?: string; productId?: string; purchaseToken?: string };
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, reason: "bad_request" }, 400);
  }
  const { kind, productId, purchaseToken } = body;
  if (!productId || !purchaseToken) return json({ ok: false, reason: "bad_request" }, 400);

  try {
    if (kind === "subs" && SUBSCRIPTIONS.includes(productId)) {
      const facts = await play.subscription(purchaseToken);
      if (!facts) return json({ ok: false, reason: "unknown_purchase" }, 404);
      if (facts.account !== user) return json({ ok: false, reason: "not_your_purchase" }, 403);
      if (!facts.products.includes(productId)) return json({ ok: false, reason: "wrong_product" }, 400);

      const result = await rpc("subscription_update", {
        p_account: user,
        p_token: purchaseToken,
        p_product: productId,
        p_state: facts.state,
        p_expires: facts.expires,
        p_order: facts.orderId,
        p_raw: facts.raw,
      });
      if (result.ok && !facts.acknowledged && facts.state === "active") {
        await play.acknowledgeSubscription(productId, purchaseToken);
      }
      return json(result);
    }

    if (kind === "inapp" && PRODUCTS.includes(productId)) {
      const facts = await play.product(productId, purchaseToken);
      if (!facts) return json({ ok: false, reason: "unknown_purchase" }, 404);
      if (facts.pending) return json({ ok: false, reason: "pending" });
      if (!facts.purchased) return json({ ok: false, reason: "not_purchased" });
      if (facts.account !== user) return json({ ok: false, reason: "not_your_purchase" }, 403);
      if (!facts.profile) return json({ ok: false, reason: "no_app_chosen" }, 400);

      const recorded = await rpc("purchase_record", {
        p_account: user,
        p_token: purchaseToken,
        p_product: productId,
        p_listing: facts.profile,
        p_order: facts.orderId,
        p_raw: facts.raw,
      });
      if (!recorded.ok) return json(recorded, 409);

      const run = await rpc("ghostline_start", {
        p_account: user,
        p_listing: facts.profile,
        p_token: purchaseToken,
      });
      // Acknowledged only once the run exists. A purchase that could not
      // start one is left unacknowledged, and Google refunds it by itself.
      if (run.ok && !facts.acknowledged) await play.acknowledgeProduct(productId, purchaseToken);
      return json(run);
    }

    return json({ ok: false, reason: "unknown_product" }, 400);
  } catch (e) {
    console.error(e);
    return json({ ok: false, reason: "unavailable" }, 503);
  }
});
