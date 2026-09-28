-- "Live now" means developers using OneDevs, not developers in a mission.
--
-- Missions are one product on this platform. Counting mission days as the
-- measure of life on the platform made the board report zero while people were
-- listing apps and testing them, which is the opposite of what it is for.

alter table public.profiles
    add column last_seen timestamptz not null default now();

create index profiles_last_seen_idx on public.profiles (last_seen desc);

/**
 * The numbers at the top of the Board.
 *
 * Marks the caller as seen, then counts. The Board asks for this whenever it
 * opens, so presence is recorded by the act of being here rather than by a
 * separate heartbeat -- and anyone counted is someone who really had the app
 * open, not someone with an account.
 */
create or replace function public.platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_live int;
    v_day int;
    v_apps int;
    v_missions int;
begin
    update public.profiles set last_seen = now() where id = auth.uid();

    select count(*) into v_live from public.profiles
        where last_seen > now() - interval '15 minutes';
    select count(*) into v_day from public.profiles
        where last_seen > now() - interval '24 hours';
    select count(*) into v_apps from public.listings where channel = 'testing';
    select count(*) into v_missions from public.missions where state = 'open';

    insert into public.pulse (at, testers_active, apps_in_testing)
    values (date_trunc('hour', now()), v_live, v_apps)
    on conflict (at) do nothing;

    return jsonb_build_object(
        'live_now', v_live,
        'active_24h', v_day,
        'apps_in_testing', v_apps,
        'open_missions', v_missions,
        'pulse', coalesce(
            (
                select jsonb_agg(jsonb_build_object('at', p.at, 'testers', p.testers_active)
                                 order by p.at)
                from (select * from public.pulse order by at desc limit 24) p
            ),
            '[]'::jsonb
        )
    );
end $$;
