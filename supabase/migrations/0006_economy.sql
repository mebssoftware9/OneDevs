-- The economy, with its numbers written down.
--
--   A new account is granted 75 DevCoins. This is the only place coins are
--   created, and it happens once per person.
--   Listing an app costs 75, held in escrow.
--   Testing someone's app pays 25.
--
-- So a listing funds exactly three tests, and a new developer can list once
-- before they have to earn. Every coin paid to a tester comes out of a
-- developer's escrow; nothing appears from nowhere.

alter table public.coin_entries drop constraint coin_entries_reason_check;
alter table public.coin_entries add constraint coin_entries_reason_check
    check (reason in (
        'grant',
        'listing_escrow', 'test_reward', 'listing_refund',
        'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund'
    ));

alter table public.listings
    add column reward int not null default 25 check (reward >= 0),
    add column slots int not null default 3 check (slots between 1 and 100),
    add column escrowed int not null default 0 check (escrowed >= 0);

-- ----------------------------------------------------------- welcome grant
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id, handle)
    values (new.id, 'dev' || substr(replace(new.id::text, '-', ''), 1, 9));

    -- Enough to list one app. The reason it is a grant and not a gift with no
    -- name is that every coin in circulation has to be explainable, and this
    -- is the only line in the ledger that adds any.
    insert into public.coin_entries (account, delta, reason)
    values (new.id, 75, 'grant');

    return new;
end $$;

-- --------------------------------------------------------- listing escrow
/**
 * Charges for a listing as it is created.
 *
 * A trigger rather than a function the client calls, so the ordinary insert
 * path cannot be used to list without paying. It fires BEFORE INSERT, which in
 * Postgres also runs for an upsert that is about to become an update -- hence
 * the existence check: editing a listing must not charge for it twice.
 */
create or replace function public.charge_for_listing() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_cost int;
begin
    if exists (select 1 from public.listings where id = new.id) then
        return new;
    end if;

    v_cost := new.slots * new.reward;

    perform 1 from public.profiles where id = new.owner for update;

    if public.balance_of(new.owner) < v_cost then
        raise exception 'not enough DevCoins: listing this app costs %', v_cost;
    end if;

    new.escrowed := v_cost;
    insert into public.coin_entries (account, delta, reason, ref)
    values (new.owner, -v_cost, 'listing_escrow', new.id);

    return new;
end $$;

create trigger listings_charge
before insert on public.listings
for each row execute function public.charge_for_listing();

-- ------------------------------------------------------------------ tests
-- One test per tester per listing, and one per device. The device is a speed
-- bump against a person testing the same app from several accounts; it is
-- resettable, so it stops the lazy version and not a determined one.
create table public.tests (
    id uuid primary key default gen_random_uuid(),
    listing_id uuid not null references public.listings (id) on delete cascade,
    tester uuid not null references public.profiles (id) on delete cascade,
    seconds int not null check (seconds between 32 and 86400),
    device text not null,
    integrity text,
    claimed_at timestamptz not null default now(),
    unique (listing_id, tester),
    unique (listing_id, device)
);

alter table public.tests enable row level security;

create policy "own tests are visible" on public.tests
    for select using (auth.uid() = tester);

grant select on public.tests to authenticated;

/**
 * A completed test, and the coins for it.
 *
 * The device reports how long it saw the app in the foreground; the server
 * decides everything else. Seconds are checked, the slot count is checked, and
 * the unique index means a second claim for the same app earns nothing rather
 * than raising -- a tester who taps twice should see "already claimed", not an
 * error.
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
    v_slots int;
    v_taken int;
    v_rows int;
begin
    select owner, reward, slots into v_owner, v_reward, v_slots
    from public.listings where id = p_listing for update;

    if v_owner is null then raise exception 'no such listing'; end if;
    if v_owner = auth.uid() then
        raise exception 'testing your own app proves nothing';
    end if;
    if p_device is null or char_length(p_device) < 8 then
        raise exception 'a device is required';
    end if;
    if p_seconds < 32 then
        return jsonb_build_object('claimed', false, 'reason', 'too_short');
    end if;

    select count(*) into v_taken from public.tests where listing_id = p_listing;
    if v_taken >= v_slots then
        return jsonb_build_object('claimed', false, 'reason', 'full');
    end if;

    insert into public.tests (listing_id, tester, seconds, device)
    values (p_listing, auth.uid(), p_seconds, p_device)
    on conflict do nothing;
    get diagnostics v_rows = row_count;

    if v_rows <> 1 then
        return jsonb_build_object('claimed', false, 'reason', 'already');
    end if;

    insert into public.coin_entries (account, delta, reason, ref)
    values (auth.uid(), v_reward, 'test_reward', p_listing);

    return jsonb_build_object('claimed', true, 'coins', v_reward);
end $$;

revoke all on function public.claim_test(uuid, int, text) from public, anon;
grant execute on function public.claim_test(uuid, int, text) to authenticated;

-- ------------------------------------------------------------ mission gate
/**
 * A mission slot now asks the tester to have tested.
 *
 * Not a balance: a new account holds the welcome grant before it has done
 * anything, so a balance threshold passes exactly the people it exists to
 * filter. Completed tests are the thing that cannot be had for free.
 */
create or replace function public.join_mission(p_mission uuid, p_device text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
    v_state text;
    v_slots int;
    v_owner uuid;
    v_taken int;
    v_tests int;
    v_enrollment uuid;
begin
    select m.state, m.slots, l.owner into v_state, v_slots, v_owner
    from public.missions m
    join public.listings l on l.id = m.listing_id
    where m.id = p_mission
    for update of m;

    if v_state is null then raise exception 'no such mission'; end if;
    if v_state <> 'open' then raise exception 'this mission is closed'; end if;
    if v_owner = auth.uid() then
        raise exception 'testing your own app proves nothing';
    end if;
    if p_device is null or char_length(p_device) < 8 then
        raise exception 'a device is required to join a mission';
    end if;

    select count(*) into v_tests from public.tests where tester = auth.uid();
    if v_tests < 3 then
        raise exception 'test three apps on the board before joining a mission';
    end if;

    select count(*) into v_taken
    from public.enrollments
    where mission_id = p_mission and state <> 'lapsed';

    if v_taken >= v_slots then raise exception 'this mission is full'; end if;

    insert into public.enrollments (mission_id, tester, device)
    values (p_mission, auth.uid(), p_device)
    returning id into v_enrollment;

    return v_enrollment;
end $$;
