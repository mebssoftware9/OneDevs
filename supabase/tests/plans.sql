-- Plans and the Lab: how many apps each plan keeps, what the caller is told,
-- and who may record a subscription. Rolled back at the end.
--
-- Claims made in one transaction share a timestamp, so the Lab's order falls
-- back to the package name: com.t.a1 was "chosen" before com.t.a2.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
) values
('00000000-0000-0000-0000-000000000000', 'cccccccc-0000-0000-0000-000000000003',
 'authenticated', 'authenticated', 'c@onedevs.test', 'x', now(), now(), now(),
 '{}'::jsonb, '{}'::jsonb, false),
('00000000-0000-0000-0000-000000000000', 'dddddddd-0000-0000-0000-000000000004',
 'authenticated', 'authenticated', 'd@onedevs.test', 'x', now(), now(), now(),
 '{}'::jsonb, '{}'::jsonb, false);

create temporary table said (step text primary key, reply jsonb) on commit drop;
grant all on said to authenticated;

-- ---- C, signed in, on Community
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"cccccccc-0000-0000-0000-000000000003","role":"authenticated"}', true);

insert into said values
    ('free_plan', public.my_plan()),
    ('free_a1', public.lab_claim_app('com.t.a1')),
    ('free_a2', public.lab_claim_app('com.t.a2')),
    ('free_a1_again', public.lab_claim_app('com.t.a1'));

do $$
begin
    perform public.subscription_update(
        'cccccccc-0000-0000-0000-000000000003', 'tok-self', 'pro', 'active', now() + interval '30 days');
    raise exception 'FAIL: a signed-in user recorded their own subscription';
exception when insufficient_privilege then
    null;
end $$;

-- ---- Premium, recorded as the purchase function would
reset role;
insert into said values ('premium_recorded', public.subscription_update(
    'cccccccc-0000-0000-0000-000000000003', 'tok-premium', 'premium', 'active', now() + interval '30 days'));

set local role authenticated;
insert into said values
    ('premium_plan', public.my_plan()),
    ('premium_a2', public.lab_claim_app('com.t.a2')),
    ('premium_a3', public.lab_claim_app('com.t.a3')),
    ('premium_a4', public.lab_claim_app('com.t.a4')),
    ('premium_a5', public.lab_claim_app('com.t.a5')),
    ('premium_a6', public.lab_claim_app('com.t.a6'));

-- ---- Pro on top
reset role;
insert into said values ('pro_recorded', public.subscription_update(
    'cccccccc-0000-0000-0000-000000000003', 'tok-pro', 'pro', 'active', now() + interval '30 days'));

set local role authenticated;
insert into said values
    ('pro_plan', public.my_plan()),
    ('pro_a6', public.lab_claim_app('com.t.a6'));

-- ---- Pro ends: back to Premium, which keeps the first five
reset role;
select public.subscription_update(
    'cccccccc-0000-0000-0000-000000000003', 'tok-pro', 'pro', 'expired', now());

set local role authenticated;
insert into said values
    ('after_pro_plan', public.my_plan()),
    ('after_pro_a6', public.lab_claim_app('com.t.a6')),
    ('after_pro_a1', public.lab_claim_app('com.t.a1'));

-- ---- the other paths
reset role;
insert into said values
    ('unknown_product', public.subscription_update(
        'cccccccc-0000-0000-0000-000000000003', 'tok-x', 'gold', 'active', now() + interval '30 days')),
    ('stolen_token', public.subscription_update(
        'dddddddd-0000-0000-0000-000000000004', 'tok-premium', 'premium', 'active', now() + interval '30 days'));

-- D has only a Ghostline month of lab_pro: any app in the Lab, but still Community.
insert into public.entitlements (account, product, source, expires_at, purchase_token)
values ('dddddddd-0000-0000-0000-000000000004', 'lab_pro', 'ghostline', now() + interval '30 days', 'tok-ghost');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"dddddddd-0000-0000-0000-000000000004","role":"authenticated"}', true);
insert into said values
    ('ghost_plan', public.my_plan()),
    ('ghost_a1', public.lab_claim_app('com.t.a1')),
    ('ghost_a2', public.lab_claim_app('com.t.a2'));

reset role;
do $$
declare
    r jsonb;
begin
    select reply into r from said where step = 'free_plan';
    if r->>'plan' is distinct from 'free' or (r->>'lab_limit')::int is distinct from 1 or r->'lab_apps' is distinct from '[]'::jsonb then
        raise exception 'FAIL free_plan: %', r;
    end if;
    select reply into r from said where step = 'free_a1';
    if (r->>'ok')::boolean is not true then raise exception 'FAIL free_a1: %', r; end if;
    select reply into r from said where step = 'free_a2';
    if r->>'reason' is distinct from 'upgrade' or r->>'lab_app' is distinct from 'com.t.a1' or (r->>'lab_limit')::int is distinct from 1 then
        raise exception 'FAIL free_a2: %', r;
    end if;
    select reply into r from said where step = 'free_a1_again';
    if (r->>'ok')::boolean is not true then raise exception 'FAIL free_a1_again: %', r; end if;

    select reply into r from said where step = 'premium_recorded';
    if r->>'plan' is distinct from 'premium' then raise exception 'FAIL premium_recorded: %', r; end if;
    select reply into r from said where step = 'premium_plan';
    if r->>'plan' is distinct from 'premium' or (r->>'lab_limit')::int is distinct from 5 or r->>'plan_until' is null then
        raise exception 'FAIL premium_plan: %', r;
    end if;
    if exists (
        select 1 from said
        where step in ('premium_a2', 'premium_a3', 'premium_a4', 'premium_a5')
          and (reply->>'ok')::boolean is not true
    ) then raise exception 'FAIL premium: a2..a5 should all fit'; end if;
    select reply into r from said where step = 'premium_a6';
    if r->>'reason' is distinct from 'upgrade' or jsonb_array_length(r->'lab_apps') is distinct from 5 then
        raise exception 'FAIL premium_a6: %', r;
    end if;

    select reply into r from said where step = 'pro_plan';
    if r->>'plan' is distinct from 'pro' or r->'lab_limit' is distinct from 'null'::jsonb then
        raise exception 'FAIL pro_plan: %', r;
    end if;
    select reply into r from said where step = 'pro_a6';
    if (r->>'ok')::boolean is not true then raise exception 'FAIL pro_a6: %', r; end if;

    select reply into r from said where step = 'after_pro_plan';
    if r->>'plan' is distinct from 'premium'
       or r->'lab_apps' is distinct from '["com.t.a1", "com.t.a2", "com.t.a3", "com.t.a4", "com.t.a5"]'::jsonb then
        raise exception 'FAIL after_pro_plan: %', r;
    end if;
    select reply into r from said where step = 'after_pro_a6';
    if r->>'reason' is distinct from 'upgrade' then raise exception 'FAIL after_pro_a6: %', r; end if;
    select reply into r from said where step = 'after_pro_a1';
    if (r->>'ok')::boolean is not true then raise exception 'FAIL after_pro_a1: %', r; end if;

    select reply into r from said where step = 'unknown_product';
    if r->>'reason' is distinct from 'unknown_product' then raise exception 'FAIL unknown_product: %', r; end if;
    select reply into r from said where step = 'stolen_token';
    if r->>'reason' is distinct from 'token_belongs_to_another_account' then raise exception 'FAIL stolen_token: %', r; end if;

    select reply into r from said where step = 'ghost_plan';
    if r->>'plan' is distinct from 'free' or r->'lab_limit' is distinct from 'null'::jsonb then
        raise exception 'FAIL ghost_plan: %', r;
    end if;
    if exists (
        select 1 from said where step in ('ghost_a1', 'ghost_a2') and (reply->>'ok')::boolean is not true
    ) then raise exception 'FAIL ghost: lab_pro should take any app'; end if;

    raise notice 'plans: all checks passed';
end $$;

rollback;
