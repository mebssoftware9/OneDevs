-- Testing cycles: what Premium and Pro pay for.
--
-- A cycle is sixteen days of real testers for one app: 16 of them on
-- Premium, 25 on Pro. Premium starts one cycle per paid month, Pro two. It
-- runs on the Ghostline machinery -- a cycle is a ghostline_runs row of kind
-- 'cycle' -- so members of the missions it is placed in see one more app to
-- test and are paid for it by OneDevs, exactly as for a Ghostline run.
--
-- Activation
--   Within five hours of starting, the app is in front of testers. Starting
--   places it in the recruiting mission and in running missions with days
--   left, until those missions hold twice as many members as the cycle
--   needs: not everyone who sees an app tests it. Whatever did not fit is
--   retried by the hourly tick during the first five hours.
--
-- Spotlight
--   On days 0, 4, 8 and 12 (and every fourth day of an extension) the app
--   sits at the top of the Testing Board for 24 hours, marked Spotlight,
--   for the whole community. A Board test of a spotlighted app is paid 50
--   DevCoins by OneDevs; the owner pays nothing for it, having paid for the
--   plan. Outside the spotlight the app stays off the boards, like any app
--   on a run.
--
-- Insights
--   Every fourth day the owner's dashboard gains a report of the four days
--   just ended. Worked out from what the server already records, never
--   naming anyone.
--
-- Guarantee
--   A cycle that reaches day 16 short of its testers runs 7 more days.
--
-- Bonus
--   Each paid month of Premium or Pro pays 500 or 1,000 DevCoins, once per
--   Google order, so a retried verification or a repeated notification
--   cannot pay it twice.
--
-- A verified tester is one person who used the app for at least 32 seconds,
-- in a mission or through the Board. Each person counts once.

-- ------------------------------------------------------------------ ledger

alter table public.coin_entries drop constraint if exists coin_entries_reason_check;
alter table public.coin_entries add constraint coin_entries_reason_check
    check (reason in (
        'grant', 'test_reward', 'test_payment',
        'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund',
        'mission_entry', 'ghostline_reward', 'plan_bonus', 'spotlight_reward'
    ));

-- ------------------------------------------------------------------ cycles

alter table public.ghostline_runs add column if not exists kind text not null default 'ghostline';
alter table public.ghostline_runs drop constraint if exists ghostline_runs_kind_check;
alter table public.ghostline_runs add constraint ghostline_runs_kind_check
    check (kind in ('ghostline', 'cycle'));
-- The plan a cycle was started on, and the testers it promised then. A
-- cycle keeps both if the plan changes halfway.
alter table public.ghostline_runs add column if not exists plan text
    check (plan is null or plan in ('premium', 'pro'));
alter table public.ghostline_runs add column if not exists needed int
    check (needed is null or needed > 0);

create index if not exists ghostline_runs_account_kind
    on public.ghostline_runs (account, kind, started_at desc);

/** Days in a cycle, before any extension. */
create or replace function public.cycle_days() returns int
language sql immutable as $$ select 16 $$;

/** Testers a plan's cycle promises. */
create or replace function public.cycle_testers(p_plan text) returns int
language sql immutable as $$
    select case p_plan when 'pro' then 25 when 'premium' then 16 end
$$;

/** Cycles a plan may start in each paid month. */
create or replace function public.cycle_apps(p_plan text) returns int
language sql immutable as $$
    select case p_plan when 'pro' then 2 when 'premium' then 1 else 0 end
$$;

/** DevCoins each paid month of a plan brings. */
create or replace function public.plan_bonus(p_plan text) returns int
language sql immutable as $$
    select case p_plan when 'pro' then 1000 when 'premium' then 500 else 0 end
$$;

/** DevCoins OneDevs pays for a Board test of a spotlighted app. */
create or replace function public.spotlight_reward() returns int
language sql immutable as $$ select 50 $$;

/** Whether a run is in one of its 24-hour spotlight windows at p_at. */
create or replace function public.cycle_spotlit(p_run public.ghostline_runs, p_at timestamptz)
returns boolean
language sql immutable as $$
    select p_run.kind = 'cycle'
       and p_run.state in ('running', 'extended')
       and p_at >= p_run.started_at
       and p_at < p_run.ends_at
       and floor(extract(epoch from p_at - p_run.started_at) / 86400)::int % 4 = 0
$$;

/** Whether an app is spotlighted on the Testing Board right now. */
create or replace function public.spotlit(p_listing uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1 from public.ghostline_runs r
        where r.listing_id = p_listing and public.cycle_spotlit(r, now())
    );
$$;

/**
 * Everyone who used a run's app since it started, and how: mission
 * check-ins, and Board tests. One row per person per day.
 */
create or replace function public.run_uses(p_run uuid)
returns table (member uuid, day date, at timestamptz, seconds int, model text, sdk int)
language sql stable security definer set search_path = '' as $$
    select c.member, c.day, c.at, c.seconds, c.device_model, c.android_sdk
    from public.ghostline_runs r
    join public.mission_checkins c on c.listing_id = r.listing_id and c.at >= r.started_at
    where r.id = p_run
    union all
    select t.tester, (t.claimed_at at time zone 'utc')::date, t.claimed_at, t.seconds, null, null
    from public.ghostline_runs r
    join public.tests t on t.listing_id = r.listing_id and t.claimed_at >= r.started_at
    where r.id = p_run;
$$;

/** People who have used a run's app since it started, in a mission or on the Board. */
create or replace function public.ghostline_testers(p_run uuid) returns int
language sql stable security definer set search_path = '' as $$
    select count(distinct u.member)::int from public.run_uses(p_run) u;
$$;

/** Testers a run needs before it counts as done. */
create or replace function public.run_needed(p_run public.ghostline_runs) returns int
language sql stable security definer set search_path = '' as $$
    select coalesce(p_run.needed, public.ghostline_testers_needed());
$$;

/**
 * Members, other than the app's owner, of the missions a run is placed in.
 * A recruiting mission counts its empty seats too: they fill with people who
 * will see the app.
 */
create or replace function public.run_reach(p_run uuid) returns int
language sql stable security definer set search_path = '' as $$
    select coalesce(sum(case
        when g.state = 'recruiting' then g.slots
        else (select count(*) from public.mission_seats s
              where s.mission_id = g.id and s.member <> r.account)
    end), 0)::int
    from public.ghostline_seats gs
    join public.ghostline_runs r on r.id = gs.run_id
    join public.mission_groups g on g.id = gs.mission_id
    where gs.run_id = p_run;
$$;

/**
 * Puts a cycle's app in front of enough testers: the recruiting mission
 * first, then running missions with the most days left, until they hold
 * twice the testers the cycle needs. Never a mission the owner sits in or
 * the app already has a seat in. Returns how many missions it added.
 */
create or replace function public.cycle_activate(p_run uuid) returns int
language plpgsql security definer set search_path = '' as $$
declare
    v_run public.ghostline_runs%rowtype;
    v_mission uuid;
    v_added int := 0;
    v_rows int;
    v_today date := (now() at time zone 'utc')::date;
begin
    select * into v_run from public.ghostline_runs
    where id = p_run and kind = 'cycle' and state in ('running', 'extended');
    if v_run.id is null then return 0; end if;

    perform public.recruiting_group();
    for v_mission in
        select g.id from public.mission_groups g
        where (g.state = 'recruiting' or v_today - g.started_on < g.window_days)
          and not exists (select 1 from public.ghostline_seats s
                          where s.run_id = p_run and s.mission_id = g.id)
          and not exists (select 1 from public.mission_seats s
                          where s.mission_id = g.id
                            and (s.member = v_run.account or s.listing_id = v_run.listing_id))
        order by (g.state = 'recruiting') desc,
                 g.started_on + g.window_days - v_today desc,
                 g.created_at
    loop
        exit when public.run_reach(p_run) >= 2 * public.run_needed(v_run);
        insert into public.ghostline_seats (run_id, mission_id, kind)
        values (p_run, v_mission, 'first')
        on conflict do nothing;
        get diagnostics v_rows = row_count;
        v_added := v_added + v_rows;
    end loop;
    return v_added;
end $$;

/**
 * Starts a testing cycle for one of the caller's apps in testing.
 *
 * { ok: true, run } or { ok: false, reason }: signed_out, no_plan,
 * not_your_testing_app, already_running, month_used. month_used carries
 * the date the next paid month starts.
 */
create or replace function public.cycle_start(p_listing uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_plan text;
    v_until timestamptz;
    v_used int;
    v_run public.ghostline_runs%rowtype;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    -- One start at a time per account, so two taps cannot both take the
    -- month's last cycle.
    perform pg_advisory_xact_lock(hashtext('cycle_start:' || auth.uid()::text));

    v_plan := public.plan_of(auth.uid());
    if v_plan not in ('premium', 'pro') then
        return jsonb_build_object('ok', false, 'reason', 'no_plan');
    end if;
    if not exists (
        select 1 from public.listings
        where id = p_listing and owner = auth.uid() and channel = 'testing'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'not_your_testing_app');
    end if;
    if exists (
        select 1 from public.ghostline_runs
        where listing_id = p_listing and state in ('running', 'extended')
    ) then
        return jsonb_build_object('ok', false, 'reason', 'already_running');
    end if;

    -- The paid month is the one ending at the plan's current expiry.
    select max(expires_at) into v_until from public.entitlements
    where account = auth.uid() and product = v_plan and expires_at > now();
    select count(*) into v_used from public.ghostline_runs
    where account = auth.uid() and kind = 'cycle'
      and started_at >= v_until - interval '1 month';
    if v_used >= public.cycle_apps(v_plan) then
        return jsonb_build_object('ok', false, 'reason', 'month_used', 'next_at', v_until);
    end if;

    insert into public.ghostline_runs (account, listing_id, kind, plan, needed, ends_at)
    values (auth.uid(), p_listing, 'cycle', v_plan, public.cycle_testers(v_plan),
            now() + public.cycle_days() * interval '1 day')
    returning * into v_run;

    perform public.cycle_activate(v_run.id);
    return jsonb_build_object('ok', true, 'run', v_run.id);
end $$;

/**
 * What the caller's plan lets them start: { plan, apps, used, needed,
 * next_at }. apps is cycles per paid month, used how many this month has
 * started, next_at when the next paid month starts.
 */
create or replace function public.cycle_allowance() returns jsonb
language sql stable security definer set search_path = '' as $$
    with p as (
        select public.plan_of(auth.uid()) as plan
    ), u as (
        select max(e.expires_at) as until from public.entitlements e, p
        where e.account = auth.uid() and e.product = p.plan and e.expires_at > now()
    )
    select jsonb_build_object(
        'plan', p.plan,
        'apps', public.cycle_apps(p.plan),
        'used', (
            select count(*)::int from public.ghostline_runs r
            where r.account = auth.uid() and r.kind = 'cycle'
              and u.until is not null and r.started_at >= u.until - interval '1 month'
        ),
        'needed', public.cycle_testers(p.plan),
        'next_at', u.until
    )
    from p, u;
$$;

-- ------------------------------------------------------------------- ticks

/**
 * Hourly: activation retries, boosts every fourth day, and the end of each
 * run.
 *
 * A run that ends short of its testers is extended once by seven days
 * rather than closed; that is the guarantee.
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
               and public.ghostline_testers(r.id) < public.run_needed(r) then
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

        if r.kind = 'cycle' then
            -- Whatever did not fit at the start gets another try each hour
            -- until the five hours are up.
            if now() < r.started_at + interval '5 hours' then
                perform public.cycle_activate(r.id);
                continue;
            end if;
        elsif not exists (select 1 from public.ghostline_seats where run_id = r.id) then
            -- The first placement can miss: the app was already sitting in
            -- the mission that was filling. It goes into the next one.
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

-- ---------------------------------------------------------------- insights

/**
 * The report on one four-day stretch of a run: day k*4, looking back from
 * then. Counts and phones, never people.
 */
create or replace function public.run_insight(p_run uuid, p_k int) returns jsonb
language sql stable security definer set search_path = '' as $$
    with r as (
        select * from public.ghostline_runs where id = p_run
    ), w as (
        select r.started_at + (p_k - 1) * interval '4 days' as since,
               r.started_at + p_k * interval '4 days' as upto
        from r
    ), u as (
        select x.* from public.run_uses(p_run) x, w where x.at < w.upto
    ), people as (
        select u.member, min(u.at) as first_at, count(distinct u.day) as days,
               sum(u.seconds) as seconds
        from u group by u.member
    ), phones as (
        select distinct on (u.member) u.model, u.sdk
        from u where u.model is not null or u.sdk is not null
        order by u.member, u.at
    )
    select jsonb_build_object(
        'day', p_k * 4,
        'at', w.upto,
        'testers', (select count(*)::int from people),
        'new', (select count(*)::int from people where first_at >= w.since),
        'avg_seconds', (select coalesce(round(avg(seconds)), 0)::int from people),
        'one_day', (select count(*)::int from people where days = 1),
        'models', coalesce((
            select jsonb_agg(jsonb_build_object('model', m.model, 'n', m.n) order by m.n desc, m.model)
            from (select model, count(*)::int as n from phones where model is not null
                  group by model order by count(*) desc, model limit 5) m
        ), '[]'::jsonb),
        'android', coalesce((
            select jsonb_agg(jsonb_build_object('sdk', a.sdk, 'n', a.n) order by a.n desc, a.sdk desc)
            from (select sdk, count(*)::int as n from phones where sdk is not null
                  group by sdk) a
        ), '[]'::jsonb)
    )
    from w;
$$;

/**
 * The owner's view of their runs and cycles: time, people, phones, and a
 * report every fourth day. Never who: members are counted and their phones
 * described, but not named.
 */
create or replace function public.ghostline_dashboard() returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(x.j order by x.created_at desc), '[]'::jsonb)
    from (
        select r.created_at, jsonb_build_object(
            'id', r.id,
            'kind', r.kind,
            'plan', r.plan,
            'state', r.state,
            'started_at', r.started_at,
            'ends_at', r.ends_at,
            'server_now', now(),
            'listing', l.id,
            'title', l.title,
            'icon_url', l.icon_url,
            'package_name', l.package_name,
            'needed', public.run_needed(r),
            'testers', public.ghostline_testers(r.id),
            'active_today', (
                select count(distinct u.member)::int from public.run_uses(r.id) u
                where u.day = (now() at time zone 'utc')::date
            ),
            'missions', (select count(*)::int from public.ghostline_seats s where s.run_id = r.id),
            'boosts', (select count(*)::int from public.ghostline_seats s
                       where s.run_id = r.id and s.kind = 'boost'),
            'next_boost_at', case when r.state in ('running', 'extended') then
                r.started_at + (floor(extract(epoch from now() - r.started_at) / 86400 / 4) + 1)
                    * interval '4 days' end,
            'spotlight', public.cycle_spotlit(r, now()),
            -- The next window, or the end of this one while it is on.
            'spotlight_until', case when public.cycle_spotlit(r, now()) then
                r.started_at + (floor(extract(epoch from now() - r.started_at) / 86400) + 1)
                    * interval '1 day' end,
            'next_spotlight_at', case when r.kind = 'cycle' and r.state in ('running', 'extended') then
                nullif(least(r.ends_at,
                    r.started_at + (floor(extract(epoch from now() - r.started_at) / 86400 / 4) + 1)
                        * interval '4 days'), r.ends_at) end,
            'installs', coalesce((
                select jsonb_agg(jsonb_build_object('at', f.at, 'model', f.model, 'sdk', f.sdk)
                                 order by f.at desc)
                from (
                    select distinct on (u.member) u.at, u.model, u.sdk
                    from public.run_uses(r.id) u
                    order by u.member, u.at
                ) f
            ), '[]'::jsonb),
            'daily', coalesce((
                select jsonb_agg(jsonb_build_object('day', d.day, 'testers', d.n) order by d.day)
                from (
                    select u.day, count(distinct u.member)::int as n
                    from public.run_uses(r.id) u
                    group by u.day
                ) d
            ), '[]'::jsonb),
            -- Newest first. A report exists once its four days are over.
            'insights', case when r.kind = 'cycle' then coalesce((
                select jsonb_agg(public.run_insight(r.id, k) order by k desc)
                from generate_series(1, floor(extract(epoch from
                    least(now(), coalesce(r.completed_at, now()), r.ends_at) - r.started_at)
                    / 86400 / 4)::int) k
            ), '[]'::jsonb) else '[]'::jsonb end
        ) as j
        from public.ghostline_runs r
        join public.listings l on l.id = r.listing_id
        where r.account = auth.uid()
    ) x;
$$;

-- ------------------------------------------------------------------- bonus

/** Months of Premium and Pro whose DevCoins have been paid, one row per Google order. */
create table if not exists public.plan_bonuses (
    order_key text primary key,
    account uuid not null references public.profiles (id) on delete cascade,
    product text not null,
    coins int not null,
    paid_at timestamptz not null default now()
);

alter table public.plan_bonuses enable row level security;
revoke all on public.plan_bonuses from anon, authenticated;

/**
 * Records a verified Play subscription and what it entitles. Service role
 * only. The product is the plan: 'premium', 'pro', or the old 'lab_pro'. The
 * expiry is Google's; a cancelled subscription keeps running to it.
 *
 * A paid month of Premium or Pro also pays its DevCoin bonus, once per Google
 * order. Renewals are new orders, so each month pays once.
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
    v_bonus int := 0;
    v_rows int;
begin
    if p_product is null or p_product not in ('lab_pro', 'premium', 'pro') then
        return jsonb_build_object('ok', false, 'reason', 'unknown_product');
    end if;

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
    values (p_account, p_product, 'subscription', v_until, p_token)
    on conflict (source, purchase_token) do update
        set product = excluded.product, expires_at = excluded.expires_at;

    if p_product in ('premium', 'pro') and p_state in ('active', 'cancelled')
       and p_expires > now() then
        insert into public.plan_bonuses (order_key, account, product, coins)
        values (coalesce(p_order, p_token || '@' || p_expires::text), p_account, p_product,
                public.plan_bonus(p_product))
        on conflict (order_key) do nothing;
        get diagnostics v_rows = row_count;
        if v_rows = 1 then
            v_bonus := public.plan_bonus(p_product);
            insert into public.coin_entries (account, delta, reason)
            values (p_account, v_bonus, 'plan_bonus');
        end if;
    end if;

    return jsonb_build_object('ok', true, 'plan', public.plan_of(p_account), 'bonus', v_bonus);
end $$;

-- -------------------------------------------------------------- the boards

-- An app on a run leaves both boards until the run is over, except while a
-- cycle has it in the spotlight: then it heads the Testing Board, whatever
-- its owner's balance, because OneDevs pays for those tests.
create or replace view public.board_listings as
select l.*,
       sp.listing_id is not null as spotlight,
       case when sp.listing_id is not null then 50 end as spotlight_reward
from public.listings l
left join lateral (
    select g.listing_id from public.ghostline_runs g
    where g.listing_id = l.id
      and g.kind = 'cycle'
      and g.state in ('running', 'extended')
      and now() >= g.started_at and now() < g.ends_at
      and floor(extract(epoch from now() - g.started_at) / 86400)::int % 4 = 0
    limit 1
) sp on true
where (
    sp.listing_id is not null
    or (
        coalesce((select ab.balance from public.account_balances ab where ab.account = l.owner), 0) - (
            select coalesce(sum(s.reward), 0)
            from public.test_sessions s
            where s.owner = l.owner and s.state = 'open' and s.expires_at > now()
        ) >= l.reward
        and not exists (
            select 1 from public.ghostline_runs g
            where g.listing_id = l.id and g.state in ('running', 'extended')
        )
    )
)
and not exists (
    select 1 from public.tests t
    where t.listing_id = l.id and t.tester = auth.uid()
);

grant select on public.board_listings to authenticated;

/** One board for this person and this phone, spotlighted apps first. */
create or replace function public.board(
    p_channel text,
    p_device text,
    p_limit int default 50
)
returns setof public.board_listings
language sql
stable
security definer
set search_path = ''
as $$
    select b.*
    from public.board_listings b
    where b.channel = p_channel
      and not exists (
          select 1
          from public.tests t
          where t.listing_id = b.id
            and (t.tester = auth.uid() or t.device = p_device)
      )
      -- Your own spotlight is not yours to test.
      and not (b.spotlight and b.owner = auth.uid())
    order by b.spotlight desc, b.created_at desc
    limit p_limit;
$$;

revoke all on function public.board(text, text, int) from public, anon;
grant execute on function public.board(text, text, int) to authenticated;

/** Everything the Board shows, in one call, spotlighted apps first. */
create or replace function public.home_board(
    p_device text,
    p_limit int default 50
) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    return jsonb_build_object(
        'stats', public.platform_stats(),
        'testing', coalesce(
            (select jsonb_agg(to_jsonb(b) order by b.spotlight desc, b.created_at desc)
             from public.board('testing', p_device, p_limit) b),
            '[]'::jsonb
        ),
        'live', coalesce(
            (select jsonb_agg(to_jsonb(b) order by b.created_at desc)
             from public.board('live', p_device, p_limit) b),
            '[]'::jsonb
        )
    );
end $$;

revoke all on function public.home_board(text, int) from public, anon;
grant execute on function public.home_board(text, int) to authenticated;

-- ------------------------------------------------------------------- tests

-- What OneDevs adds to a test, paid on top of whatever the owner holds.
alter table public.test_sessions add column if not exists bonus int not null default 0
    check (bonus >= 0);

/** A session as the app reads it: reward is everything the tester will be paid. */
create or replace function public.session_json(s public.test_sessions) returns jsonb
language sql stable set search_path = '' as $$
    select jsonb_build_object(
        'session', s.id,
        'reward', s.reward + s.bonus,
        'spotlight', s.bonus > 0,
        'started_at', s.started_at,
        'expires_at', s.expires_at
    );
$$;

revoke all on function public.session_json(public.test_sessions) from public, anon, authenticated;

/**
 * Where this tester stands with a listing, before they do anything. A
 * spotlighted app is always fundable: OneDevs pays for it.
 */
create or replace function public.test_status(p_listing uuid, p_device text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
    v_listing public.listings%rowtype;
    v_session public.test_sessions%rowtype;
    v_spot boolean;
    v_reward int;
begin
    if auth.uid() is null then
        return jsonb_build_object('state', 'signed_out');
    end if;
    select * into v_listing from public.listings where id = p_listing;
    if v_listing.id is null then
        return jsonb_build_object('state', 'missing');
    end if;
    v_spot := public.spotlit(p_listing);
    v_reward := case when v_spot then public.spotlight_reward() else v_listing.reward end;
    if v_listing.owner = auth.uid() then
        return jsonb_build_object('state', 'own_app', 'reward', v_reward, 'spotlight', v_spot);
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and tester = auth.uid()) then
        return jsonb_build_object('state', 'paid', 'reward', v_listing.reward);
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and device = p_device) then
        return jsonb_build_object('state', 'device_used', 'reward', v_reward, 'spotlight', v_spot);
    end if;
    select * into v_session from public.test_sessions
    where listing_id = p_listing and tester = auth.uid() and state = 'open';
    if v_session.id is not null and v_session.expires_at > now() then
        return public.session_json(v_session) || jsonb_build_object('state', 'open');
    end if;
    if exists (
        select 1 from public.test_sessions
        where listing_id = p_listing and device = p_device and tester <> auth.uid()
          and state = 'open' and expires_at > now()
    ) then
        return jsonb_build_object('state', 'device_in_use', 'reward', v_reward, 'spotlight', v_spot);
    end if;
    if not v_spot and public.available_of(v_listing.owner) < v_listing.reward then
        return jsonb_build_object('state', 'unfunded', 'reward', v_listing.reward);
    end if;
    return jsonb_build_object('state', 'available', 'reward', v_reward, 'spotlight', v_spot);
end;
$$;

/**
 * Starts a test and holds its reward. A test started while the app is in
 * the spotlight holds nothing of the owner's and carries OneDevs' 50
 * DevCoins instead, which it keeps if the window closes before it ends.
 */
create or replace function public.begin_test(p_listing uuid, p_device text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_listing public.listings%rowtype;
    v_session public.test_sessions%rowtype;
    v_spot boolean;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if p_device is null or char_length(p_device) < 8 then
        return jsonb_build_object('ok', false, 'reason', 'device');
    end if;
    select * into v_listing from public.listings where id = p_listing;
    if v_listing.id is null then
        return jsonb_build_object('ok', false, 'reason', 'missing');
    end if;
    if v_listing.owner = auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'own_app');
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and tester = auth.uid()) then
        return jsonb_build_object('ok', false, 'reason', 'already_paid', 'reward', v_listing.reward);
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and device = p_device) then
        return jsonb_build_object('ok', false, 'reason', 'device_used');
    end if;

    -- Serialise on the developer: two testers starting at once must not both
    -- see the same last coins as available.
    perform 1 from public.profiles where id = v_listing.owner for update;
    v_spot := public.spotlit(p_listing);

    select * into v_session from public.test_sessions
    where listing_id = p_listing and tester = auth.uid() and state = 'open'
    for update;

    if v_session.id is not null then
        -- A lapsed hold no longer counts, so reviving it is a new promise and
        -- has to be affordable like one.
        if v_session.expires_at <= now()
           and public.available_of(v_listing.owner) < v_session.reward then
            update public.test_sessions set state = 'released', settled_at = now()
            where id = v_session.id;
            return jsonb_build_object('ok', false, 'reason', 'unfunded');
        end if;
        update public.test_sessions
        set device = p_device,
            expires_at = greatest(expires_at, now() + interval '2 hours')
        where id = v_session.id
        returning * into v_session;
        return public.session_json(v_session) || jsonb_build_object('ok', true);
    end if;

    if exists (
        select 1 from public.test_sessions
        where listing_id = p_listing and device = p_device
          and state = 'open' and expires_at > now()
    ) then
        return jsonb_build_object('ok', false, 'reason', 'device_in_use');
    end if;

    if not v_spot and public.available_of(v_listing.owner) < v_listing.reward then
        return jsonb_build_object('ok', false, 'reason', 'unfunded');
    end if;

    insert into public.test_sessions (listing_id, owner, tester, device, reward, bonus)
    values (p_listing, v_listing.owner, auth.uid(), p_device,
            case when v_spot then 0 else v_listing.reward end,
            case when v_spot then public.spotlight_reward() else 0 end)
    returning * into v_session;

    return public.session_json(v_session) || jsonb_build_object('ok', true);
end;
$$;

/**
 * Pays a finished test: the owner's hold, and OneDevs' bonus if the test
 * began in the spotlight. Idempotent, as before.
 */
create or replace function public.finish_test(p_session uuid, p_seconds int) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_session public.test_sessions%rowtype;
    v_rows int;
begin
    if auth.uid() is null then
        return jsonb_build_object('paid', false, 'reason', 'signed_out');
    end if;

    select * into v_session from public.test_sessions where id = p_session for update;
    if v_session.id is null or v_session.tester <> auth.uid() then
        return jsonb_build_object('paid', false, 'reason', 'missing');
    end if;
    if v_session.state = 'paid' then
        return jsonb_build_object('paid', true, 'coins', v_session.reward + v_session.bonus,
                                  'repeat', true);
    end if;

    -- Paid some other way, by an older version of the app.
    if exists (
        select 1 from public.tests where listing_id = v_session.listing_id and tester = auth.uid()
    ) then
        update public.test_sessions set state = 'paid', settled_at = now() where id = p_session;
        return jsonb_build_object('paid', true, 'coins', v_session.reward + v_session.bonus,
                                  'repeat', true);
    end if;

    if v_session.state = 'released' then
        return jsonb_build_object('paid', false, 'reason', 'expired');
    end if;

    -- The device's count is checked against the server's own clock too: no
    -- one has used an app for thirty-two seconds within less than that.
    if p_seconds is null or p_seconds < 32
       or now() - v_session.started_at < interval '32 seconds' then
        return jsonb_build_object('paid', false, 'reason', 'too_short');
    end if;

    perform 1 from public.profiles where id = v_session.owner for update;

    -- A lapsed hold stopped counting, so the money may have gone elsewhere.
    -- Pay if it is still there; otherwise say so plainly.
    if v_session.reward > 0 and v_session.expires_at <= now()
       and public.available_of(v_session.owner) < v_session.reward then
        update public.test_sessions set state = 'released', settled_at = now()
        where id = p_session;
        return jsonb_build_object('paid', false, 'reason', 'expired');
    end if;

    insert into public.tests (listing_id, tester, seconds, device)
    values (v_session.listing_id, auth.uid(), least(p_seconds, 86400), v_session.device)
    on conflict do nothing;
    get diagnostics v_rows = row_count;

    if v_rows <> 1 then
        -- Another account on the same phone finished first.
        update public.test_sessions set state = 'released', settled_at = now()
        where id = p_session;
        return jsonb_build_object('paid', false, 'reason', 'device_used');
    end if;

    if v_session.reward > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (v_session.owner, -v_session.reward, 'test_payment', v_session.listing_id),
               (auth.uid(), v_session.reward, 'test_reward', v_session.listing_id);
    end if;
    if v_session.bonus > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (auth.uid(), v_session.bonus, 'spotlight_reward', v_session.listing_id);
    end if;

    update public.test_sessions set state = 'paid', settled_at = now() where id = p_session;

    return jsonb_build_object('paid', true, 'coins', v_session.reward + v_session.bonus);
end;
$$;

-- ------------------------------------------------------------------ grants

revoke all on function public.cycle_days() from public, anon, authenticated;
revoke all on function public.cycle_testers(text) from public, anon, authenticated;
revoke all on function public.cycle_apps(text) from public, anon, authenticated;
revoke all on function public.plan_bonus(text) from public, anon, authenticated;
revoke all on function public.spotlight_reward() from public, anon, authenticated;
revoke all on function public.cycle_spotlit(public.ghostline_runs, timestamptz) from public, anon, authenticated;
revoke all on function public.spotlit(uuid) from public, anon, authenticated;
revoke all on function public.run_uses(uuid) from public, anon, authenticated;
revoke all on function public.ghostline_testers(uuid) from public, anon, authenticated;
revoke all on function public.run_needed(public.ghostline_runs) from public, anon, authenticated;
revoke all on function public.run_reach(uuid) from public, anon, authenticated;
revoke all on function public.cycle_activate(uuid) from public, anon, authenticated;
revoke all on function public.run_insight(uuid, int) from public, anon, authenticated;
revoke all on function public.ghostline_tick() from public, anon, authenticated;
revoke all on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
    from public, anon, authenticated;

revoke all on function public.cycle_start(uuid) from public, anon;
revoke all on function public.cycle_allowance() from public, anon;
revoke all on function public.ghostline_dashboard() from public, anon;
revoke all on function public.test_status(uuid, text) from public, anon;
revoke all on function public.begin_test(uuid, text) from public, anon;
revoke all on function public.finish_test(uuid, int) from public, anon;
grant execute on function public.cycle_start(uuid) to authenticated;
grant execute on function public.cycle_allowance() to authenticated;
grant execute on function public.ghostline_dashboard() to authenticated;
grant execute on function public.test_status(uuid, text) to authenticated;
grant execute on function public.begin_test(uuid, text) to authenticated;
grant execute on function public.finish_test(uuid, int) to authenticated;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
            to service_role;
    end if;
end $$;
