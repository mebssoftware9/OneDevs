-- Grants and policies, tested as the role the API actually uses.
--
-- The seeding happens as the owner, then the session switches to
-- "authenticated" with a pretend signed-in user -- which is what a real request
-- from the app looks like once its JWT has been read. Rolled back at the end,
-- so it leaves nothing behind.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
) values
('00000000-0000-0000-0000-000000000000', 'aaaaaaaa-0000-0000-0000-000000000001',
 'authenticated', 'authenticated', 'a@onedevs.test', 'x', now(), now(), now(),
 '{}'::jsonb, '{}'::jsonb, false),
('00000000-0000-0000-0000-000000000000', 'bbbbbbbb-0000-0000-0000-000000000002',
 'authenticated', 'authenticated', 'b@onedevs.test', 'x', now(), now(), now(),
 '{}'::jsonb, '{}'::jsonb, false);

insert into public.listings (owner, package_name, title, category, channel)
values ('aaaaaaaa-0000-0000-0000-000000000001', 'com.a.app', 'A app', 'Tools', 'testing'),
       ('bbbbbbbb-0000-0000-0000-000000000002', 'com.b.app', 'B app', 'Tools', 'testing');

select public.grant_coins('aaaaaaaa-0000-0000-0000-000000000001', 700);
select public.grant_coins('bbbbbbbb-0000-0000-0000-000000000002', 300);

-- ---- from here on, this session is an ordinary signed-in user: A
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);

do $$
declare n int; v int;
begin
    -- the Board is shared: A sees both listings
    select count(*) into n from public.listings;
    if n <> 2 then raise exception 'FAIL: signed-in user sees % listings, expected 2', n; end if;

    -- the balance view answers for the caller and nobody else
    select count(*) into n from public.coin_balance;
    if n <> 1 then raise exception 'FAIL: coin_balance returned % rows, expected 1', n; end if;
    select balance into v from public.coin_balance;
    -- 75 from the welcome grant every account gets, 700 granted above
    if v <> 775 then raise exception 'FAIL: balance reads %, expected 775', v; end if;

    -- and the ledger shows only A's entries
    select count(*) into n from public.coin_entries;
    if n <> 2 then raise exception 'FAIL: A can see % ledger entries, expected 2', n; end if;

    -- nor read B's balance by passing B's id, which the Board shows
    begin
        perform public.balance_of('bbbbbbbb-0000-0000-0000-000000000002');
        raise exception 'FAIL: A read another account''s balance';
    exception when insufficient_privilege then
        null;
    end;

    -- nor reach the campaign missions 0016 replaced, which paid on reported seconds
    begin
        perform public.record_day(gen_random_uuid(), 45);
        raise exception 'FAIL: a client reached record_day';
    exception when insufficient_privilege then
        null;
    end;

    -- the app's own save still works: an upsert on id with exactly its columns
    insert into public.listings (id, owner, package_name, title, category, channel, size_bytes,
                                 test_note, play_url, icon_url, public_listing, checked_at)
    values ('aaaaaaaa-5555-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
            'com.a.second', 'A second', 'Tools', 'testing', 1000, 'Open it once a day',
            'https://play.google.com/apps/testing/com.a.second',
            'https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/icons/'
                || 'aaaaaaaa-0000-0000-0000-000000000001/aaaaaaaa-5555-0000-0000-000000000001.png',
            true, now())
    on conflict (id) do update set
        id = excluded.id, owner = excluded.owner, package_name = excluded.package_name,
        title = excluded.title, category = excluded.category, channel = excluded.channel,
        size_bytes = excluded.size_bytes, test_note = excluded.test_note,
        play_url = excluded.play_url, icon_url = excluded.icon_url,
        public_listing = excluded.public_listing, checked_at = excluded.checked_at;

    -- but not the Board's order, nor the price of a test
    begin
        update public.listings set created_at = now() + interval '10 years'
        where owner = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: A moved its listing up the Board by dating it ahead';
    exception when insufficient_privilege then
        null;
    end;
    begin
        update public.listings set reward = 0 where owner = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: A set the price of a test';
    exception when insufficient_privilege then
        null;
    end;

    -- a tester is only ever sent to Play, and an icon only comes from its owner's folder
    begin
        update public.listings set play_url = 'https://example.com/install.apk'
        where owner = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: a listing points testers away from Play';
    exception when check_violation then
        null;
    end;
    begin
        update public.listings set icon_url = 'https://example.com/huge.png'
        where owner = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: a listing icon is fetched from anywhere';
    exception when check_violation then
        null;
    end;
    begin
        update public.listings
        set icon_url = 'https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/icons/'
                       || 'bbbbbbbb-0000-0000-0000-000000000002/x.png'
        where owner = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: a listing uses an icon from someone else''s folder';
    exception when check_violation then
        null;
    end;

    -- profiles are the database's to write, and when someone was last seen is theirs
    begin
        update public.profiles set display_name = 'OneDevs Support'
        where id = 'aaaaaaaa-0000-0000-0000-000000000001';
        raise exception 'FAIL: A renamed itself';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform last_seen from public.profiles limit 1;
        raise exception 'FAIL: A read when others were last seen';
    exception when insufficient_privilege then
        null;
    end;
    select count(*) into n from public.coin_balance;
    if n <> 1 then raise exception 'FAIL: coin_balance broke without profiles.last_seen'; end if;

    -- A cannot plant a listing under B's name
    begin
        insert into public.listings (owner, package_name, title, category, channel)
        values ('bbbbbbbb-0000-0000-0000-000000000002', 'com.forged.app',
                'forged', 'Tools', 'testing');
        raise exception 'FAIL: a listing was inserted under another account';
    exception when insufficient_privilege then
        null;
    end;

    -- and cannot write the ledger at all, by any route
    begin
        insert into public.coin_entries (account, delta, reason)
        values ('aaaaaaaa-0000-0000-0000-000000000001', 1000000, 'grant');
        raise exception 'FAIL: a client wrote to the coin ledger';
    exception when insufficient_privilege then
        null;
    end;

    -- nor mint by calling the granting function
    begin
        perform public.grant_coins('aaaaaaaa-0000-0000-0000-000000000001', 1000000);
        raise exception 'FAIL: a client called grant_coins';
    exception when insufficient_privilege then
        null;
    end;

    raise notice 'access: all checks passed';
end $$;

rollback;
