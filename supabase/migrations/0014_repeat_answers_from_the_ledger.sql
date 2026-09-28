-- A repeat claim must be answered from the ledger, not from the row.
--
-- 0013 returned success the moment a tests row existed. If a row were ever
-- written without its credit, that answer would be a lie told forever. This
-- asks the only question that matters -- was this tester paid -- and if the
-- row is there without the money, it finishes what the first call started.
create or replace function public.claim_test(
    p_listing uuid,
    p_seconds int,
    p_device text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid;
    v_reward int;
    v_rows int;
    v_mine boolean;
    v_paid boolean;
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

    select exists (
        select 1 from public.tests
        where listing_id = p_listing and tester = auth.uid()
    ) into v_mine;

    if v_mine then
        select exists (
            select 1 from public.coin_entries
            where account = auth.uid()
              and ref = p_listing
              and reason = 'test_reward'
        ) into v_paid;

        if v_paid then
            return jsonb_build_object('claimed', true, 'coins', v_reward, 'repeat', true);
        end if;

        perform 1 from public.profiles where id = v_owner for update;
        if public.balance_of(v_owner) < v_reward then
            return jsonb_build_object('claimed', false, 'reason', 'unfunded');
        end if;

        insert into public.coin_entries (account, delta, reason, ref)
        values (v_owner, -v_reward, 'test_payment', p_listing),
               (auth.uid(), v_reward, 'test_reward', p_listing);

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
        return jsonb_build_object('claimed', false, 'reason', 'device_used');
    end if;

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_owner, -v_reward, 'test_payment', p_listing),
           (auth.uid(), v_reward, 'test_reward', p_listing);

    return jsonb_build_object('claimed', true, 'coins', v_reward);
end
$$;
