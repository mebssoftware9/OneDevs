-- The wallet: what you have, what is promised, and where every coin went.
--
-- The app showed "No DevCoins yet" beside a balance of 75, because the wallet
-- screen had nothing to read. The ledger was always there; this names each
-- line -- the app tested, the listing paid for, the mission joined -- so the
-- history reads as things that happened rather than as row ids.

create or replace function public.wallet(p_limit int default 100) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'balance', public.balance_of(auth.uid()),
        -- Promised to testers who are mid-test on your listings.
        'held', public.held_by(auth.uid()),
        'entries', coalesce((
            select jsonb_agg(rows.e order by rows.at desc)
            from (
                select jsonb_build_object(
                    'at', c.created_at,
                    'delta', c.delta,
                    'reason', c.reason,
                    'title', coalesce(l.title, g.name)
                ) as e, c.created_at as at
                from public.coin_entries c
                left join public.listings l on l.id = c.ref
                left join public.mission_groups g on g.id = c.ref
                where c.account = auth.uid()
                order by c.created_at desc, c.id desc
                limit greatest(1, least(p_limit, 500))
            ) rows
        ), '[]'::jsonb)
    )
    where auth.uid() is not null;
$$;

revoke all on function public.wallet(int) from public, anon;
grant execute on function public.wallet(int) to authenticated;
