-- A test reserves its reward before anyone spends time on it.
--
-- Until now a tester found out whether they could be paid only after the
-- thirty-two seconds: claim_test checked the device, the developer's balance
-- and everything else at the very end, and said no there. Five testers
-- starting on a listing that could fund one all did the work, and four were
-- refused. On the phone that looked like a reward being granted and taken
-- back, because it was.
--
-- Now "Test now" asks begin_test first. It refuses up front -- your own app,
-- already tested, this phone already used, the developer cannot pay -- and
-- otherwise opens a session that holds the reward against the developer's
-- balance. finish_test pays from that hold. Holds count against what a
-- developer can spend anywhere, so money that is promised stays promised.
--
-- Every answer is a value, never an exception. A raised error reaches the
-- client as an HTTP 400, which it cannot tell from a bad gateway, and a
-- refusal that looks like a network fault gets retried forever.

create table public.test_sessions (
    id uuid primary key default gen_random_uuid(),
    listing_id uuid not null references public.listings (id) on delete cascade,
    owner uuid not null references public.profiles (id) on delete cascade,
    tester uuid not null references public.profiles (id) on delete cascade,
    device text not null,
    reward int not null check (reward >= 0),
    started_at timestamptz not null default now(),
    -- Long enough to install an app from Play on a slow connection and use it.
    -- After this the hold stops counting, and paying depends on whether the
    -- developer can still afford it.
    expires_at timestamptz not null default now() + interval '2 hours',
    state text not null default 'open' check (state in ('open', 'paid', 'released')),
    settled_at timestamptz
);

-- One open session per tester per listing: asking twice returns the first.
create unique index test_sessions_one_open
    on public.test_sessions (listing_id, tester) where state = 'open';
create index test_sessions_owner_open
    on public.test_sessions (owner) where state = 'open';

alter table public.test_sessions enable row level security;
revoke all on public.test_sessions from anon, authenticated;

/** What an account has promised to open test sessions and not yet paid. */
create or replace function public.held_by(p_account uuid) returns int
language sql stable security definer set search_path = '' as $$
    select coalesce(sum(reward), 0)::int
    from public.test_sessions
    where owner = p_account and state = 'open' and expires_at > now();
$$;

/** What an account can still spend: its balance less what it has promised. */
create or replace function public.available_of(p_account uuid) returns int
language sql stable security definer set search_path = '' as $$
    select public.balance_of(p_account) - public.held_by(p_account);
$$;

revoke all on function public.held_by(uuid) from public, anon, authenticated;
revoke all on function public.available_of(uuid) from public, anon, authenticated;

/** A session as the app reads it. */
create or replace function public.session_json(s public.test_sessions) returns jsonb
language sql stable set search_path = '' as $$
    select jsonb_build_object(
        'session', s.id,
        'reward', s.reward,
        'started_at', s.started_at,
        'expires_at', s.expires_at
    );
$$;

revoke all on function public.session_json(public.test_sessions) from public, anon, authenticated;

/**
 * Where this tester stands with a listing, before they do anything.
 *
 * Read-only. The test screen asks this when it opens, so it can say "this
 * phone has already tested this app" before the button is pressed rather than
 * after thirty-two seconds of work.
 */
create or replace function public.test_status(p_listing uuid, p_device text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
    v_listing public.listings%rowtype;
    v_session public.test_sessions%rowtype;
begin
    if auth.uid() is null then
        return jsonb_build_object('state', 'signed_out');
    end if;
    select * into v_listing from public.listings where id = p_listing;
    if v_listing.id is null then
        return jsonb_build_object('state', 'missing');
    end if;
    if v_listing.owner = auth.uid() then
        return jsonb_build_object('state', 'own_app', 'reward', v_listing.reward);
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and tester = auth.uid()) then
        return jsonb_build_object('state', 'paid', 'reward', v_listing.reward);
    end if;
    if exists (select 1 from public.tests where listing_id = p_listing and device = p_device) then
        return jsonb_build_object('state', 'device_used', 'reward', v_listing.reward);
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
        return jsonb_build_object('state', 'device_in_use', 'reward', v_listing.reward);
    end if;
    if public.available_of(v_listing.owner) < v_listing.reward then
        return jsonb_build_object('state', 'unfunded', 'reward', v_listing.reward);
    end if;
    return jsonb_build_object('state', 'available', 'reward', v_listing.reward);
end;
$$;

/**
 * Starts a test and holds its reward.
 *
 * { ok: true, session, reward, started_at, expires_at } or
 * { ok: false, reason }. Asking again while a session is open returns the same
 * session, so a double tap or a retried request never holds twice.
 */
create or replace function public.begin_test(p_listing uuid, p_device text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_listing public.listings%rowtype;
    v_session public.test_sessions%rowtype;
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

    if public.available_of(v_listing.owner) < v_listing.reward then
        return jsonb_build_object('ok', false, 'reason', 'unfunded');
    end if;

    insert into public.test_sessions (listing_id, owner, tester, device, reward)
    values (p_listing, v_listing.owner, auth.uid(), p_device, v_listing.reward)
    returning * into v_session;

    return public.session_json(v_session) || jsonb_build_object('ok', true);
end;
$$;

/**
 * Pays a finished test from its hold.
 *
 * { paid: true, coins, repeat? } or { paid: false, reason }. Idempotent: the
 * same session asked a hundred times pays once, and every later answer is the
 * first answer. That is what makes a timeout harmless -- the client asks
 * again and gets told it was paid.
 *
 * too_short leaves the session open, so the tester can go back and finish.
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
        return jsonb_build_object('paid', true, 'coins', v_session.reward, 'repeat', true);
    end if;

    -- Paid some other way, by an older version of the app.
    if exists (
        select 1 from public.tests where listing_id = v_session.listing_id and tester = auth.uid()
    ) then
        update public.test_sessions set state = 'paid', settled_at = now() where id = p_session;
        return jsonb_build_object('paid', true, 'coins', v_session.reward, 'repeat', true);
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
    if v_session.expires_at <= now()
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

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_session.owner, -v_session.reward, 'test_payment', v_session.listing_id),
           (auth.uid(), v_session.reward, 'test_reward', v_session.listing_id);

    update public.test_sessions set state = 'paid', settled_at = now() where id = p_session;

    return jsonb_build_object('paid', true, 'coins', v_session.reward);
end;
$$;

/**
 * The old one-step claim, kept for versions of the app already installed.
 *
 * Same answers as before, with two fixes: refusals are values rather than
 * exceptions, and money held for other testers' open sessions is not
 * available to pay this one.
 */
create or replace function public.claim_test(
    p_listing uuid,
    p_seconds int,
    p_device text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_reward int;
    v_rows int;
begin
    if auth.uid() is null then
        return jsonb_build_object('claimed', false, 'reason', 'signed_out');
    end if;
    select owner, reward into v_owner, v_reward
    from public.listings where id = p_listing for update;

    if v_owner is null then
        return jsonb_build_object('claimed', false, 'reason', 'missing');
    end if;
    if v_owner = auth.uid() then
        return jsonb_build_object('claimed', false, 'reason', 'own_app');
    end if;
    if p_device is null or char_length(p_device) < 8 then
        return jsonb_build_object('claimed', false, 'reason', 'device');
    end if;

    if exists (select 1 from public.tests where listing_id = p_listing and tester = auth.uid()) then
        return jsonb_build_object('claimed', true, 'coins', v_reward, 'repeat', true);
    end if;
    if p_seconds < 32 then
        return jsonb_build_object('claimed', false, 'reason', 'too_short');
    end if;

    perform 1 from public.profiles where id = v_owner for update;
    if public.available_of(v_owner) < v_reward then
        return jsonb_build_object('claimed', false, 'reason', 'unfunded');
    end if;

    insert into public.tests (listing_id, tester, seconds, device)
    values (p_listing, auth.uid(), p_seconds, p_device)
    on conflict do nothing;
    get diagnostics v_rows = row_count;
    if v_rows <> 1 then
        return jsonb_build_object('claimed', false, 'reason', 'device_used');
    end if;

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_owner, -v_reward, 'test_payment', p_listing),
           (auth.uid(), v_reward, 'test_reward', p_listing);

    return jsonb_build_object('claimed', true, 'coins', v_reward);
end $$;

-- The board offers what can actually be paid: available money, not balance.
-- Written out rather than calling available_of: a view checks function
-- permissions as whoever reads it, and available_of is not for everyone. The
-- sums themselves run as the view's owner, as the balance always did.
create or replace view public.board_listings as
select l.*
from public.listings l
where (
    select coalesce(sum(c.delta), 0)
    from public.coin_entries c
    where c.account = l.owner
) - (
    select coalesce(sum(s.reward), 0)
    from public.test_sessions s
    where s.owner = l.owner and s.state = 'open' and s.expires_at > now()
) >= l.reward
and not exists (
    select 1 from public.tests t
    where t.listing_id = l.id and t.tester = auth.uid()
);

grant select on public.board_listings to authenticated;

-- A mission seat is paid from what is available too. Coins promised to a
-- tester who is mid-test are not the developer's to spend.
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
    if public.available_of(auth.uid()) < v_group.entry_fee then
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

-- How many more tests a listing can pay for counts the promises as well.
create or replace function public.listing_stats(p_listing uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_tests int;
    v_spent int;
    v_reward int;
    v_balance int;
begin
    select owner, reward into v_owner, v_reward
    from public.listings where id = p_listing;

    if v_owner is null then raise exception 'no such listing'; end if;
    if v_owner <> auth.uid() then raise exception 'not your listing'; end if;

    select count(*) into v_tests from public.tests where listing_id = p_listing;

    -- Payments are recorded as negative against the developer, so the amount
    -- spent is their sum inverted.
    select coalesce(-sum(delta), 0)::int into v_spent
    from public.coin_entries
    where ref = p_listing and reason = 'test_payment';

    v_balance := public.available_of(v_owner);

    return jsonb_build_object(
        'testers', v_tests,
        'spent', v_spent,
        -- How many more tests the current balance can pay for. Zero means the
        -- listing has already left the board.
        'tests_left', case when v_reward > 0 then v_balance / v_reward else 0 end
    );
end $$;

revoke all on function public.test_status(uuid, text) from public, anon;
revoke all on function public.begin_test(uuid, text) from public, anon;
revoke all on function public.finish_test(uuid, int) from public, anon;
revoke all on function public.claim_test(uuid, int, text) from public, anon;
grant execute on function public.test_status(uuid, text) to authenticated;
grant execute on function public.begin_test(uuid, text) to authenticated;
grant execute on function public.finish_test(uuid, int) to authenticated;
grant execute on function public.claim_test(uuid, int, text) to authenticated;
