-- "Active" meant mission days only, so claiming a test made nobody active and
-- the board reported zero while people were testing on it. A test is testing.
create or replace function public.platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_active int;
    v_apps int;
    v_missions int;
begin
    select count(*) into v_active from (
        select e.tester as who
        from public.mission_days d
        join public.enrollments e on e.id = d.enrollment_id
        where d.recorded_at > now() - interval '24 hours'
        union
        select t.tester
        from public.tests t
        where t.claimed_at > now() - interval '24 hours'
    ) both_kinds;

    select count(*) into v_apps from public.listings where channel = 'testing';
    select count(*) into v_missions from public.missions where state = 'open';

    insert into public.pulse (at, testers_active, apps_in_testing)
    values (date_trunc('hour', now()), v_active, v_apps)
    on conflict (at) do nothing;

    return jsonb_build_object(
        'testers_active_24h', v_active,
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
