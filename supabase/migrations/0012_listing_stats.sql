/**
 * How a listing is doing, for the developer who owns it.
 *
 * A function rather than a policy on tests: the owner should see how many
 * people tested and what it cost, not who they were. Row-level security keeps
 * tests private to the tester, and that stays true -- this returns counts.
 */
create or replace function public.listing_stats(p_listing uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_tests int;
    v_spent int;
    v_reward int;
    v_balance int;
begin
    select owner, reward into v_owner, v_reward
    from public.listings where id = p_listing;

    if v_owner is null then raise exception 'no such listing'; end if;
    if v_owner <> auth.uid() then raise exception 'not your listing'; end if;

    select count(*) into v_tests from public.tests where listing_id = p_listing;

    -- Payments are recorded as negative against the developer, so the amount
    -- spent is their sum inverted.
    select coalesce(-sum(delta), 0)::int into v_spent
    from public.coin_entries
    where ref = p_listing and reason = 'test_payment';

    v_balance := public.balance_of(v_owner);

    return jsonb_build_object(
        'testers', v_tests,
        'spent', v_spent,
        -- How many more tests the current balance can pay for. Zero means the
        -- listing has already left the board.
        'tests_left', case when v_reward > 0 then v_balance / v_reward else 0 end
    );
end $$;

revoke all on function public.listing_stats(uuid) from public, anon;
grant execute on function public.listing_stats(uuid) to authenticated;
