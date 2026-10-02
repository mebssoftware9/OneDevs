// POST { token }, with the caller's own access token as Authorization.
//
// Asks Google what the Play Integrity token says, judges it, and records the
// verdict against the session that asked: its session_id, read from the
// access token the auth server has just confirmed. The token's request hash
// must be the SHA-256 of that same access token, so a token made for one
// session opens no other.

import { json, rpc, userOf } from "../_shared/db.ts";
import { decode, judge, sessionOf, sha256Hex } from "../_shared/integrity.ts";
import { packageName } from "../_shared/play.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: false, reason: "method" }, 405);
  const user = await userOf(req);
  if (!user) return json({ ok: false, reason: "signed_out" }, 401);
  const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const session = sessionOf(bearer);
  if (!session) return json({ ok: false, reason: "no_session" }, 400);

  let token: unknown;
  try {
    token = (await req.json())?.token;
  } catch {
    return json({ ok: false, reason: "bad_request" }, 400);
  }
  if (typeof token !== "string" || token.length < 20 || token.length > 20_000) {
    return json({ ok: false, reason: "bad_request" }, 400);
  }

  const payload = await decode(token);
  if (!payload) return json({ ok: true, verified: false, reason: "undecodable" });

  const verdict = judge(payload, {
    packageName: packageName(),
    requestHash: await sha256Hex(bearer),
    certDigests: (Deno.env.get("PLAY_CERT_DIGESTS") ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    now: Date.now(),
  });
  const saved = await rpc("record_verdict", {
    p_account: user,
    p_session: session,
    p_passed: verdict.passed,
    p_verdict: { ...verdict.summary, reason: verdict.reason },
  });
  return json({ ok: true, verified: verdict.passed, reason: verdict.reason, until: saved?.until ?? null });
});
