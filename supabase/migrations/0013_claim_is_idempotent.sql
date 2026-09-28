-- A claim that succeeds but whose reply is lost must not look like a failure.
--
-- On a bad connection the payment commits and the response times out. The
-- client reported failure, the tester saw nothing, and a retry said "already
-- claimed" -- which reads exactly like the reward being eaten, even though the
-- ledger was correct the whole time.
--
-- Retrying now returns the same answer the first call would have: claimed,
-- with the amount. The unique index still means it can only ever be paid once,
-- so this is safe to call as many times as a flaky network requires.
create or replace function public.claim_test(
    p_listing uuid,
    p_seconds int,
    p_device text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_reward int;
    v_rows int;
    v_mine boolean;
begin
    select owner, reward into v_owner, v_reward
    from public.listings where id = p_listing for update;

    if v_owner is null then raise exception 'no such listing'; end if;
    if v_owner = auth.uid() then
        raise exception 'testing your own app proves nothing';
    end if;
    if p_device is null or char_length(p_device) < 8 then
        raise exception 'a device is required';
    end if;

    -- Asked before anything else: if this tester already has it, the answer is
    -- the same as the first time, whatever the seconds say now.
    select exists (
        select 1 from public.tests
        where listing_id = p_listing and tester = auth.uid()
    ) into v_mine;

    if v_mine then
        return jsonb_build_object('claimed', true, 'coins', v_reward, 'repeat', true);
    end if;

    if p_seconds < 32 then
        return jsonb_build_object('claimed', false, 'reason', 'too_short');
    end if;

    perform 1 from public.profiles where id = v_owner for update;

    if public.balance_of(v_owner) < v_reward then
        return jsonb_build_object('claimed', false, 'reason', 'unfunded');
    end if;

    insert into public.tests (listing_id, tester, seconds, device)
    values (p_listing, auth.uid(), p_seconds, p_device)
    on conflict do nothing;
    get diagnostics v_rows = row_count;

    if v_rows <> 1 then
        -- The device already claimed this listing under another account.
        return jsonb_build_object('claimed', false, 'reason', 'device_used');
    end if;

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_owner, -v_reward, 'test_payment', p_listing),
           (auth.uid(), v_reward, 'test_reward', p_listing);

    return jsonb_build_object('claimed', true, 'coins', v_reward);
end $$;

revoke all on function public.claim_test(uuid, int, text) from public, anon;
grant execute on function public.claim_test(uuid, int, text) to authenticated;
