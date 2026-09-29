-- A mission is sixteen developers testing each other's apps.
--
-- The missions table from 0001 was a developer's campaign: one listing, paid
-- testers, escrow. What the app offers now is a group: each member brings one
-- app in testing, takes one of sixteen seats, and tests the other fifteen for
-- fourteen days. Nobody is paid by anybody; a seat costs a fixed entry fee.
--
-- One mission recruits at a time. Everyone joining joins the same group, so
-- groups fill instead of sixteen half-empty ones waiting on each other. When
-- the sixteenth seat is taken the group starts and the next one opens.
--
-- The old tables and functions are left alone: nothing calls them, and
-- dropping them is a separate decision from adding this.

create sequence public.mission_group_number;

create table public.mission_groups (
    id uuid primary key default gen_random_uuid(),
    number int not null default nextval('public.mission_group_number') unique,
    name text not null,
    slots int not null default 16 check (slots between 2 and 100),
    entry_fee int not null default 100 check (entry_fee >= 0),
    window_days int not null default 14 check (window_days between 1 and 60),
    state text not null default 'recruiting' check (state in ('recruiting', 'running')),
    started_on date,
    created_at timestamptz not null default now()
);

-- At most one group recruits at any moment.
create unique index mission_groups_one_recruiting
    on public.mission_groups (state) where state = 'recruiting';

create table public.mission_seats (
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    member uuid not null references public.profiles (id) on delete cascade,
    listing_id uuid not null references public.listings (id) on delete cascade,
    -- Same speed bump as tests: one phone, one seat per group.
    device text not null,
    seat int not null,
    joined_at timestamptz not null default now(),
    primary key (mission_id, member),
    unique (mission_id, seat),
    unique (mission_id, listing_id),
    unique (mission_id, device)
);

create index mission_seats_member_idx on public.mission_seats (member);

-- Reached only through the functions below, which are security definer.
alter table public.mission_groups enable row level security;
alter table public.mission_seats enable row level security;
revoke all on public.mission_groups from anon, authenticated;
revoke all on public.mission_seats from anon, authenticated;

alter table public.coin_entries drop constraint coin_entries_reason_check;
alter table public.coin_entries add constraint coin_entries_reason_check
    check (reason in (
        'grant', 'test_reward', 'test_payment',
        'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund',
        'mission_entry'
    ));

/** The group that is recruiting, opening a new one if none is. */
create or replace function public.recruiting_group() returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_id uuid;
    v_names text[] := array[
        'Ardent', 'Bellwether', 'Cinder', 'Dovetail', 'Ember', 'Foxglove',
        'Granite', 'Halcyon', 'Indigo', 'Juniper', 'Kestrel', 'Lodestar',
        'Meridian', 'Nimbus', 'Onyx', 'Perihelion', 'Quartz', 'Rowan',
        'Sable', 'Tamarack', 'Umbra', 'Vesper', 'Willow', 'Zenith'
    ];
    v_next int;
begin
    select id into v_id from public.mission_groups where state = 'recruiting';
    if v_id is not null then return v_id; end if;

    v_next := nextval('public.mission_group_number');
    insert into public.mission_groups (number, name)
    values (
        v_next,
        'Mission ' || v_names[1 + ((v_next - 1) % array_length(v_names, 1))]
            || case when v_next > array_length(v_names, 1)
                    then ' ' || (1 + (v_next - 1) / array_length(v_names, 1))::text
                    else '' end
    )
    on conflict do nothing
    returning id into v_id;

    -- Another caller opened it first; the unique index saw to that.
    if v_id is null then
        select id into v_id from public.mission_groups where state = 'recruiting';
    end if;
    return v_id;
end;
$$;

/** One group as the app shows it: who sits where, and where it is in its life. */
create or replace function public.mission_view(p_mission uuid) returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object(
        'id', g.id,
        'name', g.name,
        'slots', g.slots,
        'entry_fee', g.entry_fee,
        'window_days', g.window_days,
        'state', case
            when g.state = 'running'
                 and (now() at time zone 'utc')::date - g.started_on >= g.window_days
                then 'elapsed'
            else g.state end,
        'day', case
            when g.started_on is null then 0
            else least(g.window_days, (now() at time zone 'utc')::date - g.started_on + 1) end,
        'member', exists (
            select 1 from public.mission_seats s
            where s.mission_id = g.id and s.member = auth.uid()
        ),
        'seats', coalesce((
            select jsonb_agg(jsonb_build_object(
                'seat', s.seat,
                'listing', l.id,
                'title', l.title,
                'icon_url', l.icon_url,
                'mine', s.member = auth.uid()
            ) order by s.seat)
            from public.mission_seats s
            join public.listings l on l.id = s.listing_id
            where s.mission_id = g.id
        ), '[]'::jsonb)
    )
    from public.mission_groups g
    where g.id = p_mission;
$$;

/** The one group that is recruiting right now. */
create or replace function public.current_mission() returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_id uuid;
begin
    -- Two statements, not one: mission_view is stable and reads the snapshot
    -- its statement started with, which would not yet hold a group this call
    -- has just opened.
    v_id := public.recruiting_group();
    return public.mission_view(v_id);
end;
$$;

/** Every group the caller holds a seat in, newest first. */
create or replace function public.my_missions() returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(jsonb_agg(public.mission_view(s.mission_id) order by s.joined_at desc), '[]'::jsonb)
    from public.mission_seats s
    where s.member = auth.uid();
$$;

/**
 * Takes a seat in the recruiting group with one of the caller's testing apps.
 *
 * Refusals come back as { joined: false, reason } rather than errors, because
 * "you cannot afford it" is an answer, not a fault. The fee and the seat are
 * one transaction: nobody pays without sitting, nobody sits without paying.
 */
create or replace function public.take_seat(
    p_mission uuid,
    p_listing uuid,
    p_device text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_group public.mission_groups%rowtype;
    v_taken int;
begin
    if auth.uid() is null then raise exception 'not signed in'; end if;
    if p_device is null or char_length(p_device) < 8 then
        raise exception 'a device is required';
    end if;

    select * into v_group from public.mission_groups where id = p_mission for update;
    if v_group.id is null then
        return jsonb_build_object('joined', false, 'reason', 'missing');
    end if;
    if exists (
        select 1 from public.mission_seats
        where mission_id = p_mission and member = auth.uid()
    ) then
        return jsonb_build_object('joined', true, 'repeat', true);
    end if;

    if v_group.state <> 'recruiting' then
        return jsonb_build_object('joined', false, 'reason', 'full');
    end if;

    -- One active mission per developer: fourteen days of testing fifteen
    -- apps is the whole commitment, and a second group would halve it.
    if exists (
        select 1 from public.mission_seats s
        join public.mission_groups g on g.id = s.mission_id
        where s.member = auth.uid()
          and (g.state = 'recruiting'
               or (now() at time zone 'utc')::date - g.started_on < g.window_days)
    ) then
        return jsonb_build_object('joined', false, 'reason', 'busy');
    end if;

    if not exists (
        select 1 from public.listings
        where id = p_listing and owner = auth.uid() and channel = 'testing'
    ) then
        return jsonb_build_object('joined', false, 'reason', 'not_yours');
    end if;

    if exists (
        select 1 from public.mission_seats
        where mission_id = p_mission and device = p_device
    ) then
        return jsonb_build_object('joined', false, 'reason', 'device');
    end if;

    perform 1 from public.profiles where id = auth.uid() for update;
    if public.balance_of(auth.uid()) < v_group.entry_fee then
        return jsonb_build_object('joined', false, 'reason', 'broke');
    end if;

    select count(*) into v_taken from public.mission_seats where mission_id = p_mission;
    if v_taken >= v_group.slots then
        return jsonb_build_object('joined', false, 'reason', 'full');
    end if;

    insert into public.mission_seats (mission_id, member, listing_id, device, seat)
    values (p_mission, auth.uid(), p_listing, p_device, v_taken + 1);

    if v_group.entry_fee > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (auth.uid(), -v_group.entry_fee, 'mission_entry', p_mission);
    end if;

    -- The last seat starts the clock and opens the next group.
    if v_taken + 1 >= v_group.slots then
        update public.mission_groups
        set state = 'running', started_on = (now() at time zone 'utc')::date
        where id = p_mission;
        perform public.recruiting_group();
    end if;

    return jsonb_build_object('joined', true, 'coins', v_group.entry_fee);
end;
$$;

revoke all on function public.recruiting_group() from public, anon, authenticated;
revoke all on function public.mission_view(uuid) from public, anon, authenticated;
revoke all on function public.current_mission() from public, anon;
revoke all on function public.my_missions() from public, anon;
revoke all on function public.take_seat(uuid, uuid, text) from public, anon;
grant execute on function public.current_mission() to authenticated;
grant execute on function public.my_missions() to authenticated;
grant execute on function public.take_seat(uuid, uuid, text) to authenticated;
