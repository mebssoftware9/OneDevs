-- Somewhere for icons to live, and a live board built on real observations.

-- ------------------------------------------------------------------- icons
-- Public to read: an app icon on a public board is public. Writable only
-- inside a folder named after the owner. Path is icons/<user id>/<listing>.png
insert into storage.buckets (id, name, public)
values ('icons', 'icons', true)
on conflict (id) do nothing;

create policy "icons are readable by anyone"
    on storage.objects for select
    using (bucket_id = 'icons');

create policy "own icons are writable"
    on storage.objects for insert to authenticated
    with check (bucket_id = 'icons' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "own icons are replaceable"
    on storage.objects for update to authenticated
    using (bucket_id = 'icons' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "own icons are removable"
    on storage.objects for delete to authenticated
    using (bucket_id = 'icons' and (storage.foldername(name))[1] = auth.uid()::text);

-- ------------------------------------------------------------------- pulse
-- One row per hour, so the board's line is a record of what was actually true
-- rather than a shape chosen to look healthy. No scheduled job: the first
-- caller in any hour writes that hour's sample and everyone after conflicts
-- harmlessly, so the series exists exactly as long as anyone has been looking.
create table public.pulse (
    at timestamptz primary key,
    testers_active int not null,
    apps_in_testing int not null
);

alter table public.pulse enable row level security;
-- No policy and no grant. It is read through the function below, never
-- directly, so there is no shape for a client to write into it.

/**
 * What the top of the Board says, counted rather than invented.
 *
 * "Active" means a tester recorded a day of real testing in the last
 * twenty-four hours -- the same evidence a mission is paid on, not sessions or
 * installs or anything that inflates by opening an app.
 *
 * These will be small numbers at first. A small true number is worth more than
 * a large invented one: the first developer who counts the apps on the board
 * and finds four is the first who stops believing anything else on the screen.
 */
create or replace function public.platform_stats() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_active int;
    v_apps int;
    v_missions int;
begin
    select count(distinct e.tester) into v_active
    from public.mission_days d
    join public.enrollments e on e.id = d.enrollment_id
    where d.recorded_at > now() - interval '24 hours';

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
                from (
                    select * from public.pulse order by at desc limit 24
                ) p
            ),
            '[]'::jsonb
        )
    );
end $$;

revoke all on function public.platform_stats() from public, anon;
grant execute on function public.platform_stats() to authenticated;
