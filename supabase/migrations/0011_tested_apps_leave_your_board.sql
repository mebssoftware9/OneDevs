-- An app you have already tested is not an opportunity, it is a row you cannot
-- act on. It leaves your board and nobody else's: the view is evaluated per
-- caller, so the same listing is still there for everyone who has not tested it.
--
-- Your own listing stays. Seeing it among the others -- and watching it slide
-- down, and vanish when you can no longer fund a test -- is how a developer
-- knows where they stand.
create or replace view public.board_listings as
select l.*
from public.listings l
where (
    select coalesce(sum(c.delta), 0)
    from public.coin_entries c
    where c.account = l.owner
) >= l.reward
and not exists (
    select 1 from public.tests t
    where t.listing_id = l.id and t.tester = auth.uid()
);

grant select on public.board_listings to authenticated;
