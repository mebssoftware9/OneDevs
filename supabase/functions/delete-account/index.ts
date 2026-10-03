// POST {}, with the caller's own access token as Authorization.
//
// Deletes the caller's account and everything OneDevs holds about them. The
// person is read from their own token, so nobody can delete anyone else.
//
//   1. Their app icons in storage (icons/<user id>/...), through the Storage
//      API, which is the only way storage files may be removed.
//   2. Their sign-in, through the auth admin API. Every table that belongs to
//      an account references it with ON DELETE CASCADE, so the profile,
//      listings, coins, tests, missions, feedback, plan records and Lab apps
//      go with it. Chat lines they wrote in missions stay for the other
//      members, with no author.
//
// A Google Play subscription is Google's, not ours: deleting the account
// does not cancel it. The app says so before asking for confirmation.

import { json, userOf } from "../_shared/db.ts";

const url = () => Deno.env.get("SUPABASE_URL")!;
const serviceKey = () => Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = () => ({ apikey: serviceKey(), Authorization: `Bearer ${serviceKey()}` });

/** Removes every icon in the account's folder. */
async function deleteIcons(user: string): Promise<boolean> {
  for (;;) {
    const listed = await fetch(`${url()}/storage/v1/object/list/icons`, {
      method: "POST",
      headers: { ...admin(), "Content-Type": "application/json" },
      body: JSON.stringify({ prefix: `${user}/`, limit: 100, offset: 0 }),
    });
    if (!listed.ok) return false;
    const files: { name: string }[] = await listed.json();
    if (files.length === 0) return true;
    const removed = await fetch(`${url()}/storage/v1/object/icons`, {
      method: "DELETE",
      headers: { ...admin(), "Content-Type": "application/json" },
      body: JSON.stringify({ prefixes: files.map((f) => `${user}/${f.name}`) }),
    });
    if (!removed.ok) return false;
  }
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: false, reason: "method" }, 405);
  const user = await userOf(req);
  if (!user) return json({ ok: false, reason: "signed_out" }, 401);

  if (!(await deleteIcons(user))) return json({ ok: false, reason: "storage" }, 502);

  const gone = await fetch(`${url()}/auth/v1/admin/users/${user}`, {
    method: "DELETE",
    headers: admin(),
  });
  if (!gone.ok && gone.status !== 404) return json({ ok: false, reason: "auth" }, 502);
  return json({ ok: true });
});
