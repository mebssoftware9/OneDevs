-- The Academy: evidence, plans, the capstone and credentials. Rolled back at the end.
--
-- L learns on Community, then on Premium. Fourteen other accounts test L's app.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid, u.id, 'authenticated', 'authenticated',
       u.email, 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false
from (
    select 'a1000000-0000-0000-0000-000000000001'::uuid as id, 'l@onedevs.test' as email
    union all
    select ('a2' || lpad(n::text, 2, '0') || '0000-0000-0000-0000-000000000000')::uuid, 't' || n || '@onedevs.test'
    from generate_series(1, 14) n
) u;

update public.profiles set display_name = 'Lena' where id = 'a1000000-0000-0000-0000-000000000001';

insert into public.listings (id, owner, package_name, title, category, channel) values
    ('a3000000-0000-0000-0000-000000000001', 'a1000000-0000-0000-0000-000000000001',
     'com.lena.ledger', 'Pocket Ledger', 'Finance', 'testing');

do $$
declare
    v_l uuid := 'a1000000-0000-0000-0000-000000000001';
    r jsonb;
    v_token text;
    v_t0 timestamptz := now() - interval '3 hours';
begin
    perform set_config('request.jwt.claims', '{"sub":"a1000000-0000-0000-0000-000000000001"}', true);

    -- ---- the plan decides which competencies take evidence
    r := public.academy_record(jsonb_build_array(
        jsonb_build_object('id', 'b0000000-0000-0000-0000-000000000001', 'pathway', 'ship-to-play',
            'competency', 'closed-testing', 'item', 'ct-rule', 'kind', 'concept', 'variant', 0,
            'correct', true, 'hinted', false, 'version', 1, 'at', v_t0),
        jsonb_build_object('id', 'b0000000-0000-0000-0000-000000000002', 'pathway', 'ship-to-play',
            'competency', 'signing', 'item', 'sg-keys', 'kind', 'concept', 'variant', 0,
            'correct', true, 'hinted', false, 'version', 1, 'at', v_t0),
        jsonb_build_object('id', 'b0000000-0000-0000-0000-000000000003', 'pathway', 'ship-to-play',
            'competency', 'no-such-thing', 'item', 'x', 'kind', 'concept', 'variant', 0,
            'correct', true, 'hinted', false, 'version', 1, 'at', v_t0),
        jsonb_build_object('id', 'not-a-uuid', 'pathway', 'ship-to-play',
            'competency', 'closed-testing', 'item', 'ct-rule', 'kind', 'concept',
            'correct', true, 'version', 1, 'at', v_t0),
        jsonb_build_object('id', 'b0000000-0000-0000-0000-000000000005', 'pathway', 'ship-to-play',
            'competency', 'closed-testing', 'item', 'ct-rule', 'kind', 'concept',
            'correct', true, 'version', 1, 'at', now() + interval '3 days')
    ));
    if r <> '{"ok": true, "saved": 1, "locked": 1, "rejected": 3}'::jsonb then
        raise exception 'FAIL community batch: %', r;
    end if;

    -- A retried send is the same row.
    r := public.academy_record(jsonb_build_array(
        jsonb_build_object('id', 'b0000000-0000-0000-0000-000000000001', 'pathway', 'ship-to-play',
            'competency', 'closed-testing', 'item', 'ct-rule', 'kind', 'concept', 'variant', 0,
            'correct', true, 'hinted', false, 'version', 1, 'at', v_t0)));
    if (r->>'saved')::int <> 1
       or (select count(*) from public.academy_evidence where account = v_l) <> 1 then
        raise exception 'FAIL retry: %', r;
    end if;

    if public.academy_record('{"not":"an array"}'::jsonb)->>'reason' <> 'bad_batch' then
        raise exception 'FAIL bad batch';
    end if;

    -- ---- foundations: not yet, then earned
    r := public.academy_claim('foundations');
    if r->>'reason' <> 'not_yet' or not (r->'missing' ? 'exported-components') then
        raise exception 'FAIL foundations early: %', r;
    end if;
    if public.academy_claim('nonsense')->>'reason' <> 'unknown' then
        raise exception 'FAIL unknown key';
    end if;

    -- Every question of the three free competencies, right and unhinted --
    -- except one answered wrong first and then right three minutes later.
    perform public.academy_record((
        select jsonb_agg(jsonb_build_object(
            'id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', q.competency,
            'item', q.item, 'kind', q.kind, 'variant', 0, 'correct', true, 'hinted', false,
            'version', 1, 'at', v_t0 + interval '1 minute'))
        from (values
            ('closed-testing', 'ct-count', 'predict'), ('closed-testing', 'ct-plan', 'debug'),
            ('closed-testing', 'ct-transfer', 'transfer'),
            ('exported-components', 'ec-rule', 'concept'), ('exported-components', 'ec-reach', 'predict'),
            ('exported-components', 'ec-merge', 'debug'), ('exported-components', 'ec-transfer', 'transfer'),
            ('permissions', 'pm-kinds', 'concept'), ('permissions', 'pm-predict', 'predict'),
            ('permissions', 'pm-restricted', 'debug')
        ) as q (competency, item, kind)
    ));
    perform public.academy_record(jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'permissions',
            'item', 'pm-transfer', 'kind', 'transfer', 'variant', 0, 'correct', false, 'hinted', false,
            'version', 1, 'at', v_t0 + interval '2 minutes'),
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'permissions',
            'item', 'pm-transfer', 'kind', 'transfer', 'variant', 1, 'correct', true, 'hinted', false,
            'version', 1, 'at', v_t0 + interval '5 minutes')));

    if public.academy_verified(v_l, 'ship-to-play') <> array['closed-testing', 'exported-components'] then
        raise exception 'FAIL right straight after wrong is not secure: %', public.academy_verified(v_l, 'ship-to-play');
    end if;

    -- A hint does not make it secure either.
    perform public.academy_record(jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'permissions',
            'item', 'pm-transfer', 'kind', 'transfer', 'variant', 0, 'correct', true, 'hinted', true,
            'version', 1, 'at', v_t0 + interval '30 minutes')));
    if 'permissions' = any (public.academy_verified(v_l, 'ship-to-play')) then
        raise exception 'FAIL hinted answer counted';
    end if;

    -- Coming back to it later, on its own, does.
    perform public.academy_record(jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'permissions',
            'item', 'pm-transfer', 'kind', 'transfer', 'variant', 1, 'correct', true, 'hinted', false,
            'version', 1, 'at', v_t0 + interval '40 minutes')));
    if public.academy_verified(v_l, 'ship-to-play') <> array['closed-testing', 'exported-components', 'permissions'] then
        raise exception 'FAIL delayed reassessment: %', public.academy_verified(v_l, 'ship-to-play');
    end if;

    r := public.academy_claim('foundations');
    if (r->>'ok')::boolean is not true or length(r->'credential'->>'token') <> 32 then
        raise exception 'FAIL foundations: %', r;
    end if;
    v_token := r->'credential'->>'token';
    if public.academy_claim('foundations')->'credential'->>'token' <> v_token then
        raise exception 'FAIL claim twice gives a second credential';
    end if;
    if jsonb_array_length(public.my_credentials()) <> 1 then
        raise exception 'FAIL my_credentials';
    end if;
    if jsonb_array_length(public.academy_mine('ship-to-play')) <> 15 then
        raise exception 'FAIL academy_mine: %', jsonb_array_length(public.academy_mine('ship-to-play'));
    end if;

    -- ---- Premium opens the rest; a gate needs an app the Lab claimed
    insert into public.entitlements (account, product, source, expires_at, purchase_token)
    values (v_l, 'premium', 'subscription', now() + interval '30 days', 'tok-academy');

    r := public.academy_record(jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'target-sdk',
            'item', 'gate', 'kind', 'artifact', 'correct', true, 'version', 1,
            'package', 'com.lena.ledger', 'at', now()),
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'target-sdk',
            'item', 'ts-roles', 'kind', 'artifact', 'correct', true, 'version', 1,
            'package', 'com.lena.ledger', 'at', now())));
    if r->>'rejected' <> '2' then raise exception 'FAIL gate without a Lab app: %', r; end if;

    insert into public.lab_apps (account, package_name) values (v_l, 'com.lena.ledger');
    perform public.academy_record((
        select jsonb_agg(jsonb_build_object(
            'id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'target-sdk',
            'item', q.item, 'kind', q.kind, 'variant', 0, 'correct', true, 'hinted', false,
            'version', 1, 'at', now()))
        from (values ('ts-roles', 'concept'), ('ts-predict', 'predict'),
                     ('ts-config', 'debug'), ('ts-transfer', 'transfer')) as q (item, kind)
    ));
    if 'target-sdk' = any (public.academy_verified(v_l, 'ship-to-play')) then
        raise exception 'FAIL target-sdk verified without its gate';
    end if;
    r := public.academy_record(jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid(), 'pathway', 'ship-to-play', 'competency', 'target-sdk',
            'item', 'gate', 'kind', 'artifact', 'correct', true, 'version', 1,
            'package', 'com.lena.ledger', 'at', now())));
    if r->>'saved' <> '1' or not ('target-sdk' = any (public.academy_verified(v_l, 'ship-to-play'))) then
        raise exception 'FAIL gate: %', r;
    end if;

    -- ---- the capstone comes from the server's own records
    insert into public.tests (listing_id, tester, seconds, device, claimed_at)
    select 'a3000000-0000-0000-0000-000000000001',
           ('a2' || lpad(n::text, 2, '0') || '0000-0000-0000-0000-000000000000')::uuid,
           60, 'device-' || n, now() - make_interval(days => n)
    from generate_series(1, 12) n;
    r := public.academy_capstone();
    if (r->>'testers')::int <> 12 or (r->>'days')::int <> 12 or (r->>'met')::boolean then
        raise exception 'FAIL capstone short on days: %', r;
    end if;
    r := public.academy_claim('capstone');
    if r->>'reason' <> 'not_yet' or not (r->'missing' ? 'capstone') then
        raise exception 'FAIL capstone early: %', r;
    end if;

    insert into public.tests (listing_id, tester, seconds, device, claimed_at)
    select 'a3000000-0000-0000-0000-000000000001',
           ('a2' || lpad(n::text, 2, '0') || '0000-0000-0000-0000-000000000000')::uuid,
           60, 'device-' || n, now() - make_interval(days => n)
    from generate_series(13, 14) n;
    r := public.academy_capstone();
    if (r->>'met')::boolean is not true or r->>'title' <> 'Pocket Ledger' then
        raise exception 'FAIL capstone met: %', r;
    end if;
    r := public.academy_claim('capstone');
    if (r->>'ok')::boolean is not true or (r->'credential'->'scope'->>'testers')::int <> 14 then
        raise exception 'FAIL capstone credential: %', r;
    end if;

    -- The whole pathway still wants every competency.
    r := public.academy_claim('ship-to-play');
    if r->>'reason' <> 'not_yet' or r->'missing' ? 'capstone' or not (r->'missing' ? 'data-safety') then
        raise exception 'FAIL pathway: %', r;
    end if;

    -- ---- verification, as anyone
    r := public.verify_credential(v_token);
    if r->>'holder' <> 'Lena' or (r->>'valid')::boolean is not true or r->>'key' <> 'foundations' then
        raise exception 'FAIL verify: %', r;
    end if;
    update public.academy_credentials set revoked_at = now(), revoked_reason = 'test' where token = v_token;
    if (public.verify_credential(v_token)->>'valid')::boolean then
        raise exception 'FAIL revoked still valid';
    end if;
    if public.verify_credential('nope') is not null
       or public.verify_credential('00000000000000000000000000000000') is not null then
        raise exception 'FAIL unknown token';
    end if;

    -- Signed out: nothing to record, nothing to claim.
    perform set_config('request.jwt.claims', '', true);
    if public.academy_record('[]'::jsonb)->>'reason' <> 'signed_out'
       or public.academy_claim('foundations')->>'reason' <> 'signed_out' then
        raise exception 'FAIL signed out';
    end if;
end $$;

-- ---- grants, as the roles the API uses
set local role anon;
do $$
begin
    begin
        perform public.academy_record('[]'::jsonb);
        raise exception 'FAIL anon may record';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.academy_verified('a1000000-0000-0000-0000-000000000001', 'ship-to-play');
        raise exception 'FAIL anon may read verification internals';
    exception when insufficient_privilege then null;
    end;
    -- The one thing anon may do.
    perform public.verify_credential('00000000000000000000000000000000');
    begin
        perform count(*) from public.academy_evidence;
        raise exception 'FAIL anon reads evidence';
    exception when insufficient_privilege then null;
    end;
end $$;
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a2010000-0000-0000-0000-000000000000","role":"authenticated"}', true);
do $$
begin
    begin
        perform count(*) from public.academy_credentials;
        raise exception 'FAIL credentials table readable';
    exception when insufficient_privilege then null;
    end;
    -- Someone else's capstone and credentials are not theirs to see.
    if (public.academy_capstone()->>'listing') is not null then
        raise exception 'FAIL capstone of another account';
    end if;
    if jsonb_array_length(public.my_credentials()) <> 0 then
        raise exception 'FAIL my_credentials leaks';
    end if;
end $$;
reset role;

select 'academy: all checks passed' as result;
rollback;
