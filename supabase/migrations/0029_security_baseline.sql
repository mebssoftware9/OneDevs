-- What a signed-in client may do, narrowed to what the app actually does.
--
-- Every rule the app shows has to hold at the database, because the app is
-- not the only client: its URL and public key ship inside every APK, and a
-- signed-in user can send any request those allow. Each section below closes
-- something that was reachable that way.

-- ------------------------------------------------------------ balances
-- balance_of(account) was granted to every signed-in user in 0002, when it
-- was how the app read a balance. The app reads coin_balance now, which
-- answers for the caller only, and every function that still calls balance_of
-- runs as its owner. The grant outlived its reason: any signed-in user could
-- pass a listing owner's id, which the Board shows, and read their balance.
revoke all on function public.balance_of(uuid) from public, anon, authenticated;

-- ----------------------------------------------------- retired missions
-- The campaign missions from 0002 were replaced by mission groups in 0016 and
-- nothing calls them. They stay in the schema, as 0016 chose, but no client
-- reaches them: record_day paid on the seconds the phone reported.
revoke all on function public.create_mission(uuid, int, int, int, int) from public, anon, authenticated;
revoke all on function public.join_mission(uuid, text) from public, anon, authenticated;
revoke all on function public.record_day(uuid, int) from public, anon, authenticated;
revoke all on function public.close_mission(uuid) from public, anon, authenticated;

-- -------------------------------------------------------------- listings
-- A developer could write every column of their own listing. created_at is
-- the Board's order after Spotlight, so a date in the future kept an app at
-- the top for free, and reward is the price of a test, which the app never
-- sets. Clients now write exactly the columns the app sends when it saves a
-- listing (an upsert on id, so insert and update both), and no others.
revoke insert, update on public.listings from authenticated;
grant insert (id, owner, package_name, title, category, channel, size_bytes, test_note,
              play_url, icon_url, public_listing, checked_at)
    on public.listings to authenticated;
grant update (id, owner, package_name, title, category, channel, size_bytes, test_note,
              play_url, icon_url, public_listing, checked_at)
    on public.listings to authenticated;

-- What testers are sent to and shown. A Play link is a Play link: the app
-- only opens these, and anything else on file was a link to somewhere else.
-- An icon lives in its owner's own folder of the icons bucket, which is the
-- only place the app uploads one; anywhere else, every phone showing the
-- listing would fetch whatever that address served. NOT VALID so rows saved
-- before this stay readable; every new write is held to it.
alter table public.listings
    drop constraint if exists listings_play_url_check,
    drop constraint if exists listings_icon_url_check,
    drop constraint if exists listings_test_note_check,
    drop constraint if exists listings_category_check,
    add constraint listings_play_url_check check (
        play_url is null or (
            char_length(play_url) <= 300
            and play_url ~ '^https://(www\.)?(play\.google\.com|groups\.google\.com)/[^[:space:]]*$'
        )
    ) not valid,
    add constraint listings_icon_url_check check (
        icon_url is null or (
            char_length(icon_url) <= 300
            and icon_url ~ ('^https://[a-z0-9]+\.supabase\.co/storage/v1/object/public/icons/'
                            || owner::text || '/[A-Za-z0-9-]+\.png$')
        )
    ) not valid,
    add constraint listings_test_note_check check (
        test_note is null or char_length(test_note) <= 500
    ) not valid,
    add constraint listings_category_check check (
        char_length(category) between 1 and 40
    ) not valid;

-- -------------------------------------------------------------- profiles
-- The app never writes a profile; the database does, as its owner. Without
-- this, a client could rename itself to anything in Mission Command, at any
-- length, and set its own account age and last-seen time.
revoke update on public.profiles from authenticated;

-- Profiles stay readable, but not when everyone was last seen. coin_balance
-- joins profiles on id as the caller, so id has to stay.
revoke select on public.profiles from authenticated;
grant select (id, handle, display_name, created_at) on public.profiles to authenticated;

-- ------------------------------------------------------------------ icons
-- The bucket is public, so whatever is in it is served from this project's
-- address to anyone. Icons are 512 px PNGs, which the app makes before upload.
update storage.buckets
set file_size_limit = 2 * 1024 * 1024,
    allowed_mime_types = array['image/png']
where id = 'icons';
