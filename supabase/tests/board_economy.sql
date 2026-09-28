-- The board economy end to end: grant, listing cost, test reward, and the
-- rules that stop each being taken twice.
--
-- One DO block that raises at the end, so it rolls itself back and can be run
-- against the live database as often as you like.

do $$
declare
    devA uuid := 'aaaaaaaa-0000-0000-0000-00000000000a';
    devB uuid := 'bbbbbbbb-0000-0000-0000-00000000000b';
    tester uuid := 'cccccccc-0000-0000-0000-00000000000c';
    v_listing uuid := 'dddddddd-0000-0000-0000-00000000000d';
    v int;
    r jsonb;
    ok boolean;
    minted int;
    total int;
begin
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, created_at, updated_at,
        raw_app_meta_data, raw_user_meta_data, is_super_admin
    )
    select '00000000-0000-0000-0000-000000000000', u.id, 'authenticated',
           'authenticated', u.email, 'x', now(), now(), now(),
           '{}'::jsonb, '{}'::jsonb, false
    from (values (devA, 'a@onedevs.test'), (devB, 'b@onedevs.test'),
                 (tester, 't@onedevs.test')) as u(id, email);

    -- ---- every new account is granted 75, once
    v := public.balance_of(devA);
    if v <> 75 then raise exception 'FAIL welcome: got %, expected 75', v; end if;

    -- ---- listing costs 75 and the escrow leaves the developer
    perform set_config('request.jwt.claims',
        json_build_object('sub', devA)::text, true);

    insert into public.listings (id, owner, package_name, title, category, channel)
    values (v_listing, devA, 'com.a.app', 'A app', 'Tools', 'testing');

    v := public.balance_of(devA);
    if v <> 0 then raise exception 'FAIL listing cost: left with %, expected 0', v; end if;
    select escrowed into v from public.listings where id = v_listing;
    if v <> 75 then raise exception 'FAIL escrow: held %, expected 75', v; end if;

    -- ---- a second listing is refused: there is nothing left to fund it
    ok := false;
    begin
        insert into public.listings (owner, package_name, title, category, channel)
        values (devA, 'com.a.second', 'Second', 'Tools', 'testing');
    exception when others then
        ok := sqlerrm like '%not enough DevCoins%';
    end;
    if not ok then raise exception 'FAIL: a second listing was allowed for free'; end if;

    -- ---- editing must not charge again (the upsert path the client uses)
    insert into public.listings (id, owner, package_name, title, category, channel)
    values (v_listing, devA, 'com.a.app', 'A app renamed', 'Tools', 'testing')
    on conflict (id) do update set title = excluded.title;

    v := public.balance_of(devA);
    if v <> 0 then raise exception 'FAIL: editing charged again, balance %', v; end if;

    -- ---- a developer cannot test their own app
    ok := false;
    begin
        perform public.claim_test(v_listing, 45, 'device-owner-01');
    exception when others then
        ok := sqlerrm like '%own app%';
    end;
    if not ok then raise exception 'FAIL: the owner claimed their own listing'; end if;

    -- ---- a tester claims once, and is paid 25
    perform set_config('request.jwt.claims',
        json_build_object('sub', tester)::text, true);

    r := public.claim_test(v_listing, 45, 'device-tester-01');
    if not (r->>'claimed')::boolean then
        raise exception 'FAIL: first claim refused: %', r;
    end if;

    v := public.balance_of(tester);
    if v <> 100 then raise exception 'FAIL reward: tester has %, expected 100', v; end if;

    -- ---- and claiming again earns nothing, without erroring
    r := public.claim_test(v_listing, 45, 'device-tester-01');
    if (r->>'claimed')::boolean then raise exception 'FAIL: paid twice for one test'; end if;
    if r->>'reason' <> 'already' then
        raise exception 'FAIL: second claim said %, expected already', r->>'reason';
    end if;

    v := public.balance_of(tester);
    if v <> 100 then raise exception 'FAIL: balance moved on a repeat claim: %', v; end if;

    -- ---- 32 seconds is the floor
    perform set_config('request.jwt.claims',
        json_build_object('sub', devB)::text, true);
    r := public.claim_test(v_listing, 31, 'device-devb-0001');
    if (r->>'claimed')::boolean then raise exception 'FAIL: 31 seconds was paid'; end if;

    -- ---- nothing was created outside the grants
    select coalesce(sum(delta), 0) into minted
        from public.coin_entries where reason = 'grant';
    select coalesce(sum(delta), 0) into total from public.coin_entries;
    if minted <> 225 then raise exception 'FAIL: granted %, expected 225', minted; end if;
    if minted <> total then
        raise exception 'FAIL: supply leaked -- granted %, circulating %', minted, total;
    end if;

    raise exception 'BOARD ECONOMY OK -- all assertions passed, nothing kept';
end $$;
