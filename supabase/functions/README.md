# Payments: Premium and Pro

Nothing in the app can grant a plan or start a run on its own. The flow is:

1. The app opens Google Play's purchase sheet.
2. The app sends the purchase token to the `play-purchases` function.
3. The function asks Google whether the purchase is real, and whose it is.
4. Only then does it call the database: `subscription_update` (Premium, Pro) or
   `ghostline_start` (Ghostline). Those functions only run for the service role.

Renewals, cancellations and refunds arrive from Google at `play-rtdn`, whether
or not the app is open.

## Products

| Type | Product ID | Base plan / price |
|---|---|---|
| Subscription | `premium` | `monthly`, auto-renewing, 1 month, $19.99 |
| Subscription | `pro` | `monthly`, auto-renewing, 1 month, $39.99 |

The IDs are constants in `Products` in the app and must match Play Console
exactly; a product ID can never be reused once created. `lab_pro` and
`ghostline` are retired and do not exist in Play.

Each paid month of Premium or Pro pays 500 or 1,000 DevCoins, once per Google
order (`plan_bonuses`), so a retried verification or a repeated notification
never pays it twice. What the plans buy, testing cycles, is described in
`supabase/migrations/0026_testing_cycles.sql`.

## How Google reaches the server

- Google Cloud project `onedevs-510000` has the Google Play Android Developer
  API enabled.
- The functions sign in to that API as the service account
  `play-purchases@onedevs-510000.iam.gserviceaccount.com`. Play Console grants
  it **View financial data** and **Manage orders and subscriptions**.
- Real-time notifications: Play publishes to the Pub/Sub topic
  `play-notifications` (Google's
  `google-play-developer-notifications@system.gserviceaccount.com` holds
  Pub/Sub Publisher on it). The push subscription `play-rtdn` forwards each
  one to `https://<project-ref>.supabase.co/functions/v1/play-rtdn?secret=<RTDN_SECRET>`.
  `play-rtdn` is deployed with `--no-verify-jwt`, since Google carries no
  Supabase JWT; the secret in the URL is what refuses everyone else.

## Secrets

Set in Supabase (Edge Functions → Secrets), never in the repo:

| Name | What it is |
|---|---|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | The service account's JSON key |
| `PLAY_PACKAGE_NAME` | `com.devbangs.onedevs` |
| `RTDN_SECRET` | The shared secret in the push subscription's URL |

## Deploy

```bash
npx supabase functions deploy play-purchases
npx supabase functions deploy play-rtdn --no-verify-jwt
npx supabase functions deploy delete-account
```

## Account deletion

`delete-account` deletes the signed-in caller's account: their icons in
storage first, then their sign-in through the auth admin API. Every table
that belongs to an account cascades from it; mission chat lines stay for the
other members with no author. The app calls it from Profile → Your data →
Delete account. The public page Google Play links to, for people without the
app, is `https://mebs.app/delete/onedevs`, from `lib/onedevs-legal.ts` in the
mbanguraproject-ai/mebs site, alongside OneDevs' privacy, terms and refund
pages.

## Testing purchases

License testers (Play Console → Settings → License testing) are never
charged, and their subscriptions renew every few minutes. Billing only works
in a build installed from Play, such as the internal testing track.

## Privacy

Mission check-ins send the phone's model and Android version, which a
Ghostline owner sees counted on their dashboard, never with names. The privacy
policy and the Data safety form (device information shared with other users)
say so.

## Tests

```bash
npx deno test supabase/functions/_shared/play_test.ts
```
