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
    if v <> 700 then raise exception 'FAIL: balance reads %, expected 700', v; end if;

    -- and the ledger shows only A's entries
    select count(*) into n from public.coin_entries;
    if n <> 1 then raise exception 'FAIL: A can see % ledger entries, expected 1', n; end if;

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

    raise exception 'ACCESS OK -- all assertions passed, nothing kept';
end $$;

rollback;
