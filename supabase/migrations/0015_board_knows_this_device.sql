-- The board should only offer what this tester can actually earn.
--
-- board_listings already drops your own apps, apps you have tested, and apps
-- whose owner cannot pay. It cannot know the device, and the device is the
-- rule that bites: one phone earns a given app once, whatever account it is
-- signed into. Without this, someone spends thirty-two seconds on a test that
-- was always going to be refused -- which is indistinguishable, from where
-- they are sitting, from being cheated.
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
    order by b.created_at desc
    limit p_limit;
$$;

revoke all on function public.board(text, text, int) from public, anon;
grant execute on function public.board(text, text, int) to authenticated;
