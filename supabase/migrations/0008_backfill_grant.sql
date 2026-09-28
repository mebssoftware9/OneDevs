-- Accounts that existed before the welcome grant did.
--
-- The trigger only fires for new sign-ups, so anyone already here has never
-- been granted anything and cannot fund a single test. One grant each, and
-- only to those with none, so running this twice changes nothing.
insert into public.coin_entries (account, delta, reason)
select p.id, 75, 'grant'
from public.profiles p
where not exists (
    select 1 from public.coin_entries c
    where c.account = p.id and c.reason = 'grant'
);
