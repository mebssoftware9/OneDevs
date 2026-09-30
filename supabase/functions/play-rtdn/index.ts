// Google Play real-time developer notifications, pushed by Pub/Sub.
//
// Renewals, cancellations, expiries and refunds happen without the app open.
// Each notification is only a token and a hint; the facts are always fetched
// from Google again, so a forged or replayed message can at worst make the
// server re-read the truth.
//
// Deployed with --no-verify-jwt. The Pub/Sub push URL carries
// ?secret=<RTDN_SECRET>, and anything without it is refused.

import * as play from "../_shared/play.ts";
import { json, purchaseOwner, rpc } from "../_shared/db.ts";

Deno.serve(async (req) => {
  const secret = Deno.env.get("RTDN_SECRET");
  if (!secret || new URL(req.url).searchParams.get("secret") !== secret) {
    return json({ ok: false }, 401);
  }

  let note: any;
  try {
    const envelope = await req.json();
    note = JSON.parse(atob(envelope.message.data));
  } catch {
    // Acknowledged anyway: a message that cannot be read will not become
    // readable on the next delivery.
    return json({ ok: true, ignored: "unreadable" });
  }
  if (note.packageName && note.packageName !== play.packageName()) {
    return json({ ok: true, ignored: "other_package" });
  }

  try {
    const sub = note.subscriptionNotification;
    if (sub?.purchaseToken) {
      const facts = await play.subscription(sub.purchaseToken);
      const known = await purchaseOwner(sub.purchaseToken);
      const owner = known?.account ?? facts?.account;
      // Google's line items name the plan; the notification and the stored
      // row are fallbacks. With none of them there is nothing to record.
      const product = facts?.products[0] ?? sub.subscriptionId ?? known?.product_id;
      if (!facts || !owner || !product) return json({ ok: true, ignored: "unknown" });
      await rpc("subscription_update", {
        p_account: owner,
        p_token: sub.purchaseToken,
        p_product: product,
        p_state: facts.state,
        p_expires: facts.expires,
        p_order: facts.orderId,
        p_raw: facts.raw,
      });
      return json({ ok: true });
    }

    const voided = note.voidedPurchaseNotification;
    if (voided?.purchaseToken) {
      const known = await purchaseOwner(voided.purchaseToken);
      if (!known) return json({ ok: true, ignored: "unknown" });
      if (known.kind === "inapp") {
        await rpc("ghostline_cancel", { p_token: voided.purchaseToken });
      } else {
        await rpc("subscription_update", {
          p_account: known.account,
          p_token: voided.purchaseToken,
          p_product: known.product_id,
          p_state: "expired",
          p_expires: new Date().toISOString(),
        });
      }
      return json({ ok: true });
    }

    return json({ ok: true, ignored: "not_handled" });
  } catch (e) {
    // Not acknowledged: Pub/Sub delivers it again later.
    console.error(e);
    return json({ ok: false }, 500);
  }
});
