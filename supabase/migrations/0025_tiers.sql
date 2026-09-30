-- Community, Premium and Pro.
--
-- Premium and Pro are monthly Play subscriptions, one product each. An
-- account's plan is the best one it holds an unexpired entitlement for, and
-- the Lab keeps as many apps as that plan allows: Community 1, Premium 5,
-- Pro any number. A Lab that moves down keeps the first apps it chose, up to
-- the new limit; nothing is deleted, so moving back up restores the rest.
--
-- lab_pro (the old Lab subscription, and the month that came with each
-- Ghostline run) still opens the Lab to any app until those products are
-- retired. It is not a plan: it never makes an account Premium or Pro.

-- ------------------------------------------------------------ entitlements

alter table public.entitlements drop constraint if exists entitlements_product_check;
alter table public.entitlements add constraint entitlements_product_check
    check (product in ('lab_pro', 'premium', 'pro'));

-- ------------------------------------------------------------------- plans

/** The plan an account is on: 'pro', 'premium' or 'free'. */
create or replace function public.plan_of(p_account uuid) returns text
language sql stable security definer set search_path = '' as $$
    select case
        when exists (
            select 1 from public.entitlements
            where account = p_account and product = 'pro' and expires_at > now()
        ) then 'pro'
        when exists (
            select 1 from public.entitlements
            where account = p_account and product = 'premium' and expires_at > now()
        ) then 'premium'
        else 'free'
    end;
$$;

/** How many apps an account's Lab keeps; null when there is no limit. */
create or replace function public.lab_limit(p_account uuid) returns int
language sql stable security definer set search_path = '' as $$
    select case
        when exists (
            select 1 from public.entitlements
            where account = p_account and product in ('pro', 'lab_pro') and expires_at > now()
        ) then null
        when public.plan_of(p_account) = 'premium' then 5
        else 1
    end;
$$;

/** The apps an account's Lab keeps: the first it chose, up to its limit. */
create or replace function public.lab_kept(p_account uuid) returns text[]
language sql stable security definer set search_path = '' as $$
    select coalesce(array_agg(k.package_name order by k.chosen_at, k.package_name), '{}')
    from (
        select a.package_name, a.chosen_at from public.lab_apps a
        where a.account = p_account
        order by a.chosen_at, a.package_name
        limit public.lab_limit(p_account)
    ) k;
$$;

revoke all on function public.plan_of(uuid) from public, anon, authenticated;
revoke all on function public.lab_limit(uuid) from public, anon, authenticated;
revoke all on function public.lab_kept(uuid) from public, anon, authenticated;

/**
 * The caller's plan and Lab.
 *
 * plan_until is when the plan's current paid month ends. pro_until and
 * pro_source still describe the latest entitlement of any kind, and lab_app
 * the first app the Lab chose, for builds that read them.
 */
create or replace function public.my_plan() returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'plan', p.name,
        'plan_until', (
            select max(e.expires_at) from public.entitlements e
            where e.account = auth.uid() and e.product = p.name and e.expires_at > now()
        ),
        'pro_until', (
            select max(e.expires_at) from public.entitlements e
            where e.account = auth.uid() and e.expires_at > now()
        ),
        'pro_source', (
            select e.source from public.entitlements e
            where e.account = auth.uid() and e.expires_at > now()
            order by e.expires_at desc limit 1
        ),
        'lab_app', (
            select a.package_name from public.lab_apps a
            where a.account = auth.uid() order by a.chosen_at, a.package_name limit 1
        ),
        'lab_apps', to_jsonb(public.lab_kept(auth.uid())),
        'lab_limit', public.lab_limit(auth.uid())
    )
    from (select public.plan_of(auth.uid()) as name) p;
$$;

revoke all on function public.my_plan() from public, anon;
grant execute on function public.my_plan() to authenticated;

/**
 * Claims an app for the caller's Lab.
 *
 * { ok: true } when the Lab may analyse it. { ok: false, reason: 'upgrade',
 * lab_app, lab_apps, lab_limit } when the Lab is full: lab_apps are the apps
 * it keeps, lab_app the first of them.
 *
 * One claim at a time per account, so two analyses started together cannot
 * both take the last place.
 */
create or replace function public.lab_claim_app(p_package text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_limit int;
    v_kept text[];
begin
    if auth.uid() is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if p_package is null
       or p_package !~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$' then
        return jsonb_build_object('ok', false, 'reason', 'bad_package');
    end if;

    perform pg_advisory_xact_lock(hashtext('lab_claim:' || auth.uid()::text));
    v_limit := public.lab_limit(auth.uid());
    v_kept := public.lab_kept(auth.uid());

    if v_limit is null or p_package = any(v_kept) or cardinality(v_kept) < v_limit then
        insert into public.lab_apps (account, package_name)
        values (auth.uid(), p_package)
        on conflict do nothing;
        return jsonb_build_object('ok', true);
    end if;
    return jsonb_build_object(
        'ok', false, 'reason', 'upgrade',
        'lab_app', v_kept[1], 'lab_apps', to_jsonb(v_kept), 'lab_limit', v_limit
    );
end $$;

revoke all on function public.lab_claim_app(text) from public, anon;
grant execute on function public.lab_claim_app(text) to authenticated;

-- ----------------------------------------------------------- subscriptions

/**
 * Records a verified Play subscription and what it entitles. Service role
 * only. The product is the plan: 'premium', 'pro', or the old 'lab_pro'. The
 * expiry is Google's; a cancelled subscription keeps running to it.
 */
create or replace function public.subscription_update(
    p_account uuid,
    p_token text,
    p_product text,
    p_state text,
    p_expires timestamptz,
    p_order text default null,
    p_raw jsonb default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_until timestamptz;
begin
    if p_product is null or p_product not in ('lab_pro', 'premium', 'pro') then
        return jsonb_build_object('ok', false, 'reason', 'unknown_product');
    end if;

    select account into v_owner from public.play_purchases where purchase_token = p_token;
    if v_owner is not null and v_owner <> p_account then
        return jsonb_build_object('ok', false, 'reason', 'token_belongs_to_another_account');
    end if;

    insert into public.play_purchases
        (purchase_token, account, product_id, kind, state, expires_at, order_id, raw, verified_at)
    values (p_token, p_account, p_product, 'subs', p_state, p_expires, p_order, p_raw, now())
    on conflict (purchase_token) do update
        set state = excluded.state, expires_at = excluded.expires_at,
            order_id = coalesce(excluded.order_id, public.play_purchases.order_id),
            raw = excluded.raw, verified_at = now();

    v_until := case when p_state in ('active', 'cancelled') then p_expires else now() end;
    insert into public.entitlements (account, product, source, expires_at, purchase_token)
    values (p_account, p_product, 'subscription', v_until, p_token)
    on conflict (source, purchase_token) do update
        set product = excluded.product, expires_at = excluded.expires_at;

    return jsonb_build_object('ok', true, 'plan', public.plan_of(p_account));
end $$;

revoke all on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
    from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.subscription_update(uuid, text, text, text, timestamptz, text, jsonb)
            to service_role;
    end if;
end $$;
