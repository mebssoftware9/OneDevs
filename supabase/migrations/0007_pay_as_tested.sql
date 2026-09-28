-- Tests are paid from the developer's balance as they happen.
--
-- The escrow in 0006 was the wrong shape: it locked coins away at listing
-- time and needed refunds to give them back. Paying per test needs neither.
-- A listing is on the board while its owner can still afford a test, and
-- drops off the moment they cannot -- which is the same sentence as the rule,
-- rather than an approximation of it.

drop trigger if exists listings_charge on public.listings;
drop function if exists public.charge_for_listing();

alter table public.listings drop column if exists slots;
alter table public.listings drop column if exists escrowed;

-- Escrow and refund no longer happen for listings. The mission reasons stay:
-- a mission is a commitment over fourteen days and still holds its money.
alter table public.coin_entries drop constraint coin_entries_reason_check;
alter table public.coin_entries add constraint coin_entries_reason_check
    check (reason in (
        'grant', 'test_reward', 'test_payment',
        'mission_escrow', 'mission_day', 'mission_bonus', 'mission_refund'
    ));

/**
 * What is actually on the board.
 *
 * A listing appears while its owner's balance covers one more test. Below
 * that, the app leaves -- not hidden behind a disabled button, gone, because
 * a row that cannot pay is a row that wastes a tester's thirty-two seconds.
 *
 * Deliberately NOT security_invoker: it has to read the owner's ledger to sum
 * their balance, which row-level security would correctly hide from everyone
 * else. It exposes listing columns only, never a number about anyone's money.
 */
create or replace view public.board_listings as
select l.*
from public.listings l
where (
    select coalesce(sum(c.delta), 0)
    from public.coin_entries c
    where c.account = l.owner
) >= l.reward;

grant select on public.board_listings to authenticated;

/**
 * A completed test: one payment, two entries, one transaction.
 *
 * The developer pays and the tester is paid in the same statement, so there is
 * no moment where a coin exists in neither place or both. If the developer
 * cannot afford it the test is refused before anything is written -- and the
 * tester is told the listing is unfunded rather than being paid by the
 * platform, because the platform does not mint.
 */
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
    if p_seconds < 32 then
        return jsonb_build_object('claimed', false, 'reason', 'too_short');
    end if;

    -- Serialise on the developer, or two testers finishing at once can both
    -- pass a balance check only one of them can be paid from.
    perform 1 from public.profiles where id = v_owner for update;

    if public.balance_of(v_owner) < v_reward then
        return jsonb_build_object('claimed', false, 'reason', 'unfunded');
    end if;

    insert into public.tests (listing_id, tester, seconds, device)
    values (p_listing, auth.uid(), p_seconds, p_device)
    on conflict do nothing;
    get diagnostics v_rows = row_count;

    if v_rows <> 1 then
        return jsonb_build_object('claimed', false, 'reason', 'already');
    end if;

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_owner, -v_reward, 'test_payment', p_listing),
           (auth.uid(), v_reward, 'test_reward', p_listing);

    return jsonb_build_object('claimed', true, 'coins', v_reward);
end $$;

revoke all on function public.claim_test(uuid, int, text) from public, anon;
grant execute on function public.claim_test(uuid, int, text) to authenticated;
