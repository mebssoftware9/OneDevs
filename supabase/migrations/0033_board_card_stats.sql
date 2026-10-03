-- What a Board card needs to say about an app, from the Board itself.
--
-- Each row now carries how many people have tested the app (on the Board or
-- in a mission), so the card can say how far it is from Google's twelve, and
-- how many apps its developer has tested for others, so testers can see who
-- gives back. Appended to board_listings; nothing that read it changes.

create index if not exists mission_checkins_listing_member
    on public.mission_checkins (listing_id, member);
create index if not exists mission_checkins_member_listing
    on public.mission_checkins (member, listing_id);
create index if not exists tests_tester_listing
    on public.tests (tester, listing_id);

create or replace view public.board_listings as
select l.*,
       sp.listing_id is not null as spotlight,
       case when sp.listing_id is not null then 50 end as spotlight_reward,
       -- People who have used the app, on the Board or in a mission.
       (select count(*) from (
            select t.tester from public.tests t where t.listing_id = l.id
            union
            select c.member from public.mission_checkins c where c.listing_id = l.id
        ) u)::int as testers,
       -- Apps its developer has tested for others: who gives back.
       (select count(*) from (
            select t.listing_id from public.tests t where t.tester = l.owner
            union
            select c.listing_id from public.mission_checkins c where c.member = l.owner
        ) g)::int as owner_tested
from public.listings l
left join lateral (
    select g.listing_id from public.ghostline_runs g
    where g.listing_id = l.id
      and g.kind = 'cycle'
      and g.state in ('running', 'extended')
      and now() >= g.started_at and now() < g.ends_at
      and floor(extract(epoch from now() - g.started_at) / 86400)::int % 4 = 0
    limit 1
) sp on true
where (
    sp.listing_id is not null
    or (
        coalesce((select ab.balance from public.account_balances ab where ab.account = l.owner), 0) - (
            select coalesce(sum(s.reward), 0)
            from public.test_sessions s
            where s.owner = l.owner and s.state = 'open' and s.expires_at > now()
        ) >= l.reward
        and not exists (
            select 1 from public.ghostline_runs g
            where g.listing_id = l.id and g.state in ('running', 'extended')
        )
    )
)
and not exists (
    select 1 from public.tests t
    where t.listing_id = l.id and t.tester = auth.uid()
);

grant select on public.board_listings to authenticated;

/** One board for this person and this phone, spotlighted apps first. */
create or replace function public.board(
    p_channel text,
    p_device text,
    p_limit int default 50
)
returns setof public.board_listings
language sql
stable
security definer
set search_path = ''
as $$
    select b.*
    from public.board_listings b
    where b.channel = p_channel
      and not exists (
          select 1
          from public.tests t
          where t.listing_id = b.id
            and (t.tester = auth.uid() or t.device = p_device)
      )
      -- Your own spotlight is not yours to test.
      and not (b.spotlight and b.owner = auth.uid())
    order by b.spotlight desc, b.created_at desc
    limit p_limit;
$$;

revoke all on function public.board(text, text, int) from public, anon;
grant execute on function public.board(text, text, int) to authenticated;

/** Everything the Board shows, in one call, spotlighted apps first. */
create or replace function public.home_board(
    p_device text,
    p_limit int default 50
) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    return jsonb_build_object(
        'stats', public.platform_stats(),
        'testing', coalesce(
            (select jsonb_agg(to_jsonb(b) order by b.spotlight desc, b.created_at desc)
             from public.board('testing', p_device, p_limit) b),
            '[]'::jsonb
        ),
        'live', coalesce(
            (select jsonb_agg(to_jsonb(b) order by b.created_at desc)
             from public.board('live', p_device, p_limit) b),
            '[]'::jsonb
        )
    );
end $$;

revoke all on function public.home_board(text, int) from public, anon;
grant execute on function public.home_board(text, int) to authenticated;

