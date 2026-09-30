-- The balance chip read "–" for everyone after 0020.
--
-- coin_balance runs as the person asking (security_invoker), and since 0020
-- it reads account_balances -- a table the signed-in role was never granted.
-- Every read was refused with "permission denied", the app treated that as
-- "could not be read", and phones that had the number in memory kept showing
-- it until the next launch, which is why it looked fine at first.
--
-- The row policy from 0020 still shows each person only their own balance;
-- this grants the privilege that policy was waiting on. Writes stay with the
-- trigger alone.

grant select on public.account_balances to authenticated;
revoke insert, update, delete, truncate on public.account_balances from anon, authenticated;
