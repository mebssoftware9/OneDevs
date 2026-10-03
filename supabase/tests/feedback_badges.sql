-- Feedback reports and badges. Rolled back at the end.
--
-- D owns an app; T tests it on the Board and sends reports; S never tested it.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid, u.id, 'authenticated', 'authenticated',
       u.email, 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false
from (values
    ('0d000000-0000-0000-0000-00000000000d'::uuid, 'd@onedevs.test'),
    ('07000000-0000-0000-0000-000000000007'::uuid, 't@onedevs.test'),
    ('05000000-0000-0000-0000-000000000005'::uuid, 's@onedevs.test')
) u (id, email);

insert into public.listings (id, owner, package_name, title, category, channel) values
    ('f1000000-0000-0000-0000-000000000001', '0d000000-0000-0000-0000-00000000000d',
     'com.d.app', 'D App', 'Tools', 'testing');
insert into public.tests (listing_id, tester, seconds, device)
values ('f1000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000007', 60, 'device-t-1');

do $$
declare
    v_app uuid := 'f1000000-0000-0000-0000-000000000001';
    r jsonb;
    v_bug bigint;
    v_idea bigint;
    i int;
    b jsonb;
begin
    -- S has not tested it.
    perform set_config('request.jwt.claims', '{"sub":"05000000-0000-0000-0000-000000000005"}', true);
    r := public.feedback_submit(v_app, 'bug', 'The settings screen crashes when rotated.');
    if r->>'reason' <> 'not_tested' then raise exception 'FAIL untested: %', r; end if;

    -- D cannot report on their own app.
    perform set_config('request.jwt.claims', '{"sub":"0d000000-0000-0000-0000-00000000000d"}', true);
    r := public.feedback_submit(v_app, 'bug', 'The settings screen crashes when rotated.');
    if r->>'reason' <> 'own_app' then raise exception 'FAIL own: %', r; end if;

    -- T can.
    perform set_config('request.jwt.claims', '{"sub":"07000000-0000-0000-0000-000000000007"}', true);
    r := public.feedback_submit(v_app, 'bug', 'short');
    if r->>'reason' <> 'too_short' then raise exception 'FAIL short: %', r; end if;
    r := public.feedback_submit(v_app, 'bug', 'The settings screen crashes when the phone is rotated.');
    if (r->>'ok')::boolean is not true then raise exception 'FAIL bug: %', r; end if;
    v_bug := (r->>'id')::bigint;
    r := public.feedback_submit(v_app, 'suggestion', repeat('A dark mode would help at night. ', 10));
    v_idea := (r->>'id')::bigint;
    -- Five a day.
    for i in 1..3 loop
        perform public.feedback_submit(v_app, 'suggestion', 'Another thought about onboarding number ' || i);
    end loop;
    r := public.feedback_submit(v_app, 'suggestion', 'One report more than the daily limit allows.');
    if r->>'reason' <> 'slow_down' then raise exception 'FAIL limit: %', r; end if;

    -- T sees their own; T cannot review.
    if jsonb_array_length(public.feedback_for_listing(v_app)) <> 5 then raise exception 'FAIL tester view'; end if;
    r := public.feedback_review(v_bug, 'confirmed');
    if r->>'reason' <> 'not_yours' then raise exception 'FAIL tester review: %', r; end if;

    -- S sees nothing.
    perform set_config('request.jwt.claims', '{"sub":"05000000-0000-0000-0000-000000000005"}', true);
    if jsonb_array_length(public.feedback_for_listing(v_app)) <> 0 then raise exception 'FAIL stranger view'; end if;

    -- The Board card's numbers: one tester so far, and a developer who has
    -- tested nothing for anyone else yet.
    if coalesce((select testers from public.board('testing', 'device-s-0001', 50) where id = v_app), -1) <> 1
       or coalesce((select owner_tested from public.board('testing', 'device-s-0001', 50) where id = v_app), -1) <> 0 then
        raise exception 'FAIL board card counts';
    end if;

    -- D reviews.
    perform set_config('request.jwt.claims', '{"sub":"0d000000-0000-0000-0000-00000000000d"}', true);
    -- The owner's page counts the same tester the card does.
    if (public.listing_stats(v_app)->>'testers')::int <> 1 then raise exception 'FAIL listing_stats testers'; end if;
    r := public.feedback_for_listing(v_app);
    if jsonb_array_length(r) <> 5 or r->0->>'author' is null then raise exception 'FAIL owner view: %', r; end if;
    r := public.feedback_review(v_idea, 'confirmed');
    if r->>'reason' <> 'bad_status' then raise exception 'FAIL confirm a suggestion: %', r; end if;
    perform public.feedback_review(v_bug, 'confirmed');
    perform public.feedback_review(v_idea, 'accepted');

    -- T's badges: two accepted reports, one confirmed bug, one long accepted
    -- report, one developer helped; nothing from missions.
    perform set_config('request.jwt.claims', '{"sub":"07000000-0000-0000-0000-000000000007"}', true);
    r := public.my_badges();
    if jsonb_array_length(r) <> 18 then raise exception 'FAIL badge count %', jsonb_array_length(r); end if;
    select x into b from jsonb_array_elements(r) x where x->>'key' = 'useful';
    if (b->>'have')::int <> 2 or (b->>'earned')::boolean then raise exception 'FAIL useful: %', b; end if;
    select x into b from jsonb_array_elements(r) x where x->>'key' = 'bug_hunter';
    if (b->>'have')::int <> 1 then raise exception 'FAIL bug hunter: %', b; end if;
    select x into b from jsonb_array_elements(r) x where x->>'key' = 'detail';
    if (b->>'have')::int <> 1 then raise exception 'FAIL detail: %', b; end if;
    select x into b from jsonb_array_elements(r) x where x->>'key' = 'builder';
    if (b->>'have')::int <> 1 then raise exception 'FAIL builder: %', b; end if;
    select x into b from jsonb_array_elements(r) x where x->>'key' = 'contributor';
    if (b->>'have')::int <> 1 then raise exception 'FAIL contributor: %', b; end if;
    if exists (select 1 from jsonb_array_elements(r) x where (x->>'earned')::boolean) then
        raise exception 'FAIL something earned too early: %', r;
    end if;

    -- A genuine, Play-licensed phone earns the two trust badges.
    insert into public.verified_sessions (session_id, account, passed, verdict, expires_at)
    values (gen_random_uuid(), '07000000-0000-0000-0000-000000000007', true,
            '{"device":["MEETS_BASIC_INTEGRITY","MEETS_DEVICE_INTEGRITY"],"licence":"LICENSED"}', now() + interval '1 hour');
    r := public.my_badges();
    if not exists (select 1 from jsonb_array_elements(r) x where x->>'key' = 'device' and (x->>'earned')::boolean)
       or not exists (select 1 from jsonb_array_elements(r) x where x->>'key' = 'account' and (x->>'earned')::boolean) then
        raise exception 'FAIL trust badges: %', r;
    end if;

    raise notice 'feedback and badges: all checks passed';
end $$;

rollback;
