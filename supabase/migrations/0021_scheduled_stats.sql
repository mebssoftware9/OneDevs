-- The Board numbers are counted on a schedule, never in someone's request.
--
-- Since 0019 the first person to open the Board after the numbers were a
-- minute old paid for the recount inside their own request, and an hour in
-- which nobody opened the Board left a gap in the pulse graph. Now pg_cron
-- recounts every minute: every request only reads one cached row, and the
-- pulse gets its point every hour whether anyone is looking or not.
--
-- platform_stats() still recounts itself if the numbers are more than five
-- minutes old, so the Board keeps working if the job ever stops.

create extension if not exists pg_cron with schema pg_catalog;
grant usage on schema cron to postgres;

-- ------------------------------------------------------------------ reader

/** The numbers at the top of the Board: one row read, kept fresh by cron. */
create or replace function public.platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_row public.platform_cache%rowtype;
begin
    perform public.touch_presence();
    select * into v_row from public.platform_cache where id;
    -- Only when the job has not run for five minutes. The lock still makes
    -- that one recount, however many people arrive at once.
    if v_row.id is null
       or (v_row.refreshed_at < now() - interval '5 minutes'
           and pg_try_advisory_xact_lock(hashtext('onedevs.platform_stats'))) then
        return public.refresh_platform_stats();
    end if;
    return v_row.stats;
end $$;

revoke all on function public.platform_stats() from public, anon;
grant execute on function public.platform_stats() to authenticated;

-- -------------------------------------------------------------------- jobs

-- Re-runnable: replace the jobs rather than adding a second copy.
do $$
begin
    perform cron.unschedule(jobid) from cron.job
    where jobname in ('onedevs-platform-stats', 'onedevs-cron-history');

    perform cron.schedule(
        'onedevs-platform-stats',
        '* * * * *',
        'select public.refresh_platform_stats()'
    );

    -- A job every minute writes 1,440 history rows a day. A week is plenty
    -- to see whether it is running.
    perform cron.schedule(
        'onedevs-cron-history',
        '17 3 * * *',
        $job$delete from cron.job_run_details where end_time < now() - interval '7 days'$job$
    );
end $$;

-- ------------------------------------------------------------------ index

-- test_sessions_owner_open_expiry (0019) starts with owner too and answers
-- every query this one did. Two indexes on the same rows only doubled the
-- cost of every test starting and finishing.
drop index if exists public.test_sessions_owner_open;
