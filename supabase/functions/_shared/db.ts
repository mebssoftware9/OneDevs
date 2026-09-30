// The database as the service role: only these functions may grant a plan
// or start a Ghostline run.

const url = () => Deno.env.get("SUPABASE_URL")!;
const serviceKey = () => Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

export async function rpc(fn: string, args: Record<string, unknown>): Promise<any> {
  const res = await fetch(`${url()}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      apikey: serviceKey(),
      Authorization: `Bearer ${serviceKey()}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(args),
  });
  if (!res.ok) throw new Error(`${fn} failed: ${res.status} ${await res.text()}`);
  return await res.json();
}

/** Who a purchase token already belongs to, if anyone. */
export async function purchaseOwner(token: string): Promise<{ account: string; kind: string; product_id: string } | null> {
  const res = await fetch(
    `${url()}/rest/v1/play_purchases?purchase_token=eq.${encodeURIComponent(token)}&select=account,kind,product_id`,
    { headers: { apikey: serviceKey(), Authorization: `Bearer ${serviceKey()}` } },
  );
  if (!res.ok) return null;
  const rows = await res.json();
  return rows[0] ?? null;
}

/** The signed-in user behind a request, from their own access token. */
export async function userOf(req: Request): Promise<string | null> {
  const auth = req.headers.get("Authorization");
  if (!auth) return null;
  const res = await fetch(`${url()}/auth/v1/user`, {
    headers: { apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? serviceKey(), Authorization: auth },
  });
  if (!res.ok) return null;
  return (await res.json()).id ?? null;
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
