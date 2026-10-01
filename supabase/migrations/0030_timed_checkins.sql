-- Mission check-ins timed by the server, as Board tests already are.
--
-- A check-in used to count on the seconds the phone reported, so a modified
-- client could tick every task every day without opening an app: DevCoins
-- for apps placed by a testing cycle, days towards a mission's result, and a
-- "verified tester" for a Premium or Pro customer who is paying for real ones.
--
-- Now the app tells the server when it opens a member's app, and a check-in
-- counts only once that start is at least 32 seconds old, for no longer than
-- it has been open, and once per start.

create table if not exists public.mission_checkin_starts (
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    member uuid not null references public.profiles (id) on delete cascade,
    listing_id uuid not null references public.listings (id) on delete cascade,
    started_at timestamptz not null default now(),
    primary key (mission_id, member, listing_id)
);

alter table public.mission_checkin_starts enable row level security;
revoke all on public.mission_checkin_starts from anon, authenticated;

/**
 * Whether the caller may work on [p_listing] in [p_mission] right now: a
 * reason when not, null when so, and whether the app was placed there by a
 * testing cycle. The checks every task shares, in the order the app reports
 * them.
 */
create or replace function public.mission_task(
    p_mission uuid,
    p_listing uuid,
    out reason text,
    out ghost boolean
)
language plpgsql stable security definer set search_path = '' as $$
declare
    v_group public.mission_groups%rowtype;
    v_owner uuid;
begin
    ghost := false;
    if auth.uid() is null then
        reason := 'signed_out';
        return;
    end if;
    select * into v_group from public.mission_groups where id = p_mission;
    if v_group.id is null then
        reason := 'missing';
        return;
    end if;
    if not exists (
        select 1 from public.mission_seats where mission_id = p_mission and member = auth.uid()
    ) then
        reason := 'not_member';
        return;
    end if;
    if v_group.state = 'completed'
       or (v_group.state = 'running'
           and (now() at time zone 'utc')::date - v_group.started_on >= v_group.window_days) then
        reason := 'elapsed';
        return;
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
        ghost := v_owner is not null;
    end if;
    if v_owner is null then
        reason := 'not_in_mission';
    elsif v_owner = auth.uid() then
        reason := 'own_app';
    end if;
end $$;

revoke all on function public.mission_task(uuid, uuid) from public, anon, authenticated;

/** The app is opening a member's app: start the server's clock for it. */
create or replace function public.mission_checkin_begin(p_mission uuid, p_listing uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_task record;
begin
    select * into v_task from public.mission_task(p_mission, p_listing);
    if v_task.reason is not null then
        return jsonb_build_object('ok', false, 'reason', v_task.reason);
    end if;
    insert into public.mission_checkin_starts (mission_id, member, listing_id, started_at)
    values (p_mission, auth.uid(), p_listing, now())
    on conflict (mission_id, member, listing_id) do update set started_at = excluded.started_at;
    return jsonb_build_object('ok', true);
end $$;

revoke all on function public.mission_checkin_begin(uuid, uuid) from public, anon;
grant execute on function public.mission_checkin_begin(uuid, uuid) to authenticated;

/**
 * Records that the caller used another member's app today. Counts only on a
 * start the server recorded at least 32 seconds ago and less than six hours
 * ago, credits no more seconds than that start has been open, and uses the
 * start up. The phone's own count can only lower the figure, never raise it.
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
    v_task record;
    v_started timestamptz;
    v_open int;
    v_inserted boolean;
begin
    select * into v_task from public.mission_task(p_mission, p_listing);
    if v_task.reason is not null then
        return jsonb_build_object('ok', false, 'reason', v_task.reason);
    end if;
    if p_seconds is null or p_seconds < 32 then
        return jsonb_build_object('ok', false, 'reason', 'too_short');
    end if;

    select started_at into v_started
    from public.mission_checkin_starts
    where mission_id = p_mission and member = auth.uid() and listing_id = p_listing
    for update;
    if v_started is null or v_started < now() - interval '6 hours' then
        return jsonb_build_object('ok', false, 'reason', 'not_started');
    end if;
    v_open := floor(extract(epoch from now() - v_started))::int;
    if v_open < 32 then
        return jsonb_build_object('ok', false, 'reason', 'too_short');
    end if;

    delete from public.mission_checkin_starts
    where mission_id = p_mission and member = auth.uid() and listing_id = p_listing;

    insert into public.mission_checkins
        (mission_id, member, listing_id, seconds, device, device_model, android_sdk)
    values (p_mission, auth.uid(), p_listing, least(p_seconds, v_open, 86400), coalesce(p_device, ''),
            left(p_model, 80), case when p_sdk between 1 and 200 then p_sdk end)
    on conflict (mission_id, member, listing_id, day) do update
        set seconds = greatest(public.mission_checkins.seconds, excluded.seconds)
    returning (xmax = 0) into v_inserted;

    if v_task.ghost and v_inserted and not exists (
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
