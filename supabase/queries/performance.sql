-- Paste into the Supabase SQL editor. Read-only.

-- 1. The slowest calls, by total time spent. platform_stats, home_board and
--    board_listings should sit far down this list after 0019, 0020 and 0021.
select
    left(query, 120) as query,
    calls,
    round(mean_exec_time::numeric, 1) as mean_ms,
    round(total_exec_time::numeric / 1000, 1) as total_s
from extensions.pg_stat_statements
order by total_exec_time desc
limit 15;

-- 2. The same numbers from zero: run this, use the app for a day, run (1).
-- select extensions.pg_stat_statements_reset();

-- 3. Running balances that disagree with the ledger. Must return no rows.
select * from public.balance_drift();

-- 4. How old the Board numbers are, and how many presence writes a day now
--    costs (one per active person per five minutes at most).
select refreshed_at, now() - refreshed_at as age from public.platform_cache;
select count(*) as active_24h from public.profiles where last_seen > now() - interval '24 hours';

-- 5. The scheduled recount (0021): last runs and whether they succeeded.
--    refreshed_at in (4) should never be more than a minute or two old.
select j.jobname, d.status, d.start_time, d.end_time - d.start_time as took
from cron.job_run_details d
join cron.job j using (jobid)
order by d.start_time desc
limit 10;
