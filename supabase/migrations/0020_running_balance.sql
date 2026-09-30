-- One number per account instead of a sum over its whole history.
--
-- Every balance check -- the Board deciding whether an app can still pay, a
-- test starting, a mission seat being taken, the wallet -- added up every
-- coin_entries row the account had ever had. The ledger only grows, so those
-- checks got slower every week at the same number of users, and the Board did
-- it once per listing on every read.
--
-- The ledger stays the truth. account_balances is its running total, kept by
-- a trigger in the same transaction as every write, so the two cannot drift;
-- balance_drift() is there to prove it.

create table if not exists public.account_balances (
    account uuid primary key references public.profiles (id) on delete cascade,
    balance int not null default 0
);

alter table public.account_balances enable row level security;

drop policy if exists "own balance is visible" on public.account_balances;
create policy "own balance is visible" on public.account_balances
    for select using (auth.uid() = account);

/** Keeps account_balances in step with every ledger write. */
create or replace function public.apply_coin_entry() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if tg_op in ('UPDATE', 'DELETE') then
        update public.account_balances
        set balance = balance - old.delta
        where account = old.account;
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        insert into public.account_balances (account, balance)
        values (new.account, new.delta)
        on conflict (account) do update
            set balance = public.account_balances.balance + excluded.balance;
    end if;
    return null;
end $$;

revoke all on function public.apply_coin_entry() from public, anon, authenticated;

-- Backfill under a lock, so no ledger write can land between the sum and the
-- trigger taking over. The whole migration is one transaction.
lock table public.coin_entries in share row exclusive mode;

insert into public.account_balances (account, balance)
select account, sum(delta)::int from public.coin_entries group by account
on conflict (account) do update set balance = excluded.balance;

drop trigger if exists coin_entries_running_balance on public.coin_entries;
create trigger coin_entries_running_balance
    after insert or update or delete on public.coin_entries
    for each row execute function public.apply_coin_entry();

-- ------------------------------------------------------------------ readers

create or replace function public.balance_of(p_account uuid) returns int
language sql stable security definer set search_path = '' as $$
    select coalesce(
        (select balance from public.account_balances where account = p_account),
        0
    );
$$;

-- The app's balance chip. Same columns as before, one row read instead of a sum.
create or replace view public.coin_balance with (security_invoker = true) as
select p.id as account, coalesce(b.balance, 0)::int as balance
from public.profiles p
left join public.account_balances b on b.account = p.id
where p.id = auth.uid();

grant select on public.coin_balance to authenticated;

-- The Board's funding rule, reading the running balance. Views run as their
-- owner here, so this sees every owner's balance while the table's own policy
-- still shows each person only their own.
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
);

grant select on public.board_listings to authenticated;

-- --------------------------------------------------------------- checking

/**
 * Accounts whose running balance disagrees with their ledger. Should always
 * be empty; run it from the SQL editor after the migration, or on a schedule.
 */
create or replace function public.balance_drift()
returns table (account uuid, cached int, ledger int)
language sql stable security definer set search_path = '' as $$
    select coalesce(b.account, c.account), coalesce(b.balance, 0), coalesce(c.total, 0)
    from public.account_balances b
    full join (
        select account, sum(delta)::int as total from public.coin_entries group by account
    ) c on c.account = b.account
    where coalesce(b.balance, 0) <> coalesce(c.total, 0);
$$;

revoke all on function public.balance_drift() from public, anon, authenticated;
