# Payments: Premium and Pro

Nothing in the app can grant a plan or start a run on its own. The flow is:

1. The app opens Google Play's purchase sheet.
2. The app sends the purchase token to the `play-purchases` function.
3. The function asks Google whether the purchase is real, and whose it is.
4. Only then does it call the database: `subscription_update` (Premium, Pro) or
   `ghostline_start` (Ghostline). Those functions only run for the service role.

Renewals, cancellations and refunds arrive from Google at `play-rtdn`, whether
or not the app is open.

## 1. Play Console products

Monetize → Products.

| Type | Product ID | Base plans / price |
|---|---|---|
| Subscription | `premium` | base plan `monthly` (auto-renewing, 1 month, $19.99) |
| Subscription | `pro` | base plan `monthly` (auto-renewing, 1 month, $39.99) |

The IDs must match exactly; they are constants in `Products` in the app, and
a product ID can never be reused once created. `lab_pro` and `ghostline` are
being retired: do not create them.

## 2. Service account

1. In Google Cloud (the project linked to Play Console), enable the
   **Google Play Android Developer API**.
2. Create a service account and download a JSON key for it.
3. In Play Console → Users and permissions, invite the service account's email
   with **View financial data** and **Manage orders and subscriptions**.
   Permissions can take up to 24 hours to apply.

## 3. Secrets and deploy

```bash
npx supabase secrets set GOOGLE_SERVICE_ACCOUNT_JSON="$(cat path/to/key.json)"
npx supabase secrets set PLAY_PACKAGE_NAME=com.devbangs.onedevs
npx supabase secrets set RTDN_SECRET="$(openssl rand -hex 24)"
npx supabase functions deploy play-purchases
npx supabase functions deploy play-rtdn --no-verify-jwt
```

## 4. Real-time notifications

1. In Google Cloud Pub/Sub, create a topic, e.g. `play-notifications`.
2. Give `google-play-developer-notifications@system.gserviceaccount.com` the
   **Pub/Sub Publisher** role on that topic.
3. Create a **push** subscription on the topic with the endpoint
   `https://<project-ref>.supabase.co/functions/v1/play-rtdn?secret=<RTDN_SECRET>`.
4. Play Console → Monetization setup → Real-time developer notifications: enter
   the topic name and press **Send test notification**.

## 5. Testing purchases

- Add your Google account under Play Console → Settings → License testing.
  License testers are never charged, and subscriptions renew every few minutes.
- Billing only works in a build installed from Play: upload to the internal
  testing track and install it from there.

## 6. Privacy

Mission check-ins now send the phone's model and Android version, which a
Ghostline owner sees counted on their dashboard (never with names). Mention
this in the privacy policy, and in the Data safety form under device
information shared with other users.

## Tests

```bash
npx deno test supabase/functions/_shared/play_test.ts
```
