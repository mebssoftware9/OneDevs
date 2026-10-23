// GET ?c=<token>
//
// A credential's public verification link. Anyone holding the link may read
// it, so it is deployed with --no-verify-jwt; the token itself is the only
// key, and it is unguessable. Answers in plain text, or as JSON for a client
// that asks for application/json.
//
// The lookup runs as anon through verify_credential, the one Academy function
// anon may call, so this function cannot read anything a stranger could not.

import { describe, type Verified, wellFormed } from "../_shared/credential.ts";

const url = () => Deno.env.get("SUPABASE_URL")!;
const anonKey = () => Deno.env.get("SUPABASE_ANON_KEY")!;

async function lookup(token: string): Promise<Verified | null> {
  const res = await fetch(`${url()}/rest/v1/rpc/verify_credential`, {
    method: "POST",
    headers: { apikey: anonKey(), Authorization: `Bearer ${anonKey()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ p_token: token }),
  });
  if (!res.ok) throw new Error(`verify_credential failed: ${res.status}`);
  return await res.json();
}

Deno.serve(async (req) => {
  if (req.method !== "GET") return new Response("GET only\n", { status: 405 });
  const token = new URL(req.url).searchParams.get("c");
  let found: Verified | null = null;
  try {
    found = wellFormed(token) ? await lookup(token) : null;
  } catch {
    return new Response("OneDevs could not check this credential just now. Try again shortly.\n", {
      status: 503,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }
  const status = found ? 200 : 404;
  if ((req.headers.get("Accept") ?? "").includes("application/json")) {
    return new Response(JSON.stringify(found), { status, headers: { "Content-Type": "application/json" } });
  }
  return new Response(describe(found), {
    status,
    headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
  });
});
