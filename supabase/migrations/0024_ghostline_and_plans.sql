-- Plans and Ghostline: how OneDevs makes money without making anyone's
-- mission worse.
--
-- Lab plans
--   Free keeps one app in the Lab for good. Lab Pro (a Play subscription)
--   takes any number. The app a free account analyses is claimed on the
--   server, so reinstalling or clearing data does not reset it.
--
-- Ghostline
--   A paid closed test. The app is placed in the mission that is filling, as
--   an extra seat: the sixteen members keep their fifteen testers each, and
--   see one more app to test, with nothing marking it as paid. Members who
--   use it are paid in DevCoins by OneDevs. Every fourth day the app is placed
--   in the next filling mission as well, which is how a tester who drops out
--   gets replaced. The owner sees none of this on the boards -- the app leaves
--   them for the run -- only a live dashboard.
--
--   If the run reaches its end with fewer than twelve people having used the
--   app, it is extended once by seven days, with the boosts carrying on.
--
-- Purchases are verified by the play-purchases Edge Function against Google
-- Play, which then calls the service-only functions here. Nothing a phone
-- sends can grant a plan or start a run.

-- ------------------------------------------------------------------ ledger

alter table public.coin_entries drop constraint if exists coin_entries_reason_check;
alter table public.coin_entries add constraint coin_entries_reason_check
    check (reason in (
        'grant', 'test_reward', 'test_payment',
        'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund',
        'mission_entry', 'ghostline_reward'
    ));

-- The phone a member tested on, for the Ghostline owner's dashboard.
alter table public.mission_checkins add column if not exists device_model text;
alter table public.mission_checkins add column if not exists android_sdk int;

-- ---------------------------------------------------------------- purchases

create table if not exists public.play_purchases (
    purchase_token text primary key,
    account uuid not null references public.profiles (id) on delete cascade,
    product_id text not null,
    kind text not null check (kind in ('subs', 'inapp')),
    state text not null check (state in ('active', 'cancelled', 'expired', 'refunded', 'pending')),
    expires_at timestamptz,
    listing_id uuid references public.listings (id) on delete set null,
    order_id text,
    raw jsonb,
    verified_at timestamptz not null default now()
);

create index if not exists play_purchases_account on public.play_purchases (account);

/** What an account has: Lab Pro until a date, from a subscription or a run. */
create table if not exists public.entitlements (
    id bigint generated always as identity primary key,
    account uuid not null references public.profiles (id) on delete cascade,
    product text not null check (product in ('lab_pro')),
    source text not null check (source in ('subscription', 'ghostline', 'grant')),
    starts_at timestamptz not null default now(),
    expires_at timestamptz not null,
    purchase_token text,
    created_at timestamptz not null default now(),
    unique (source, purchase_token)
);

create index if not exists entitlements_account_expiry
    on public.entitlements (account, expires_at desc);

/** Apps a Lab has analysed. A free Lab keeps the first one. */
create table if not exists public.lab_apps (
    account uuid not null references public.profiles (id) on delete cascade,
    package_name text not null
        check (package_name ~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'),
    chosen_at timestamptz not null default now(),
    primary key (account, package_name)
);

alter table public.play_purchases enable row level security;
alter table public.entitlements enable row level security;
alter table public.lab_apps enable row level security;
revoke all on public.play_purchases from anon, authenticated;
revoke all on public.entitlements from anon, authenticated;
revoke all on public.lab_apps from anon, authenticated;

-- ------------------------------------------------------------------- plans

/** Until when an account has Lab Pro, or null. */
create or replace function public.pro_until(p_account uuid) returns timestamptz
language sql stable security definer set search_path = '' as $$
    select max(expires_at) from public.entitlements
    where account = p_account and product = 'lab_pro' and expires_at > now();
$$;

revoke all on function public.pro_until(uuid) from public, anon, authenticated;

/** The caller's plan, and the app a free Lab is kept to. */
create or replace function public.my_plan() returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'plan', case when public.pro_until(auth.uid()) is null then 'free' else 'pro' end,
        'pro_until', public.pro_until(auth.uid()),
        'pro_source', (
            select e.source from public.entitlements e
            where e.account = auth.uid() and e.expires_at > now()
            order by e.expires_at desc limit 1
        ),
        'lab_app', (
            select a.package_name from public.lab_apps a
            where a.account = auth.uid() order by a.chosen_at limit 1
        )
    );
$$;

revoke all on function public.my_plan() from public, anon;
grant execute on function public.my_plan() to authenticated;

/**
 * Claims an app for the caller's Lab.
 *
 * { ok: true } when the Lab may analyse it; { ok: false, reason: 'upgrade',
 * lab_app } when a free Lab already belongs to another app. Pro accounts
 * claim anything, and the apps they claimed are remembered, but a Lab that
 * goes back to free keeps only its first.
 */
create or replace function public.lab_claim_app(p_package text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_first text;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if p_package is null
       or p_package !~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$' then
        return jsonb_build_object('ok', false, 'reason', 'bad_package');
    end if;

    select package_name into v_first from public.lab_apps
    where account = auth.uid() order by chosen_at limit 1;

    if v_first is null or v_first = p_package or public.pro_until(auth.uid()) is not null then
        insert into public.lab_apps (account, package_name)
        values (auth.uid(), p_package)
        on conflict do nothing;
        return jsonb_build_object('ok', true);
    end if;
    return jsonb_build_object('ok', false, 'reason', 'upgrade', 'lab_app', v_first);
end $$;

revoke all on function public.lab_claim_app(text) from public, anon;
grant execute on function public.lab_claim_app(text) to authenticated;

-- ---------------------------------------------------------------- ghostline

create table if not exists public.ghostline_runs (
    id uuid primary key default gen_random_uuid(),
    account uuid not null references public.profiles (id) on delete cascade,
    listing_id uuid not null references public.listings (id) on delete cascade,
    purchase_token text unique,
    state text not null default 'running'
        check (state in ('running', 'extended', 'completed', 'cancelled')),
    started_at timestamptz not null default now(),
    ends_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null default now()
);

-- One live run per app.
create unique index if not exists ghostline_runs_one_live
    on public.ghostline_runs (listing_id) where state in ('running', 'extended');
create index if not exists ghostline_runs_account on public.ghostline_runs (account, created_at desc);

create table if not exists public.ghostline_seats (
    run_id uuid not null references public.ghostline_runs (id) on delete cascade,
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    kind text not null check (kind in ('first', 'boost')),
    placed_at timestamptz not null default now(),
    primary key (run_id, mission_id)
);

create index if not exists ghostline_seats_mission on public.ghostline_seats (mission_id);

alter table public.ghostline_runs enable row level security;
alter table public.ghostline_seats enable row level security;
revoke all on public.ghostline_runs from anon, authenticated;
revoke all on public.ghostline_seats from anon, authenticated;

/** DevCoins a member earns the first time each day they use a Ghostline app. */
create or replace function public.ghostline_reward() returns int
language sql immutable as $$ select 5 $$;

/** People needed, and days, before a run counts as done. */
create or replace function public.ghostline_testers_needed() returns int
language sql immutable as $$ select 12 $$;

/**
 * Puts a run's app in the mission that is filling. Skips a mission the app
 * is already in, as a seat or as a ghost; the next tick tries the next one.
 * Returns whether it was placed.
 */
create or replace function public.ghostline_place(p_run uuid, p_kind text) returns boolean
language plpgsql security definer set search_path = '' as $$
declare
    v_listing uuid;
    v_group uuid;
    v_rows int;
begin
    select listing_id into v_listing from public.ghostline_runs
    where id = p_run and state in ('running', 'extended');
    if v_listing is null then return false; end if;

    v_group := public.recruiting_group();
    if exists (select 1 from public.mission_seats where mission_id = v_group and listing_id = v_listing) then
        return false;
    end if;
    insert into public.ghostline_seats (run_id, mission_id, kind)
    values (p_run, v_group, p_kind)
    on conflict do nothing;
    get diagnostics v_rows = row_count;
    return v_rows = 1;
end $$;

/** People who have used a run's app since it started, in any mission. */
create or replace function public.ghostline_testers(p_run uuid) returns int
language sql stable security definer set search_path = '' as $$
    select count(distinct c.member)::int
    from public.ghostline_runs r
    join public.mission_checkins c on c.listing_id = r.listing_id and c.at >= r.started_at
    where r.id = p_run;
$$;

/**
 * Starts a paid run. Service role only: called by the Edge Function after
 * Google Play has confirmed the purchase.
 *
 * Idempotent on the purchase token: the same token asked again returns the
 * run it started. { ok: true, run } or { ok: false, reason }.
 */
create or replace function public.ghostline_start(
    p_account uuid,
    p_listing uuid,
    p_token text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_run public.ghostline_runs%rowtype;
begin
    select * into v_run from public.ghostline_runs where purchase_token = p_token;
    if v_run.id is not null then
        return jsonb_build_object('ok', true, 'run', v_run.id, 'repeat', true);
    end if;
    if not exists (
        select 1 from public.listings
        where id = p_listing and owner = p_account and channel = 'testing'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'not_your_testing_app');
    end if;
    if exists (
        select 1 from public.ghostline_runs
        where listing_id = p_listing and state in ('running', 'extended')
    ) then
        return jsonb_build_object('ok', false, 'reason', 'already_running');
    end if;

    insert into public.ghostline_runs (account, listing_id, purchase_token, ends_at)
    values (p_account, p_listing, p_token, now() + interval '14 days')
    returning * into v_run;

    perform public.ghostline_place(v_run.id, 'first');

    -- A month of the Lab comes with every run.
    insert into public.entitlements (account, product, source, expires_at, purchase_token)
    values (p_account, 'lab_pro', 'ghostline',
            greatest(now(), coalesce(public.pro_until(p_account), now())) + interval '30 days',
            p_token)
    on conflict (source, purchase_token) do nothing;

    return jsonb_build_object('ok', true, 'run', v_run.id);
end $$;

/** A refunded or voided run stops, and its month of Lab Pro with it. */
create or replace function public.ghostline_cancel(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    update public.ghostline_runs
    set state = 'cancelled', completed_at = now()
    where purchase_token = p_token and state in ('running', 'extended');
    update public.entitlements set expires_at = least(expires_at, now())
    where source = 'ghostline' and purchase_token = p_token;
    update public.play_purchases set state = 'refunded' where purchase_token = p_token;
    return jsonb_build_object('ok', true);
end $$;

/**
 * Records a verified Play subscription and what it entitles. Service role
 * only. The expiry is Google's; a cancelled subscription keeps running to it.
 */
create or replace function public.subscription_update(
    p_account uuid,
    p_token text,
    p_product text,
    p_state text,
    p_expires timestamptz,
    p_order text default null,
    p_raw jsonb default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_until timestamptz;
begin
    select account into v_owner from public.play_purchases where purchase_token = p_token;
    if v_owner is not null and v_owner <> p_account then
        return jsonb_build_object('ok', false, 'reason', 'token_belongs_to_another_account');
    end if;

    insert into public.play_purchases
        (purchase_token, account, product_id, kind, state, expires_at, order_id, raw, verified_at)
    values (p_token, p_account, p_product, 'subs', p_state, p_expires, p_order, p_raw, now())
    on conflict (purchase_token) do update
        set state = excluded.state, expires_at = excluded.expires_at,
            order_id = coalesce(excluded.order_id, public.play_purchases.order_id),
            raw = excluded.raw, verified_at = now();

    v_until := case when p_state in ('active', 'cancelled') then p_expires else now() end;
    insert into public.entitlements (account, product, source, expires_at, purchase_token)
    values (p_account, 'lab_pro', 'subscription', v_until, p_token)
    on conflict (source, purchase_token) do update set expires_at = excluded.expires_at;

    return jsonb_build_object('ok', true, 'pro_until', public.pro_until(p_account));
end $$;

/** Records a verified one-time purchase before its run starts. Service role only. */
create or replace function public.purchase_record(
    p_account uuid,
    p_token text,
    p_product text,
    p_listing uuid,
    p_order text default null,
    p_raw jsonb default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
begin
    select account into v_owner from public.play_purchases where purchase_token = p_token;
    if v_owner is not null and v_owner <> p_account then
        return jsonb_build_object('ok', false, 'reason', 'token_belongs_to_another_account');
    end if;
    insert into public.play_purchases
        (purchase_token, account, product_id, kind, state, listing_id, order_id, raw)
    values (p_token, p_account, p_product, 'inapp', 'active', p_listing, p_order, p_raw)
    on conflict (purchase_token) do nothing;
    return jsonb_build_object('ok', true);
end $$;

/**
 * Hourly: boosts every fourth day, and the end of each run.
 *
 * A run that ends with fewer than twelve people having used the app is
 * extended once by seven days rather than closed; that is the guarantee.
 */
create or replace function public.ghostline_tick() returns void
language plpgsql security definer set search_path = '' as $$
declare
    r public.ghostline_runs%rowtype;
    v_due int;
    v_done int;
begin
    for r in select * from public.ghostline_runs where state in ('running', 'extended') loop
        if now() >= r.ends_at then
            if r.state = 'running'
               and public.ghostline_testers(r.id) < public.ghostline_testers_needed() then
                update public.ghostline_runs
                set state = 'extended', ends_at = r.ends_at + interval '7 days'
                where id = r.id;
            else
                update public.ghostline_runs
                set state = 'completed', completed_at = now()
                where id = r.id;
            end if;
            continue;
        end if;

        -- The first placement can miss: the app was already sitting in the
        -- mission that was filling. It goes into the next one.
        if not exists (select 1 from public.ghostline_seats where run_id = r.id) then
            perform public.ghostline_place(r.id, 'first');
            continue;
        end if;

        v_due := floor(extract(epoch from now() - r.started_at) / 86400 / 4)::int;
        select count(*) into v_done from public.ghostline_seats where run_id = r.id and kind = 'boost';
        if v_due > v_done then
            perform public.ghostline_place(r.id, 'boost');
        end if;
    end loop;
end $$;

/**
 * The owner's view of their runs: time, people, phones. Never who: members
 * are counted and their phones described, but not named.
 */
create or replace function public.ghostline_dashboard() returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(x.j order by x.created_at desc), '[]'::jsonb)
    from (
        select r.created_at, jsonb_build_object(
            'id', r.id,
            'state', r.state,
            'started_at', r.started_at,
            'ends_at', r.ends_at,
            'server_now', now(),
            'listing', l.id,
            'title', l.title,
            'icon_url', l.icon_url,
            'package_name', l.package_name,
            'needed', public.ghostline_testers_needed(),
            'testers', public.ghostline_testers(r.id),
            'active_today', (
                select count(distinct c.member)::int from public.mission_checkins c
                where c.listing_id = r.listing_id and c.at >= r.started_at
                  and c.day = (now() at time zone 'utc')::date
            ),
            'missions', (select count(*)::int from public.ghostline_seats s where s.run_id = r.id),
            'boosts', (select count(*)::int from public.ghostline_seats s
                       where s.run_id = r.id and s.kind = 'boost'),
            'next_boost_at', case when r.state in ('running', 'extended') then
                r.started_at + (floor(extract(epoch from now() - r.started_at) / 86400 / 4) + 1)
                    * interval '4 days' end,
            'installs', coalesce((
                select jsonb_agg(jsonb_build_object('at', f.at, 'model', f.model, 'sdk', f.sdk)
                                 order by f.at desc)
                from (
                    select distinct on (c.member) c.at, c.device_model as model, c.android_sdk as sdk
                    from public.mission_checkins c
                    where c.listing_id = r.listing_id and c.at >= r.started_at
                    order by c.member, c.at
                ) f
            ), '[]'::jsonb),
            'daily', coalesce((
                select jsonb_agg(jsonb_build_object('day', d.day, 'testers', d.n) order by d.day)
                from (
                    select c.day, count(distinct c.member)::int as n
                    from public.mission_checkins c
                    where c.listing_id = r.listing_id and c.at >= r.started_at
                    group by c.day
                ) d
            ), '[]'::jsonb)
        ) as j
        from public.ghostline_runs r
        join public.listings l on l.id = r.listing_id
        where r.account = auth.uid()
    ) x;
$$;

revoke all on function public.ghostline_reward() from public, anon, authenticated;
revoke all on function public.ghostline_testers_needed() from public, anon, authenticated;
revoke all on function public.ghostline_place(uuid, text) from public, anon, authenticated;
revoke all on function public.ghostline_testers(uuid) from public, anon, authenticated;
revoke all on function public.ghostline_start(uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.ghostline_cancel(text) from public, anon, authenticated;
revoke all on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
    from public, anon, authenticated;
revoke all on function public.purchase_record(uuid, text, text, uuid, text, jsonb)
    from public, anon, authenticated;
revoke all on function public.ghostline_tick() from public, anon, authenticated;
revoke all on function public.ghostline_dashboard() from public, anon;
grant execute on function public.ghostline_dashboard() to authenticated;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.ghostline_start(uuid, uuid, text) to service_role;
        grant execute on function public.ghostline_cancel(text) to service_role;
        grant execute on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
            to service_role;
        grant execute on function public.purchase_record(uuid, text, text, uuid, text, jsonb)
            to service_role;
        -- The notification handler looks tokens up before it knows whose they are.
        grant select on public.play_purchases to service_role;
    end if;
end $$;

-- -------------------------------------------------------------- the boards

-- An app on a Ghostline run leaves both boards until the run is over.
create or replace view public.board_listings as
select l.*
from public.listings l
left join public.account_balances ab on ab.account = l.owner
where coalesce(ab.balance, 0) - (
    select coalesce(sum(s.reward), 0)
    from public.test_sessions s
    where s.owner = l.owner and s.state = 'open' and s.expires_at > now()
) >= l.reward
and not exists (
    select 1 from public.tests t
    where t.listing_id = l.id and t.tester = auth.uid()
)
and not exists (
    select 1 from public.ghostline_runs g
    where g.listing_id = l.id and g.state in ('running', 'extended')
);

grant select on public.board_listings to authenticated;

-- ---------------------------------------------------------------- checkins


drop function if exists public.mission_checkin(uuid, uuid, int, text);

/**
 * Records that the caller used another app in their mission today: a
 * member's, or a Ghostline app placed there. The phone's model and Android
 * version travel with it, for the Ghostline owner's dashboard.
 *
 * The first check-in each day on a Ghostline app pays the member.
 */
create or replace function public.mission_checkin(
    p_mission uuid,
    p_listing uuid,
    p_seconds int,
    p_device text,
    p_model text default null,
    p_sdk int default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_group public.mission_groups%rowtype;
    v_owner uuid;
    v_ghost boolean := false;
    v_inserted boolean;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    select * into v_group from public.mission_groups where id = p_mission;
    if v_group.id is null then
        return jsonb_build_object('ok', false, 'reason', 'missing');
    end if;
    if not exists (
        select 1 from public.mission_seats where mission_id = p_mission and member = auth.uid()
    ) then
        return jsonb_build_object('ok', false, 'reason', 'not_member');
    end if;
    if v_group.state = 'running'
       and (now() at time zone 'utc')::date - v_group.started_on >= v_group.window_days then
        return jsonb_build_object('ok', false, 'reason', 'elapsed');
    end if;

    select l.owner into v_owner
    from public.mission_seats s join public.listings l on l.id = s.listing_id
    where s.mission_id = p_mission and s.listing_id = p_listing;
    if v_owner is null then
        select l.owner into v_owner
        from public.ghostline_seats gs
        join public.ghostline_runs r on r.id = gs.run_id and r.state in ('running', 'extended')
        join public.listings l on l.id = r.listing_id
        where gs.mission_id = p_mission and r.listing_id = p_listing;
        v_ghost := v_owner is not null;
    end if;
    if v_owner is null then
        return jsonb_build_object('ok', false, 'reason', 'not_in_mission');
    end if;
    if v_owner = auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'own_app');
    end if;
    if p_seconds is null or p_seconds < 32 then
        return jsonb_build_object('ok', false, 'reason', 'too_short');
    end if;

    insert into public.mission_checkins
        (mission_id, member, listing_id, seconds, device, device_model, android_sdk)
    values (p_mission, auth.uid(), p_listing, least(p_seconds, 86400), coalesce(p_device, ''),
            left(p_model, 80), case when p_sdk between 1 and 200 then p_sdk end)
    on conflict (mission_id, member, listing_id, day) do update
        set seconds = greatest(public.mission_checkins.seconds, excluded.seconds)
    returning (xmax = 0) into v_inserted;

    -- Paid once per app per day, not once per mission: the same app in two
    -- of your missions is still one app used.
    if v_ghost and v_inserted and not exists (
        select 1 from public.coin_entries
        where account = auth.uid() and reason = 'ghostline_reward' and ref = p_listing
          and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'
    ) then
        insert into public.coin_entries (account, delta, reason, ref)
        values (auth.uid(), public.ghostline_reward(), 'ghostline_reward', p_listing);
        return jsonb_build_object('ok', true, 'coins', public.ghostline_reward());
    end if;
    return jsonb_build_object('ok', true);
end $$;

revoke all on function public.mission_checkin(uuid, uuid, int, text, text, int) from public, anon;
grant execute on function public.mission_checkin(uuid, uuid, int, text, text, int) to authenticated;

-- ------------------------------------------------------------ mission view

/**
 * One group as the app shows it, now with the Ghostline apps placed in it.
 *
 * They come first in the seats list with seat numbers below one, so they head
 * a member's tasks and stay out of the sixteen-slot grid. To a member they are
 * another app to test; nothing here marks them as paid. "taken" counts the
 * real seats, which is what the mission fills on.
 */
create or replace function public.mission_view(p_mission uuid) returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    with g as (
        select * from public.mission_groups where id = p_mission
    ), me as (
        select exists (
            select 1 from public.mission_seats s
            where s.mission_id = p_mission and s.member = auth.uid()
        ) as member
    ), today as (
        select (now() at time zone 'utc')::date as d
    ), ghosts as (
        select (-row_number() over (order by gs.placed_at))::int as seat, l.*
        from public.ghostline_seats gs
        join public.ghostline_runs r on r.id = gs.run_id and r.state in ('running', 'extended')
        join public.listings l on l.id = r.listing_id
        where gs.mission_id = p_mission
    ), all_seats as (
        select s.seat, s.member as owner, l.id, l.title, l.icon_url, l.package_name, l.play_url,
               false as ghost
        from public.mission_seats s
        join public.listings l on l.id = s.listing_id
        where s.mission_id = p_mission
        union all
        select gh.seat, gh.owner, gh.id, gh.title, gh.icon_url, gh.package_name, gh.play_url, true
        from ghosts gh
    )
    select jsonb_build_object(
        'id', g.id,
        'name', g.name,
        'slots', g.slots,
        'entry_fee', g.entry_fee,
        'window_days', g.window_days,
        'state', case
            when g.state = 'running'
                 and (select d from today) - g.started_on >= g.window_days
                then 'elapsed'
            else g.state end,
        'day', case
            when g.started_on is null then 0
            else least(g.window_days, (select d from today) - g.started_on + 1) end,
        'member', me.member,
        'taken', (select count(*)::int from public.mission_seats s where s.mission_id = g.id),
        'seats', coalesce((
            select jsonb_agg(jsonb_build_object(
                'seat', a.seat,
                'listing', a.id,
                'title', a.title,
                'icon_url', a.icon_url,
                'mine', a.owner = auth.uid(),
                'owner_name', case when me.member
                    then coalesce(nullif(p.display_name, ''), p.handle) end,
                'package_name', case when me.member then a.package_name end,
                'play_url', case when me.member then a.play_url end,
                'done_today', me.member and exists (
                    select 1 from public.mission_checkins c
                    where c.mission_id = g.id and c.member = auth.uid()
                      and c.listing_id = a.id and c.day = (select d from today)
                ),
                'done_ever', me.member and exists (
                    select 1 from public.mission_checkins c
                    where c.mission_id = g.id and c.member = auth.uid()
                      and c.listing_id = a.id
                ),
                -- Progress is a member's: someone who is not testing the
                -- others has none to show.
                'tested', case when me.member and not a.ghost then (
                    select count(distinct c.listing_id)::int
                    from public.mission_checkins c
                    join public.mission_seats ms
                      on ms.mission_id = c.mission_id and ms.listing_id = c.listing_id
                    where c.mission_id = g.id and c.member = a.owner
                      and (g.state = 'recruiting' or c.day = (select d from today))
                ) end
            ) order by a.seat)
            from all_seats a
            join public.profiles p on p.id = a.owner
            -- The Ghostline app never appears in its own owner's missions.
            where not (a.ghost and a.owner = auth.uid())
        ), '[]'::jsonb)
    )
    from g, me;
$$;

revoke all on function public.mission_view(uuid) from public, anon, authenticated;

-- -------------------------------------------------------------- scheduling

do $$
begin
    if exists (select 1 from pg_namespace where nspname = 'cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'onedevs-ghostline';
        perform cron.schedule('onedevs-ghostline', '7 * * * *', 'select public.ghostline_tick()');
    end if;
end $$;
