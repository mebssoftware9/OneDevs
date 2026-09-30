-- The Board in one round trip, and a Board that costs the same at any size.
--
-- Opening the Board used to make three calls in a row, and the first of them
-- wrote to the database and counted every profile on the platform, every
-- time, for every person. On a phone network three sequential round trips is
-- already seconds; counts that grow with the user base made it worse each
-- week. That is the "Slow connection" people were seeing.
--
-- Now:
--   * presence is written at most once every five minutes per person;
--   * the platform numbers are computed at most once a minute, by whichever
--     caller finds them stale first, and everyone else reads one cached row;
--   * home_board() returns the numbers and both boards in a single call.

-- ---------------------------------------------------------------- presence

/**
 * Marks the caller as here. A row that was touched in the last five minutes
 * is left alone: "live now" is a fifteen-minute window, so a finer write buys
 * nothing and costs a row lock and a WAL record per Board open.
 */
create or replace function public.touch_presence() returns void
language sql security definer set search_path = '' as $$
    update public.profiles
    set last_seen = now()
    where id = auth.uid()
      and last_seen < now() - interval '5 minutes';
$$;

revoke all on function public.touch_presence() from public, anon, authenticated;

-- ------------------------------------------------------------ cached stats

create table if not exists public.platform_cache (
    id boolean primary key default true check (id),
    stats jsonb not null default '{}'::jsonb,
    refreshed_at timestamptz not null default 'epoch'
);

insert into public.platform_cache (id) values (true) on conflict (id) do nothing;

-- Read only through the functions below.
alter table public.platform_cache enable row level security;

/** Counts everything once and stores it. Also records the hour's pulse. */
create or replace function public.refresh_platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_live int;
    v_day int;
    v_apps int;
    v_missions int;
    v_stats jsonb;
begin
    select count(*) into v_live from public.profiles
        where last_seen > now() - interval '15 minutes';
    select count(*) into v_day from public.profiles
        where last_seen > now() - interval '24 hours';
    select count(*) into v_apps from public.listings where channel = 'testing';
    select count(*) into v_missions from public.missions where state = 'open';

    insert into public.pulse (at, testers_active, apps_in_testing)
    values (date_trunc('hour', now()), v_live, v_apps)
    on conflict (at) do nothing;

    v_stats := jsonb_build_object(
        'live_now', v_live,
        'active_24h', v_day,
        'apps_in_testing', v_apps,
        'open_missions', v_missions,
        'pulse', coalesce(
            (
                select jsonb_agg(jsonb_build_object('at', p.at, 'testers', p.testers_active)
                                 order by p.at)
                from (select * from public.pulse order by at desc limit 24) p
            ),
            '[]'::jsonb
        )
    );

    update public.platform_cache set stats = v_stats, refreshed_at = now() where id;
    return v_stats;
end $$;

revoke all on function public.refresh_platform_stats() from public, anon, authenticated;

/**
 * The numbers at the top of the Board, at most a minute old.
 *
 * When they are stale, the first caller to get the advisory lock recounts;
 * everyone arriving meanwhile gets the previous numbers instead of queueing
 * behind the count. A thousand people opening the Board in the same second
 * cause one recount, not a thousand.
 */
create or replace function public.platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_row public.platform_cache%rowtype;
begin
    perform public.touch_presence();
    select * into v_row from public.platform_cache where id;
    if v_row.id is null
       or (v_row.refreshed_at < now() - interval '60 seconds'
           and pg_try_advisory_xact_lock(hashtext('onedevs.platform_stats'))) then
        return public.refresh_platform_stats();
    end if;
    return v_row.stats;
end $$;

revoke all on function public.platform_stats() from public, anon;
grant execute on function public.platform_stats() to authenticated;

-- ------------------------------------------------------------- one request

/**
 * Everything the Board shows, in one call: the numbers and both channels,
 * already filtered for this person and this phone by board().
 */
create or replace function public.home_board(
    p_device text,
    p_limit int default 50
) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    return jsonb_build_object(
        'stats', public.platform_stats(),
        'testing', coalesce(
            (select jsonb_agg(to_jsonb(b) order by b.created_at desc)
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

-- ------------------------------------------------------------------ index

-- board_listings subtracts each owner's open reservations that have not
-- expired; with the expiry in the index that is a range read, not a filter
-- over every open session the owner has.
create index if not exists test_sessions_owner_open_expiry
    on public.test_sessions (owner, expires_at) where state = 'open';
