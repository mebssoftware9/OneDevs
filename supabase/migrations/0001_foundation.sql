-- OneDevs foundation: identity, listings, missions, evidence, coins.
--
-- Two rules shape everything below.
--
-- 1. The coin ledger is append-only and no client can write to it. Balance is
--    sum(delta), never a stored number, so a balance cannot drift from its
--    history. Every movement happens inside a function that the database runs
--    as its owner, with row-level security denying direct writes to everyone.
--
-- 2. A day of testing is stamped by the server. The device reports how many
--    seconds it observed, but never which day that was -- otherwise changing
--    the phone clock fabricates a fortnight.

-- ---------------------------------------------------------------- identity

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    handle text not null unique check (handle ~ '^[a-z0-9_]{3,20}$'),
    display_name text,
    created_at timestamptz not null default now()
);

-- A profile exists the moment an account does, so nothing downstream has to
-- cope with a signed-in user who has no row.
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id, handle)
    values (new.id, 'dev' || substr(replace(new.id::text, '-', ''), 1, 9));
    return new;
end $$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------- listings

create table public.listings (
    id uuid primary key default gen_random_uuid(),
    owner uuid not null references public.profiles (id) on delete cascade,
    package_name text not null
        check (package_name ~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'),
    title text not null check (char_length(title) between 1 and 80),
    category text not null,
    channel text not null check (channel in ('testing', 'live')),
    size_bytes bigint check (size_bytes is null or size_bytes > 0),
    test_note text,
    play_url text,
    icon_url text,
    -- What the Play check last found. null means never asked; an unreachable
    -- Play leaves the previous answer alone rather than writing "unknown".
    public_listing boolean,
    checked_at timestamptz,
    created_at timestamptz not null default now(),
    unique (owner, package_name, channel)
);

create index listings_channel_idx on public.listings (channel, created_at desc);

-- ---------------------------------------------------------------- missions

-- The developer's campaign: slots, the continuity it demands, and the coins
-- put up front to pay for it.
create table public.missions (
    id uuid primary key default gen_random_uuid(),
    listing_id uuid not null references public.listings (id) on delete cascade,
    slots int not null check (slots between 1 and 100),
    required_days int not null default 14 check (required_days between 1 and 60),
    reward_per_day int not null check (reward_per_day >= 0),
    completion_bonus int not null check (completion_bonus >= 0),
    -- What one tester can earn from this mission, at most. Held per slot so a
    -- slot can be settled on its own when its tester finishes or drops.
    slot_cost int not null check (slot_cost >= 0),
    escrowed int not null check (escrowed >= 0),
    state text not null default 'open' check (state in ('open', 'closed')),
    created_at timestamptz not null default now()
);

create index missions_open_idx on public.missions (state, created_at desc);

-- One tester's seat in a mission.
create table public.enrollments (
    id uuid primary key default gen_random_uuid(),
    mission_id uuid not null references public.missions (id) on delete cascade,
    tester uuid not null references public.profiles (id) on delete cascade,
    -- A speed bump against one person filling a mission with their own
    -- accounts. It is resettable and therefore not proof; Play Integrity is
    -- what will make it evidence. It stops the lazy version today.
    device text not null,
    joined_on date not null default (now() at time zone 'utc')::date,
    state text not null default 'active'
        check (state in ('active', 'complete', 'lapsed')),
    unique (mission_id, tester),
    unique (mission_id, device)
);

-- The evidence. One row per tester per day, so re-submitting is a no-op rather
-- than a second payday. seconds is what the device observed in the foreground;
-- day is the server's, never the device's.
create table public.mission_days (
    enrollment_id uuid not null references public.enrollments (id) on delete cascade,
    day date not null,
    seconds int not null check (seconds between 32 and 86400),
    -- Reserved for a Play Integrity verdict. Empty until coins are worth
    -- forging, but the column exists now so adding it is not a migration
    -- against live balances.
    integrity text,
    recorded_at timestamptz not null default now(),
    primary key (enrollment_id, day)
);

-- ------------------------------------------------------------------- coins

create table public.coin_entries (
    id bigint generated always as identity primary key,
    account uuid not null references public.profiles (id) on delete cascade,
    delta int not null check (delta <> 0),
    reason text not null check (reason in (
        'grant', 'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund'
    )),
    ref uuid,
    created_at timestamptz not null default now()
);

create index coin_entries_account_idx on public.coin_entries (account, id desc);

-- security_invoker so row-level security on coin_entries still applies through
-- the view; without it the view would happily show everyone everyone's money.
create view public.coin_balance with (security_invoker = true) as
select p.id as account, coalesce(sum(c.delta), 0)::int as balance
from public.profiles p
left join public.coin_entries c on c.account = p.id
group by p.id;

-- --------------------------------------------------------------------- RLS

alter table public.profiles enable row level security;
alter table public.listings enable row level security;
alter table public.missions enable row level security;
alter table public.enrollments enable row level security;
alter table public.mission_days enable row level security;
alter table public.coin_entries enable row level security;

create policy "profiles are public" on public.profiles
    for select using (true);
create policy "own profile is editable" on public.profiles
    for update using (auth.uid() = id) with check (auth.uid() = id);

-- The Board is the point: listings are readable by everyone signed in.
create policy "listings are public" on public.listings
    for select using (true);
create policy "own listings are writable" on public.listings
    for insert with check (auth.uid() = owner);
create policy "own listings are editable" on public.listings
    for update using (auth.uid() = owner) with check (auth.uid() = owner);
create policy "own listings are removable" on public.listings
    for delete using (auth.uid() = owner);

-- Readable by all, created only through create_mission, which escrows first.
create policy "missions are public" on public.missions
    for select using (true);

create policy "own enrollments are visible" on public.enrollments
    for select using (
        auth.uid() = tester
        or auth.uid() in (
            select l.owner from public.listings l
            join public.missions m on m.listing_id = l.id
            where m.id = enrollments.mission_id
        )
    );

create policy "own evidence is visible" on public.mission_days
    for select using (
        auth.uid() in (
            select e.tester from public.enrollments e
            where e.id = mission_days.enrollment_id
        )
    );

-- Select only, deliberately. There is no insert, update or delete policy on
-- this table for any role, so the only way a coin moves is a function below.
create policy "own ledger is visible" on public.coin_entries
    for select using (auth.uid() = account);
