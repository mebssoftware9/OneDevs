-- Every coin movement in OneDevs. Nothing outside this file can write to
-- coin_entries: the table has a select policy and no other, so these functions
-- -- which the database runs as its owner -- are the only doors.

create function public.balance_of(p_account uuid) returns int
language sql stable security definer set search_path = '' as $$
    select coalesce(sum(delta), 0)::int
    from public.coin_entries where account = p_account;
$$;

-- The one place coins are created rather than moved, which is why it is a named
-- reason in the ledger and not a quiet insert. Needed at genesis: until someone
-- holds coins nobody can fund a mission, so there is nothing to test and no way
-- to earn. Service key only -- no app user can reach it.
create function public.grant_coins(p_account uuid, p_amount int) returns bigint
language plpgsql security definer set search_path = '' as $$
declare v_id bigint;
begin
    if p_amount <= 0 then
        raise exception 'a grant must be positive';
    end if;
    insert into public.coin_entries (account, delta, reason)
    values (p_account, p_amount, 'grant')
    returning id into v_id;
    return v_id;
end $$;

revoke all on function public.grant_coins(uuid, int) from public, anon, authenticated;
grant execute on function public.grant_coins(uuid, int) to service_role;

-- Funding a mission takes the whole cost up front. A campaign that could run
-- out of money halfway through is one that stops paying people who already did
-- the work.
create function public.create_mission(
    p_listing uuid,
    p_slots int,
    p_required_days int,
    p_reward_per_day int,
    p_completion_bonus int
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_channel text;
    v_slot int;
    v_cost int;
    v_mission uuid;
begin
    select owner, channel into v_owner, v_channel
    from public.listings where id = p_listing;

    if v_owner is null then
        raise exception 'no such listing';
    end if;
    if v_owner <> auth.uid() then
        raise exception 'only the owner can fund a mission for this listing';
    end if;
    if v_channel <> 'testing' then
        raise exception 'a mission enforces closed-test continuity, so the listing must be a closed test';
    end if;

    -- Serialise on the account, or two missions funded at once can both pass a
    -- balance check that only one of them can afford.
    perform 1 from public.profiles where id = v_owner for update;

    v_slot := p_required_days * p_reward_per_day + p_completion_bonus;
    v_cost := p_slots * v_slot;

    if public.balance_of(v_owner) < v_cost then
        raise exception 'not enough DevCoins: this mission costs %', v_cost;
    end if;

    insert into public.missions (
        listing_id, slots, required_days, reward_per_day, completion_bonus,
        slot_cost, escrowed
    )
    values (
        p_listing, p_slots, p_required_days, p_reward_per_day, p_completion_bonus,
        v_slot, v_cost
    )
    returning id into v_mission;

    insert into public.coin_entries (account, delta, reason, ref)
    values (v_owner, -v_cost, 'mission_escrow', v_mission);

    return v_mission;
end $$;

create function public.join_mission(p_mission uuid, p_device text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
    v_state text;
    v_slots int;
    v_owner uuid;
    v_taken int;
    v_enrollment uuid;
begin
    select m.state, m.slots, l.owner into v_state, v_slots, v_owner
    from public.missions m
    join public.listings l on l.id = m.listing_id
    where m.id = p_mission
    for update of m;

    if v_state is null then raise exception 'no such mission'; end if;
    if v_state <> 'open' then raise exception 'this mission is closed'; end if;
    if v_owner = auth.uid() then
        raise exception 'testing your own app proves nothing';
    end if;

    select count(*) into v_taken
    from public.enrollments
    where mission_id = p_mission and state <> 'lapsed';

    if v_taken >= v_slots then raise exception 'this mission is full'; end if;

    if p_device is null or char_length(p_device) < 8 then
        raise exception 'a device is required to join a mission';
    end if;

    insert into public.enrollments (mission_id, tester, device)
    values (p_mission, auth.uid(), p_device)
    returning id into v_enrollment;

    return v_enrollment;
end $$;

-- A day of evidence.
--
-- The device says how many seconds it saw the app in the foreground. It does
-- not get to say which day that was: current_date here is the server's, so a
-- phone clock wound forward buys nothing. One row per day means a replay is a
-- no-op rather than a second payday.
--
-- The streak is strict. A gap marks the enrollment lapsed, because the thing a
-- developer is buying is fourteen CONTINUOUS days, and counting fourteen
-- scattered ones while calling it continuous would be a lie told by software.
create function public.record_day(p_enrollment uuid, p_seconds int) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_tester uuid;
    v_state text;
    v_mission uuid;
    v_required int;
    v_per_day int;
    v_bonus int;
    v_today date := (now() at time zone 'utc')::date;
    v_last date;
    v_rows int;
    v_days int;
    v_complete boolean := false;
begin
    select e.tester, e.state, e.mission_id,
           m.required_days, m.reward_per_day, m.completion_bonus
    into v_tester, v_state, v_mission, v_required, v_per_day, v_bonus
    from public.enrollments e
    join public.missions m on m.id = e.mission_id
    where e.id = p_enrollment
    for update of e;

    if v_tester is null then raise exception 'no such enrollment'; end if;
    if v_tester <> auth.uid() then raise exception 'not your enrollment'; end if;

    if v_state <> 'active' then
        return jsonb_build_object('counted', false, 'reason', v_state);
    end if;
    if p_seconds < 32 then
        return jsonb_build_object('counted', false, 'reason', 'too_short');
    end if;

    select max(day) into v_last
    from public.mission_days where enrollment_id = p_enrollment;

    if v_last is not null and v_last < v_today - 1 then
        perform public.lapse_enrollment(p_enrollment);
        return jsonb_build_object(
            'counted', false, 'reason', 'lapsed', 'last_day', v_last
        );
    end if;

    insert into public.mission_days (enrollment_id, day, seconds)
    values (p_enrollment, v_today, p_seconds)
    on conflict (enrollment_id, day) do nothing;
    get diagnostics v_rows = row_count;

    -- Paid only on the first submission of the day. The second is welcome and
    -- earns nothing.
    if v_rows = 1 and v_per_day > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (v_tester, v_per_day, 'mission_day', p_enrollment);
    end if;

    select count(*) into v_days
    from public.mission_days where enrollment_id = p_enrollment;

    if v_days >= v_required then
        update public.enrollments set state = 'complete' where id = p_enrollment;
        if v_bonus > 0 then
            insert into public.coin_entries (account, delta, reason, ref)
            values (v_tester, v_bonus, 'mission_bonus', p_enrollment);
        end if;
        v_complete := true;
    end if;

    return jsonb_build_object(
        'counted', v_rows = 1,
        'days', v_days,
        'required', v_required,
        'complete', v_complete
    );
end $$;

-- A slot that will never be finished releases the part of it nobody earned.
-- Called when a streak breaks and by the sweep below; never by a client.
create function public.lapse_enrollment(p_enrollment uuid) returns int
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_slot int;
    v_paid int;
    v_refund int;
begin
    select l.owner, m.slot_cost into v_owner, v_slot
    from public.enrollments e
    join public.missions m on m.id = e.mission_id
    join public.listings l on l.id = m.listing_id
    where e.id = p_enrollment;

    update public.enrollments set state = 'lapsed'
    where id = p_enrollment and state = 'active';
    if not found then return 0; end if;

    select coalesce(sum(delta), 0)::int into v_paid
    from public.coin_entries
    where ref = p_enrollment and reason in ('mission_day', 'mission_bonus');

    v_refund := v_slot - v_paid;
    if v_refund > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (v_owner, v_refund, 'mission_refund', p_enrollment);
    end if;
    return greatest(v_refund, 0);
end $$;

revoke all on function public.lapse_enrollment(uuid) from public, anon, authenticated;

-- A tester who joins and never shows up holds a slot the developer paid for.
-- Two days of silence releases it. Run on a schedule, not by a client.
create function public.sweep_lapsed() returns int
language plpgsql security definer set search_path = '' as $$
declare
    v_enrollment uuid;
    v_count int := 0;
begin
    for v_enrollment in
        select e.id
        from public.enrollments e
        where e.state = 'active'
          and coalesce(
                (select max(d.day) from public.mission_days d
                 where d.enrollment_id = e.id),
                e.joined_on
              ) < (now() at time zone 'utc')::date - 1
    loop
        perform public.lapse_enrollment(v_enrollment);
        v_count := v_count + 1;
    end loop;
    return v_count;
end $$;

revoke all on function public.sweep_lapsed() from public, anon, authenticated;
grant execute on function public.sweep_lapsed() to service_role;

-- Closing stops new testers. It does NOT stop paying the ones already working:
-- a developer must not be able to watch thirteen days of testing and then close
-- the mission to keep the bonus. Only slots nobody ever took come back, and the
-- rest settles as each tester finishes or drops.
create function public.close_mission(p_mission uuid) returns int
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_state text;
    v_slots int;
    v_slot_cost int;
    v_held int;
    v_refund int;
begin
    select l.owner, m.state, m.slots, m.slot_cost
    into v_owner, v_state, v_slots, v_slot_cost
    from public.missions m
    join public.listings l on l.id = m.listing_id
    where m.id = p_mission
    for update of m;

    if v_owner is null then raise exception 'no such mission'; end if;
    if v_owner <> auth.uid() then raise exception 'not your mission'; end if;
    if v_state = 'closed' then raise exception 'this mission is already closed'; end if;

    -- Slots currently spoken for. Lapsed ones were already refunded.
    select count(*) into v_held
    from public.enrollments
    where mission_id = p_mission and state in ('active', 'complete');

    v_refund := (v_slots - v_held) * v_slot_cost;

    if v_refund > 0 then
        insert into public.coin_entries (account, delta, reason, ref)
        values (v_owner, v_refund, 'mission_refund', p_mission);
    end if;

    update public.missions set state = 'closed' where id = p_mission;
    return greatest(v_refund, 0);
end $$;

revoke all on function public.create_mission(uuid, int, int, int, int) from public, anon;
revoke all on function public.join_mission(uuid, text) from public, anon;
revoke all on function public.record_day(uuid, int) from public, anon;
revoke all on function public.close_mission(uuid) from public, anon;

grant execute on function public.balance_of(uuid) to authenticated;
grant execute on function public.create_mission(uuid, int, int, int, int) to authenticated;
grant execute on function public.join_mission(uuid, text) to authenticated;
grant execute on function public.record_day(uuid, int) to authenticated;
grant execute on function public.close_mission(uuid) to authenticated;
