-- A mission you can actually work in: tasks, progress, and one shared room.
--
-- Until now a seat was the whole of a mission. Members could see sixteen
-- icons and nothing to do with them: no link to the other apps, no record of
-- who had tested whose, and no way to talk to the group.
--
--   * mission_checkin() records that a member used another member's app,
--     once per app per day, with the foreground seconds the phone measured.
--     Before the mission starts it counts as pre-installing; once it runs it
--     is the daily task the rules already describe.
--   * mission_view() gives members what they need to do those tasks -- whose
--     app it is, where to get it, whether they have done it today -- and how
--     every member is doing.
--   * Mission Command is one room per mission that every member reads and
--     writes. Joins and the start are posted into it by the server, so the
--     room is also the mission's log.

-- ----------------------------------------------------------------- checkins

create table if not exists public.mission_checkins (
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    member uuid not null references public.profiles (id) on delete cascade,
    listing_id uuid not null references public.listings (id) on delete cascade,
    day date not null default (now() at time zone 'utc')::date,
    seconds int not null check (seconds between 0 and 86400),
    device text not null,
    at timestamptz not null default now(),
    primary key (mission_id, member, listing_id, day)
);

create index if not exists mission_checkins_mission_day
    on public.mission_checkins (mission_id, day);

alter table public.mission_checkins enable row level security;
revoke all on public.mission_checkins from anon, authenticated;

/**
 * Records that the caller used another member's app today.
 *
 * { ok: true, repeat? } or { ok: false, reason }. Idempotent per app per day:
 * a second check-in the same day keeps the longer measurement.
 */
create or replace function public.mission_checkin(
    p_mission uuid,
    p_listing uuid,
    p_seconds int,
    p_device text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_group public.mission_groups%rowtype;
    v_owner uuid;
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
        return jsonb_build_object('ok', false, 'reason', 'not_in_mission');
    end if;
    if v_owner = auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'own_app');
    end if;
    -- Same bar as a paid test: nobody has used an app in less than this.
    if p_seconds is null or p_seconds < 32 then
        return jsonb_build_object('ok', false, 'reason', 'too_short');
    end if;

    insert into public.mission_checkins (mission_id, member, listing_id, seconds, device)
    values (p_mission, auth.uid(), p_listing, least(p_seconds, 86400), coalesce(p_device, ''))
    on conflict (mission_id, member, listing_id, day) do update
        set seconds = greatest(public.mission_checkins.seconds, excluded.seconds);

    return jsonb_build_object('ok', true);
end $$;

revoke all on function public.mission_checkin(uuid, uuid, int, text) from public, anon;
grant execute on function public.mission_checkin(uuid, uuid, int, text) to authenticated;

-- ------------------------------------------------------------ mission view

/**
 * One group as the app shows it.
 *
 * Everyone sees the seats. Members also see, for every seat, whose app it is,
 * where to get it, whether they have used it today and ever, and how many of
 * the others that seat's member has used -- today once the mission runs,
 * ever while it is still filling.
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
        'seats', coalesce((
            select jsonb_agg(jsonb_build_object(
                'seat', s.seat,
                'listing', l.id,
                'title', l.title,
                'icon_url', l.icon_url,
                'mine', s.member = auth.uid(),
                'owner_name', case when me.member
                    then coalesce(nullif(p.display_name, ''), p.handle) end,
                'package_name', case when me.member then l.package_name end,
                'play_url', case when me.member then l.play_url end,
                'done_today', me.member and exists (
                    select 1 from public.mission_checkins c
                    where c.mission_id = g.id and c.member = auth.uid()
                      and c.listing_id = l.id and c.day = (select d from today)
                ),
                'done_ever', me.member and exists (
                    select 1 from public.mission_checkins c
                    where c.mission_id = g.id and c.member = auth.uid()
                      and c.listing_id = l.id
                ),
                'tested', case when me.member then (
                    select count(distinct c.listing_id)::int
                    from public.mission_checkins c
                    where c.mission_id = g.id and c.member = s.member
                      and (g.state = 'recruiting' or c.day = (select d from today))
                ) end
            ) order by s.seat)
            from public.mission_seats s
            join public.listings l on l.id = s.listing_id
            join public.profiles p on p.id = s.member
            where s.mission_id = g.id
        ), '[]'::jsonb)
    )
    from g, me;
$$;

revoke all on function public.mission_view(uuid) from public, anon, authenticated;

-- --------------------------------------------------------- mission command

create table if not exists public.mission_messages (
    id bigint generated always as identity primary key,
    mission_id uuid not null references public.mission_groups (id) on delete cascade,
    -- Null for the server's own lines: joins, the start.
    author uuid references public.profiles (id) on delete set null,
    kind text not null default 'chat' check (kind in ('chat', 'join', 'start')),
    body text not null check (char_length(body) between 1 and 500),
    created_at timestamptz not null default now()
);

create index if not exists mission_messages_feed
    on public.mission_messages (mission_id, id desc);
create index if not exists mission_messages_author_recent
    on public.mission_messages (author, created_at desc) where kind = 'chat';

alter table public.mission_messages enable row level security;
revoke all on public.mission_messages from anon, authenticated;

/**
 * Posts to the mission's room. Members only.
 *
 * { ok: true, id } or { ok: false, reason }. Limited to one message every
 * two seconds and sixty an hour per person, which no conversation needs to
 * exceed and which keeps one person from drowning the room.
 */
create or replace function public.mission_post(p_mission uuid, p_body text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_body text := btrim(coalesce(p_body, ''));
    v_id bigint;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if not exists (
        select 1 from public.mission_seats where mission_id = p_mission and member = auth.uid()
    ) then
        return jsonb_build_object('ok', false, 'reason', 'not_member');
    end if;
    if char_length(v_body) = 0 then
        return jsonb_build_object('ok', false, 'reason', 'empty');
    end if;
    if char_length(v_body) > 500 then
        return jsonb_build_object('ok', false, 'reason', 'too_long');
    end if;
    if exists (
        select 1 from public.mission_messages
        where author = auth.uid() and kind = 'chat'
          and created_at > now() - interval '2 seconds'
    ) or (
        select count(*) from public.mission_messages
        where author = auth.uid() and kind = 'chat'
          and created_at > now() - interval '1 hour'
    ) >= 60 then
        return jsonb_build_object('ok', false, 'reason', 'slow_down');
    end if;

    insert into public.mission_messages (mission_id, author, body)
    values (p_mission, auth.uid(), v_body)
    returning id into v_id;
    return jsonb_build_object('ok', true, 'id', v_id);
end $$;

/**
 * The room, oldest first: messages after [p_after], or the latest [p_limit]
 * when p_after is 0. Members only; anyone else gets an empty list.
 */
create or replace function public.mission_feed(
    p_mission uuid,
    p_after bigint default 0,
    p_limit int default 100
) returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(m.j order by m.id), '[]'::jsonb)
    from (
        select x.id, jsonb_build_object(
            'id', x.id,
            'kind', x.kind,
            'body', x.body,
            'at', x.created_at,
            'mine', x.author is not null and x.author = auth.uid(),
            'author', coalesce(nullif(p.display_name, ''), p.handle),
            'seat', s.seat
        ) as j
        from public.mission_messages x
        left join public.profiles p on p.id = x.author
        left join public.mission_seats s on s.mission_id = x.mission_id and s.member = x.author
        where x.mission_id = p_mission
          and x.id > coalesce(p_after, 0)
          and exists (
              select 1 from public.mission_seats me
              where me.mission_id = p_mission and me.member = auth.uid()
          )
        order by x.id desc
        limit least(greatest(coalesce(p_limit, 100), 1), 200)
    ) m;
$$;

revoke all on function public.mission_post(uuid, text) from public, anon;
revoke all on function public.mission_feed(uuid, bigint, int) from public, anon;
grant execute on function public.mission_post(uuid, text) to authenticated;
grant execute on function public.mission_feed(uuid, bigint, int) to authenticated;

-- The server's own lines: who joined with what, and when the clock started.
-- The body is the fact; the app words it in the reader's language.
create or replace function public.mission_seat_posted() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.mission_messages (mission_id, author, kind, body)
    select new.mission_id, new.member, 'join', l.title
    from public.listings l where l.id = new.listing_id;
    return null;
end $$;

create or replace function public.mission_start_posted() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if old.state = 'recruiting' and new.state = 'running' then
        insert into public.mission_messages (mission_id, kind, body)
        values (new.id, 'start', new.window_days::text);
    end if;
    return null;
end $$;

revoke all on function public.mission_seat_posted() from public, anon, authenticated;
revoke all on function public.mission_start_posted() from public, anon, authenticated;

drop trigger if exists mission_seat_posted on public.mission_seats;
create trigger mission_seat_posted
    after insert on public.mission_seats
    for each row execute function public.mission_seat_posted();

drop trigger if exists mission_start_posted on public.mission_groups;
create trigger mission_start_posted
    after update of state on public.mission_groups
    for each row execute function public.mission_start_posted();
