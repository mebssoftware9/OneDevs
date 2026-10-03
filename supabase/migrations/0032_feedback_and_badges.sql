-- Feedback reports, and badges worked out from what people actually did.
--
-- Feedback
--   Anyone who has tested an app -- on the Board or in a mission -- can send
--   its developer a bug report or a suggestion. The developer reads them on
--   the app's page and marks each one: accepted (useful), confirmed (a real
--   bug) or dismissed. Five reports a day per app per tester, so the inbox
--   stays readable.
--
-- Badges
--   The Badges tab listed eighteen badges and computed none of them. Each is
--   now a rule over records the server already keeps; my_badges() answers
--   with every badge, whether it is earned, and how far along the caller is.

-- ---------------------------------------------------------------- feedback

create table if not exists public.feedback_reports (
    id bigint generated always as identity primary key,
    listing_id uuid not null references public.listings (id) on delete cascade,
    author uuid not null references public.profiles (id) on delete cascade,
    kind text not null check (kind in ('bug', 'suggestion')),
    body text not null check (char_length(body) between 20 and 2000),
    status text not null default 'new'
        check (status in ('new', 'accepted', 'confirmed', 'dismissed')),
    created_at timestamptz not null default now(),
    reviewed_at timestamptz
);

create index if not exists feedback_reports_listing on public.feedback_reports (listing_id, id desc);
create index if not exists feedback_reports_author on public.feedback_reports (author, created_at desc);

alter table public.feedback_reports enable row level security;
revoke all on public.feedback_reports from anon, authenticated;

/** Whether an account has used an app: a paid Board test or a mission check-in. */
create or replace function public.has_tested(p_account uuid, p_listing uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.tests where listing_id = p_listing and tester = p_account)
        or exists (select 1 from public.mission_checkins where listing_id = p_listing and member = p_account);
$$;

/**
 * Sends a report about an app the caller has tested.
 * { ok: true, id } or { ok: false, reason }: signed_out, missing, own_app,
 * not_tested, bad_kind, too_short, too_long, slow_down.
 */
create or replace function public.feedback_submit(p_listing uuid, p_kind text, p_body text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_body text := btrim(coalesce(p_body, ''));
    v_id bigint;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    select owner into v_owner from public.listings where id = p_listing;
    if v_owner is null then
        return jsonb_build_object('ok', false, 'reason', 'missing');
    end if;
    if v_owner = auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'own_app');
    end if;
    if not public.has_tested(auth.uid(), p_listing) then
        return jsonb_build_object('ok', false, 'reason', 'not_tested');
    end if;
    if p_kind is null or p_kind not in ('bug', 'suggestion') then
        return jsonb_build_object('ok', false, 'reason', 'bad_kind');
    end if;
    if char_length(v_body) < 20 then
        return jsonb_build_object('ok', false, 'reason', 'too_short');
    end if;
    if char_length(v_body) > 2000 then
        return jsonb_build_object('ok', false, 'reason', 'too_long');
    end if;
    if (select count(*) from public.feedback_reports
        where author = auth.uid() and listing_id = p_listing
          and created_at > now() - interval '1 day') >= 5 then
        return jsonb_build_object('ok', false, 'reason', 'slow_down');
    end if;

    insert into public.feedback_reports (listing_id, author, kind, body)
    values (p_listing, auth.uid(), p_kind, v_body)
    returning id into v_id;
    return jsonb_build_object('ok', true, 'id', v_id);
end $$;

/**
 * The reports on one app, newest first. The developer sees all of them, with
 * who sent each; a tester sees only their own. Anyone else sees none.
 */
create or replace function public.feedback_for_listing(p_listing uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'kind', f.kind,
        'body', f.body,
        'status', f.status,
        'at', f.created_at,
        'mine', f.author = auth.uid(),
        'author', coalesce(nullif(p.display_name, ''), p.handle)
    ) order by f.id desc), '[]'::jsonb)
    from public.feedback_reports f
    join public.listings l on l.id = f.listing_id
    join public.profiles p on p.id = f.author
    where f.listing_id = p_listing
      and (l.owner = auth.uid() or f.author = auth.uid());
$$;

/**
 * The developer marks a report: accepted, confirmed (bugs only) or dismissed.
 * { ok: true } or { ok: false, reason }: signed_out, missing, not_yours,
 * bad_status.
 */
create or replace function public.feedback_review(p_report bigint, p_status text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_report public.feedback_reports%rowtype;
    v_owner uuid;
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    select * into v_report from public.feedback_reports where id = p_report for update;
    if v_report.id is null then
        return jsonb_build_object('ok', false, 'reason', 'missing');
    end if;
    select owner into v_owner from public.listings where id = v_report.listing_id;
    if v_owner is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'not_yours');
    end if;
    if p_status is null or p_status not in ('accepted', 'confirmed', 'dismissed')
       or (p_status = 'confirmed' and v_report.kind <> 'bug') then
        return jsonb_build_object('ok', false, 'reason', 'bad_status');
    end if;
    update public.feedback_reports set status = p_status, reviewed_at = now() where id = p_report;
    return jsonb_build_object('ok', true);
end $$;

-- ------------------------------------------------------------------ badges

/** The longest run of missions in a row in which an account did its part. */
create or replace function public.best_mission_streak(p_account uuid) returns int
language plpgsql stable security definer set search_path = '' as $$
declare
    r record;
    v_run int := 0;
    v_best int := 0;
begin
    for r in
        select res.did_part from public.mission_results res
        join public.mission_groups g on g.id = res.mission_id
        where res.member = p_account
        order by g.completed_at, g.id
    loop
        v_run := case when r.did_part then v_run + 1 else 0 end;
        v_best := greatest(v_best, v_run);
    end loop;
    return v_best;
end $$;

/**
 * Every badge, for the caller: [{ key, earned, have, need }]. have and need
 * are the progress toward it, in whatever the badge counts.
 */
create or replace function public.my_badges() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
    me uuid := auth.uid();
    v_done int;
    v_missions int;
    v_streak int;
    v_useful int;
    v_bugs int;
    v_detail int;
    v_helped int;
    v_days int;
    v_seats int;
    v_seats_done int;
    v_cycles int;
    v_device boolean;
    v_licensed boolean;
    v_has_app boolean;
    v_coins int;
    v_ready int;
    v_badges jsonb := '[]'::jsonb;
begin
    if me is null then return '[]'::jsonb; end if;

    select count(*) filter (where did_part), count(*) into v_done, v_missions
    from public.mission_results where member = me;
    v_streak := public.best_mission_streak(me);

    select count(*) filter (where status in ('accepted', 'confirmed')),
           count(*) filter (where status = 'confirmed' and kind = 'bug'),
           count(*) filter (where status in ('accepted', 'confirmed') and char_length(body) >= 280)
    into v_useful, v_bugs, v_detail
    from public.feedback_reports where author = me;

    -- Developers helped: the owners of every other app this account used.
    select count(distinct l.owner) into v_helped
    from public.listings l
    where l.owner <> me
      and (exists (select 1 from public.tests t where t.listing_id = l.id and t.tester = me)
           or exists (select 1 from public.mission_checkins c where c.listing_id = l.id and c.member = me));

    -- Days with any testing in the last thirty.
    select count(*) into v_days from (
        select c.day from public.mission_checkins c
        where c.member = me and c.day > (now() at time zone 'utc')::date - 30
        union
        select (t.claimed_at at time zone 'utc')::date from public.tests t
        where t.tester = me and t.claimed_at > now() - interval '30 days'
    ) d;

    select count(*), count(*) filter (where g.state = 'completed')
    into v_seats, v_seats_done
    from public.mission_seats s join public.mission_groups g on g.id = s.mission_id
    where s.member = me;

    select count(*) into v_cycles from public.ghostline_runs
    where account = me and kind = 'cycle' and state = 'completed';

    select coalesce(bool_or(v.verdict->'device' ? 'MEETS_DEVICE_INTEGRITY'), false),
           coalesce(bool_or(v.verdict->>'licence' = 'LICENSED'), false)
    into v_device, v_licensed
    from public.verified_sessions v where v.account = me;

    v_has_app := exists (select 1 from public.listings where owner = me and channel = 'testing');
    v_coins := public.available_of(me);
    v_ready := (case when v_has_app then 1 else 0 end)
             + (case when v_coins >= 100 then 1 else 0 end)
             + (case when v_device then 1 else 0 end);

    select jsonb_agg(jsonb_build_object('key', b.key, 'earned', b.earned, 'have', b.have, 'need', b.need))
    into v_badges
    from (values
        ('first_test', v_done >= 1, least(v_done, 1), 1),
        ('tester', v_done >= 5, least(v_done, 5), 5),
        ('dedicated', v_done >= 10, least(v_done, 10), 10),
        ('veteran', v_done >= 25, least(v_done, 25), 25),
        ('streak', v_streak >= 5, least(v_streak, 5), 5),
        ('useful', v_useful >= 5, least(v_useful, 5), 5),
        ('bug_hunter', v_bugs >= 10, least(v_bugs, 10), 10),
        ('detail', v_detail >= 3, least(v_detail, 3), 3),
        ('builder', v_helped >= 10, least(v_helped, 10), 10),
        ('helper', v_helped >= 25, least(v_helped, 25), 25),
        ('contributor', v_days >= 20, least(v_days, 20), 20),
        ('creator', v_seats >= 1, least(v_seats, 1), 1),
        ('campaign', v_cycles >= 1, least(v_cycles, 1), 1),
        ('trusted_creator', v_seats_done >= 5, least(v_seats_done, 5), 5),
        ('device', v_device, case when v_device then 1 else 0 end, 1),
        ('account', v_licensed, case when v_licensed then 1 else 0 end, 1),
        ('reliable', v_missions >= 3 and v_done * 5 >= v_missions * 4, least(v_done, 3), 3),
        ('eligible', v_ready = 3, v_ready, 3)
    ) as b (key, earned, have, need);

    return coalesce(v_badges, '[]'::jsonb);
end $$;

-- ------------------------------------------------------------------ grants

revoke all on function public.has_tested(uuid, uuid) from public, anon, authenticated;
revoke all on function public.best_mission_streak(uuid) from public, anon, authenticated;
revoke all on function public.feedback_submit(uuid, text, text) from public, anon;
revoke all on function public.feedback_for_listing(uuid) from public, anon;
revoke all on function public.feedback_review(bigint, text) from public, anon;
revoke all on function public.my_badges() from public, anon;
grant execute on function public.feedback_submit(uuid, text, text) to authenticated;
grant execute on function public.feedback_for_listing(uuid) to authenticated;
grant execute on function public.feedback_review(bigint, text) to authenticated;
grant execute on function public.my_badges() to authenticated;
