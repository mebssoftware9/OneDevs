-- coin_balance was defined over every profile, so it returned a row per
-- account. Row-level security kept other people's entries out of the sum, but
-- their rows still came back reading zero -- a balance that looks like a fact
-- and is not one. A view called "balance" should answer for the caller and
-- nobody else.
create or replace view public.coin_balance with (security_invoker = true) as
select p.id as account, coalesce(sum(c.delta), 0)::int as balance
from public.profiles p
left join public.coin_entries c on c.account = p.id
where p.id = auth.uid()
group by p.id;

grant select on public.coin_balance to authenticated;
