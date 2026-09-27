-- Postgres checks table privileges before it evaluates a row-level policy, so
-- a table with perfect policies and no grant is simply unreachable. The first
-- smoke test against the live API returned "permission denied for table
-- listings" -- the policies were never consulted at all.
--
-- Granting is therefore deliberate, not a formality:
--
--   anon gets nothing. OneDevs needs an account to do anything meaningful, and
--   a signed-out caller has no business reading the Board. Two layers say no.
--
--   authenticated gets only the verbs that a policy then narrows to its own
--   rows. Where the only legitimate write path is a function -- missions,
--   enrollments, evidence, coins -- there is no write grant at all, so even a
--   policy mistake later cannot open one.

grant usage on schema public to authenticated;

grant select, update on public.profiles to authenticated;
grant select, insert, update, delete on public.listings to authenticated;

-- Read-only by grant. create_mission and join_mission are the only ways in,
-- and they run as the database owner.
grant select on public.missions to authenticated;
grant select on public.enrollments to authenticated;
grant select on public.mission_days to authenticated;

-- The ledger and its balance. Select only, forever.
grant select on public.coin_entries to authenticated;
grant select on public.coin_balance to authenticated;
