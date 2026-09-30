-- How a mission ends, and how you leave one that has not started.
--
-- End
--   Fourteen days after it starts, the hourly tick completes the mission. A
--   member did their part if they used every other member's app on at least
--   10 of the 14 days. Everyone who did gets their entry fee back, and the
--   fees of those who did not are split among them. No coins are created: the
--   payout is exactly what the members paid in. If nobody did their part,
--   nothing is paid back. The room gets a closing line, and each member keeps
--   a record of how it ended for them.
--
-- Leaving
--   Until the last seat is taken, a member can leave and get the fee back.
--   The seats behind theirs move up one, so the next person to join takes the
--   next number. Once the mission starts, leaving is not possible: the
--   others are counting on your testers.

alter table public.mission_groups drop constraint if exists mission_groups_state_check;
alter table public.mission_groups add constraint mission_groups_state_check
    check (state in ('recruiting', 'running', 'completed'));
alter table public.mission_groups add column if not exists completed_at timestamptz;

alter table public.mission_messages drop constraint if exists mission_messages_kind_check;
alter table public.mission_messages add constraint mission_messages_kind_check
    check (kind in ('chat', 'join', 'start', 'leave', 'complete'));

/** How each member's mission ended. */
create table if not exists public.mission_results (
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    member uuid not null references public.profiles (id) on delete cascade,
    did_part boolean not null,
    -- The fewest days they used any one of the other apps.
    days int not null,
    coins int not null default 0,
    primary key (mission_id, member)
);

alter table public.mission_results enable row level security;
revoke all on public.mission_results from anon, authenticated;

/** Days of the window a member must use each other app: 10 of 14, in proportion. */
create or replace function public.mission_days_needed(p_window int) returns int
language sql immutable as $$
    select greatest(1, round(p_window * 10 / 14.0))::int
$$;

/**
 * Completes a mission whose days are over and pays it out. Does nothing for
 * a mission that is not running or not yet over, so it is safe to call more
 * than once.
 */
create or replace function public.mission_complete(p_mission uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_group public.mission_groups%rowtype;
    v_needed int;
    v_last date;
    v_pot int;
    v_done int;
    v_members int;
    v_share int;
    v_left int;
    r record;
    v_coins int;
begin
    select * into v_group from public.mission_groups where id = p_mission for update;
    if v_group.id is null or v_group.state <> 'running'
       or (now() at time zone 'utc')::date - v_group.started_on < v_group.window_days then
        return;
    end if;
    v_needed := public.mission_days_needed(v_group.window_days);
    v_last := v_group.started_on + v_group.window_days - 1;

    insert into public.mission_results (mission_id, member, did_part, days)
    select p_mission, s.member,
           coalesce(min(u.days), 0) >= v_needed and count(o.member) = count(u.listing_id),
           coalesce(min(u.days), 0)
    from public.mission_seats s
    join public.mission_seats o on o.mission_id = s.mission_id and o.member <> s.member
    left join lateral (
        select c.listing_id, count(distinct c.day)::int as days
        from public.mission_checkins c
        where c.mission_id = s.mission_id and c.member = s.member
          and c.listing_id = o.listing_id
          and c.day between v_group.started_on and v_last
        group by c.listing_id
    ) u on true
    where s.mission_id = p_mission
    group by s.member
    on conflict do nothing;

    -- What the members actually paid in, less anything already paid back.
    select coalesce(-sum(delta), 0)::int into v_pot
    from public.coin_entries
    where ref = p_mission and reason in ('mission_entry', 'mission_refund');
    select count(*) filter (where did_part), count(*) into v_done, v_members
    from public.mission_results where mission_id = p_mission;

    if v_done > 0 and v_pot > 0 then
        v_share := v_pot / v_done;
        v_left := v_pot - v_share * v_done;
        for r in
            select res.member from public.mission_results res
            join public.mission_seats s on s.mission_id = res.mission_id and s.member = res.member
            where res.mission_id = p_mission and res.did_part
            order by s.seat
        loop
            -- The coins that do not divide evenly go to the first seats, one each.
            v_coins := v_share + case when v_left > 0 then 1 else 0 end;
            v_left := v_left - 1;
            insert into public.coin_entries (account, delta, reason, ref)
            select r.member, least(v_coins, v_group.entry_fee), 'mission_refund', p_mission
            where least(v_coins, v_group.entry_fee) > 0;
            insert into public.coin_entries (account, delta, reason, ref)
            select r.member, v_coins - v_group.entry_fee, 'mission_bonus', p_mission
            where v_coins > v_group.entry_fee;
            update public.mission_results set coins = v_coins
            where mission_id = p_mission and member = r.member;
        end loop;
    end if;

    update public.mission_groups set state = 'completed', completed_at = now() where id = p_mission;
    insert into public.mission_messages (mission_id, kind, body)
    values (p_mission, 'complete', v_done || '/' || v_members);
end $$;

/** Hourly: completes every mission whose days are over. */
create or replace function public.mission_tick() returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_id uuid;
begin
    for v_id in
        select id from public.mission_groups
        where state = 'running'
          and (now() at time zone 'utc')::date - started_on >= window_days
    loop
        perform public.mission_complete(v_id);
    end loop;
end $$;

/**
 * Leaves a mission that has not started, with the fee back.
 * { left: true, coins } or { left: false, reason }: signed_out, not_member,
 * started.
 */
create or replace function public.leave_mission(p_mission uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_group public.mission_groups%rowtype;
    v_seat public.mission_seats%rowtype;
    v_paid int;
begin
    if auth.uid() is null then
        return jsonb_build_object('left', false, 'reason', 'signed_out');
    end if;
    -- The same lock take_seat holds, so a seat cannot be taken and left at once.
    select * into v_group from public.mission_groups where id = p_mission for update;
    select * into v_seat from public.mission_seats
    where mission_id = p_mission and member = auth.uid();
    if v_group.id is null or v_seat.member is null then
        return jsonb_build_object('left', false, 'reason', 'not_member');
    end if;
    if v_group.state <> 'recruiting' then
        return jsonb_build_object('left', false, 'reason', 'started');
    end if;

    delete from public.mission_seats where mission_id = p_mission and member = auth.uid();
    -- Close the gap in two moves, so no two seats share a number on the way.
    update public.mission_seats set seat = -seat
    where mission_id = p_mission and seat > v_seat.seat;
    update public.mission_seats set seat = -seat - 1
    where mission_id = p_mission and seat < 0;

    -- Back exactly what this member paid for this mission and has not had back.
    select coalesce(-sum(delta), 0)::int into v_paid
    from public.coin_entries
    where account = auth.uid() and ref = p_mission and reason in ('mission_entry', 'mission_refund');
    if v_paid > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (auth.uid(), v_paid, 'mission_refund', p_mission);
    end if;

    insert into public.mission_messages (mission_id, author, kind, body)
    select p_mission, auth.uid(), 'leave', l.title
    from public.listings l where l.id = v_seat.listing_id;

    return jsonb_build_object('left', true, 'coins', greatest(v_paid, 0));
end $$;

-- ---------------------------------------------------------------- checkins

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
    if v_group.state = 'completed'
       or (v_group.state = 'running'
           and (now() at time zone 'utc')::date - v_group.started_on >= v_group.window_days) then
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
 * One group as the app shows it, with the apps placed in it and, once it is
 * over, how it ended for the caller.
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
        'days_needed', public.mission_days_needed(g.window_days),
        -- The caller's own ending, once there is one.
        'result', (
            select jsonb_build_object('did_part', r.did_part, 'coins', r.coins, 'days', r.days)
            from public.mission_results r
            where r.mission_id = g.id and r.member = auth.uid()
        ),
        'completers', case when g.state = 'completed' then (
            select count(*)::int from public.mission_results r
            where r.mission_id = g.id and r.did_part
        ) end,
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
                -- Days the caller has used this app in the mission.
                'my_days', case when me.member and not a.ghost then (
                    select count(distinct c.day)::int from public.mission_checkins c
                    where c.mission_id = g.id and c.member = auth.uid() and c.listing_id = a.id
                ) end,
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

-- ------------------------------------------------------------------ grants

revoke all on function public.mission_days_needed(int) from public, anon, authenticated;
revoke all on function public.mission_complete(uuid) from public, anon, authenticated;
revoke all on function public.mission_tick() from public, anon, authenticated;
revoke all on function public.leave_mission(uuid) from public, anon;
grant execute on function public.leave_mission(uuid) to authenticated;

do $$
begin
    if exists (select 1 from pg_namespace where nspname = 'cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'onedevs-missions';
        perform cron.schedule('onedevs-missions', '17 * * * *', 'select public.mission_tick()');
    end if;
end $$;
