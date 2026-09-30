-- Mission names: 500 of them, drawn at random, none repeated until all have
-- had a turn. Rolled back at the end.

begin;

do $$
declare
    v_names text[] := '{}';
    v_name text;
    v_id uuid;
    i int;
begin
    if (select count(*) from public.mission_names) <> 500 then
        raise exception 'FAIL: % mission names, expected 500', (select count(*) from public.mission_names);
    end if;

    -- Open sixty missions, starting each so the next one opens.
    for i in 1..60 loop
        -- Two statements: the one that opens a mission cannot see it.
        v_id := public.recruiting_group();
        select name into v_name from public.mission_groups where id = v_id;
        v_names := v_names || v_name;
        update public.mission_groups set state = 'running', started_on = current_date
        where state = 'recruiting';
    end loop;

    if (select count(distinct n) from unnest(v_names) n) <> 60 then
        raise exception 'FAIL: a name came back before its turn: %', v_names;
    end if;
    if exists (
        select 1 from unnest(v_names) n
        where not exists (select 1 from public.mission_names m where 'Mission ' || m.name = n)
    ) then
        raise exception 'FAIL: a name not from the list: %', v_names;
    end if;
    -- Not simply alphabetical: sixty draws in list order would be a fluke.
    if v_names = (select array_agg(x order by x) from unnest(v_names) x) then
        raise exception 'FAIL: names came out in order, not at random';
    end if;

    -- Once every name has had a turn, the next round carries its number.
    update public.mission_names set uses = 1;
    select public.draw_mission_name() into v_name;
    if v_name !~ '^Mission [A-Z][A-Za-z]+ 2$' then
        raise exception 'FAIL: second round name %', v_name;
    end if;

    raise notice 'mission names: all checks passed';
end $$;

rollback;
