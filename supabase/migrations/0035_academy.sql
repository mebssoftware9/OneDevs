-- The Academy: learning that ends in evidence.
--
-- A pathway is a list of competencies, each taught by a lesson on the phone
-- and proved by answering its questions, and for some by a release APK of the
-- learner's own app that the Lab checked. The lessons ship in the app
-- (assets/academy); what the server keeps is what the learner did.
--
-- Evidence
--   One row per answer, written on the phone and sent when there is a
--   connection. Each carries an id the phone made, so a retried send is the
--   same row, never a second answer. Rows for a competency the caller's plan
--   does not open are refused here, whatever the phone believes: the first
--   three competencies are Community's, the rest are Premium's and Pro's.
--
-- Verified
--   A competency is verified when its questions are secure: the latest
--   attempt without a hint was right, and was not made within ten minutes of
--   getting the same question wrong. At least 80% of the formative questions
--   (concept, predict) and 85% of the summative ones (debug, transfer) must
--   be secure -- the same rule the phone applies -- and a competency with a
--   gate also needs a passing check of an app this account's Lab has claimed.
--
-- Capstone
--   Worked out only from what the server already records: the caller's own
--   app on the testing board, used for at least 32 seconds by 12 or more
--   other people, on 14 or more different days. Nothing the phone says counts
--   toward it.
--
-- Credentials
--   Issued only here, once each, with a token that makes a public
--   verification link. A credential names its rubric and the content version
--   it was earned on, and can be revoked; verification says so.

-- -------------------------------------------------------------- catalogue

create table if not exists public.academy_competencies (
    pathway text not null,
    id text not null,
    position int not null,
    free boolean not null,
    artifact text check (artifact is null or artifact in ('target_sdk', 'signing', 'release_build', 'native_code')),
    formative int not null check (formative >= 0),
    summative int not null check (summative >= 0),
    primary key (pathway, id)
);

alter table public.academy_competencies enable row level security;
revoke all on public.academy_competencies from anon, authenticated;

-- Kept in step with assets/academy/ship-to-play.json by AcademyCatalogueTest.
insert into public.academy_competencies (pathway, id, position, free, artifact, formative, summative) values
    ('ship-to-play', 'closed-testing', 1, true, null, 2, 2),
    ('ship-to-play', 'exported-components', 2, true, null, 2, 2),
    ('ship-to-play', 'permissions', 3, true, null, 2, 2),
    ('ship-to-play', 'target-sdk', 4, false, 'target_sdk', 2, 2),
    ('ship-to-play', 'signing', 5, false, 'signing', 2, 2),
    ('ship-to-play', 'release-build', 6, false, 'release_build', 2, 2),
    ('ship-to-play', 'native-code', 7, false, 'native_code', 2, 2),
    ('ship-to-play', 'versions-tracks', 8, false, null, 2, 2),
    ('ship-to-play', 'store-listing', 9, false, null, 2, 2),
    ('ship-to-play', 'data-safety', 10, false, null, 2, 2)
on conflict (pathway, id) do update set
    position = excluded.position,
    free = excluded.free,
    artifact = excluded.artifact,
    formative = excluded.formative,
    summative = excluded.summative;

-- --------------------------------------------------------------- evidence

create table if not exists public.academy_evidence (
    account uuid not null references public.profiles (id) on delete cascade,
    -- Made on the phone. Scoped to the account, so nobody can claim an id
    -- another account used.
    id uuid not null,
    pathway text not null,
    competency text not null,
    -- A question's id, or 'gate' for an APK check.
    item text not null check (char_length(item) between 1 and 40),
    kind text not null check (kind in ('concept', 'predict', 'debug', 'transfer', 'artifact')),
    variant int not null default 0 check (variant between 0 and 9),
    correct boolean not null,
    hinted boolean not null default false,
    content_version int not null check (content_version > 0),
    -- The app an APK check read. Artifact rows only.
    package_name text check (package_name is null or package_name ~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'),
    -- When it happened on the phone, which may be long before it arrived.
    at timestamptz not null,
    received_at timestamptz not null default now(),
    primary key (account, id),
    foreign key (pathway, competency) references public.academy_competencies (pathway, id)
);

create index if not exists academy_evidence_progress
    on public.academy_evidence (account, pathway, competency, item, at desc);
create index if not exists academy_evidence_received
    on public.academy_evidence (account, received_at desc);

alter table public.academy_evidence enable row level security;
revoke all on public.academy_evidence from anon, authenticated;

/** Whether an account's plan opens a competency: free ones always, the rest on Premium or Pro. */
create or replace function public.academy_open(p_account uuid, p_pathway text, p_competency text)
returns boolean
language sql stable security definer set search_path = '' as $$
    select coalesce((
        select c.free or public.plan_of(p_account) in ('premium', 'pro')
        from public.academy_competencies c
        where c.pathway = p_pathway and c.id = p_competency
    ), false);
$$;

/**
 * Records answers from the phone. p_events is an array of
 *   { id, pathway, competency, item, kind, variant, correct, hinted,
 *     version, package?, at }
 * at most 100 at a time.
 *
 * { ok: true, saved, locked, rejected } or { ok: false, reason }:
 * signed_out, bad_batch, slow_down. Rows already recorded count as saved, so
 * the phone can forget everything it sent once ok is true. A locked row is
 * for a competency the plan does not open; a rejected one is malformed, from
 * the future, or a gate for an app this account's Lab has not claimed.
 */
create or replace function public.academy_record(p_events jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    me uuid := auth.uid();
    e jsonb;
    v_saved int := 0;
    v_locked int := 0;
    v_rejected int := 0;
    v_at timestamptz;
    v_kind text;
    v_package text;
begin
    if me is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if p_events is null or jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) > 100 then
        return jsonb_build_object('ok', false, 'reason', 'bad_batch');
    end if;
    -- A practice session is a few dozen answers. A thousand in a day is not
    -- someone learning.
    if (select count(*) from public.academy_evidence
        where account = me and received_at > now() - interval '1 day') + jsonb_array_length(p_events) > 1000 then
        return jsonb_build_object('ok', false, 'reason', 'slow_down');
    end if;

    for e in select * from jsonb_array_elements(p_events) loop
        begin
            v_kind := e->>'kind';
            v_at := (e->>'at')::timestamptz;
            v_package := nullif(e->>'package', '');
            if v_at > now() + interval '1 day'
               or (v_kind = 'artifact') <> (e->>'item' = 'gate')
               or (v_kind = 'artifact' and not exists (
                       select 1 from public.lab_apps
                       where account = me and package_name = v_package)) then
                v_rejected := v_rejected + 1;
                continue;
            end if;
            if not exists (
                select 1 from public.academy_competencies
                where pathway = e->>'pathway' and id = e->>'competency'
            ) then
                v_rejected := v_rejected + 1;
                continue;
            end if;
            if not public.academy_open(me, e->>'pathway', e->>'competency') then
                v_locked := v_locked + 1;
                continue;
            end if;
            insert into public.academy_evidence (
                account, id, pathway, competency, item, kind, variant,
                correct, hinted, content_version, package_name, at
            ) values (
                me, (e->>'id')::uuid, e->>'pathway', e->>'competency', e->>'item', v_kind,
                coalesce((e->>'variant')::int, 0), (e->>'correct')::boolean,
                coalesce((e->>'hinted')::boolean, false), (e->>'version')::int,
                case when v_kind = 'artifact' then v_package end, v_at
            ) on conflict (account, id) do nothing;
            v_saved := v_saved + 1;
        exception
            -- A malformed row (a missing field, a bad uuid, a check that
            -- fails) is that row's problem, not the batch's.
            when others then v_rejected := v_rejected + 1;
        end;
    end loop;

    return jsonb_build_object('ok', true, 'saved', v_saved, 'locked', v_locked, 'rejected', v_rejected);
end $$;

/** The caller's answers on one pathway, oldest first, so a new phone can carry on. */
create or replace function public.academy_mine(p_pathway text) returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', e.id,
        'competency', e.competency,
        'item', e.item,
        'kind', e.kind,
        'variant', e.variant,
        'correct', e.correct,
        'hinted', e.hinted,
        'version', e.content_version,
        'package', e.package_name,
        'at', e.at
    ) order by e.at), '[]'::jsonb)
    from (
        select * from public.academy_evidence
        where account = auth.uid() and pathway = p_pathway
        order by at desc
        limit 5000
    ) e;
$$;

/**
 * The competencies an account has verified on a pathway, by the rule at the
 * top of this file.
 */
create or replace function public.academy_verified(p_account uuid, p_pathway text) returns text[]
language sql stable security definer set search_path = '' as $$
    with latest as (
        select distinct on (e.competency, e.item) e.competency, e.item, e.kind, e.correct, e.at
        from public.academy_evidence e
        where e.account = p_account and e.pathway = p_pathway
          and e.kind <> 'artifact' and not e.hinted
        order by e.competency, e.item, e.at desc
    ),
    secure as (
        select l.competency, l.kind from latest l
        where l.correct and not exists (
            select 1 from public.academy_evidence w
            where w.account = p_account and w.pathway = p_pathway
              and w.competency = l.competency and w.item = l.item
              and not w.correct and w.at <= l.at and w.at > l.at - interval '10 minutes'
        )
    )
    select coalesce(array_agg(c.id order by c.position), '{}')
    from public.academy_competencies c
    where c.pathway = p_pathway
      and (select count(*) from secure s where s.competency = c.id and s.kind in ('concept', 'predict'))
          >= ceil(c.formative * 0.80)
      and (select count(*) from secure s where s.competency = c.id and s.kind in ('debug', 'transfer'))
          >= ceil(c.summative * 0.85)
      and (c.artifact is null or exists (
            select 1 from public.academy_evidence g
            join public.lab_apps a on a.account = g.account and a.package_name = g.package_name
            where g.account = p_account and g.pathway = p_pathway
              and g.competency = c.id and g.kind = 'artifact' and g.correct
      ));
$$;

-- ---------------------------------------------------------------- capstone

/**
 * How far an account's best app on the testing board is toward the capstone:
 * { listing, title, testers, days, needed_testers, needed_days, met }, or
 * { listing: null, ... } when it has no app there.
 *
 * A tester is another account that used the app for at least 32 seconds, on
 * the Board or in a mission; a day is a UTC day on which any of them did.
 */
create or replace function public.academy_capstone_of(p_account uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    with activity as (
        select l.id as listing, t.tester as person, (t.claimed_at at time zone 'utc')::date as day
        from public.listings l join public.tests t on t.listing_id = l.id
        where l.owner = p_account and l.channel = 'testing' and t.tester <> p_account
        union all
        select l.id, c.member, c.day
        from public.listings l join public.mission_checkins c on c.listing_id = l.id
        where l.owner = p_account and l.channel = 'testing' and c.member <> p_account and c.seconds >= 32
    ),
    per_app as (
        select l.id, l.title,
               count(distinct a.person) as testers,
               count(distinct a.day) as days
        from public.listings l
        left join activity a on a.listing = l.id
        where l.owner = p_account and l.channel = 'testing'
        group by l.id, l.title
    ),
    best as (
        select * from per_app
        order by (testers >= 12 and days >= 14) desc, least(testers, 12) + least(days, 14) desc, testers desc, id
        limit 1
    )
    select jsonb_build_object(
        'listing', b.id,
        'title', b.title,
        'testers', coalesce(b.testers, 0),
        'days', coalesce(b.days, 0),
        'needed_testers', 12,
        'needed_days', 14,
        'met', coalesce(b.testers >= 12 and b.days >= 14, false)
    )
    from (select 1) one left join best b on true;
$$;

create or replace function public.academy_capstone() returns jsonb
language sql stable security definer set search_path = '' as $$
    select case when auth.uid() is null then null else public.academy_capstone_of(auth.uid()) end;
$$;

-- ------------------------------------------------------------- credentials

create table if not exists public.academy_credentials (
    id uuid primary key default gen_random_uuid(),
    account uuid not null references public.profiles (id) on delete cascade,
    key text not null check (key in ('foundations', 'capstone', 'ship-to-play')),
    pathway text not null default 'ship-to-play',
    -- What a verifier is shown: the competencies or the app it covers.
    scope jsonb not null,
    rubric int not null default 1,
    content_version int not null default 1,
    -- The public half of the verification link. Unguessable, and nothing else.
    token text not null unique default replace(gen_random_uuid()::text, '-', ''),
    issued_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_reason text,
    unique (account, key)
);

alter table public.academy_credentials enable row level security;
revoke all on public.academy_credentials from anon, authenticated;

/**
 * Issues a credential if the caller has earned it, or returns the one already
 * issued. { ok: true, credential } or { ok: false, reason, missing? }:
 * signed_out, unknown, not_yet. missing lists the competencies still to
 * verify, plus 'capstone' when the closed test is still short.
 */
create or replace function public.academy_claim(p_key text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    me uuid := auth.uid();
    v_verified text[];
    v_needed text[];
    v_missing text[];
    v_capstone jsonb;
    v_scope jsonb;
    v_version int;
    v_row public.academy_credentials%rowtype;
begin
    if me is null then
        return jsonb_build_object('ok', false, 'reason', 'signed_out');
    end if;
    if p_key is null or p_key not in ('foundations', 'capstone', 'ship-to-play') then
        return jsonb_build_object('ok', false, 'reason', 'unknown');
    end if;

    select * into v_row from public.academy_credentials where account = me and key = p_key;
    if v_row.id is null then
        v_verified := public.academy_verified(me, 'ship-to-play');
        select coalesce(array_agg(id order by position), '{}') into v_needed
        from public.academy_competencies
        where pathway = 'ship-to-play' and (p_key = 'ship-to-play' or (p_key = 'foundations' and free));
        select coalesce(array_agg(n), '{}') into v_missing
        from unnest(v_needed) n where not n = any (v_verified);

        if p_key in ('capstone', 'ship-to-play') then
            v_capstone := public.academy_capstone_of(me);
            if not (v_capstone->>'met')::boolean then
                v_missing := v_missing || array['capstone'];
            end if;
        end if;
        if cardinality(v_missing) > 0 then
            return jsonb_build_object('ok', false, 'reason', 'not_yet', 'missing', to_jsonb(v_missing));
        end if;

        v_scope := jsonb_build_object('competencies', to_jsonb(v_needed));
        if v_capstone is not null then
            v_scope := v_scope || jsonb_build_object(
                'app', v_capstone->>'title',
                'testers', (v_capstone->>'testers')::int,
                'days', (v_capstone->>'days')::int);
        end if;
        select coalesce(max(content_version), 1) into v_version
        from public.academy_evidence where account = me and pathway = 'ship-to-play';

        insert into public.academy_credentials (account, key, scope, content_version)
        values (me, p_key, v_scope, v_version)
        on conflict (account, key) do nothing;
        select * into v_row from public.academy_credentials where account = me and key = p_key;
    end if;

    return jsonb_build_object('ok', true, 'credential', jsonb_build_object(
        'key', v_row.key,
        'token', v_row.token,
        'scope', v_row.scope,
        'rubric', v_row.rubric,
        'version', v_row.content_version,
        'issued_at', v_row.issued_at,
        'revoked_at', v_row.revoked_at
    ));
end $$;

/** The caller's credentials, issued first first. */
create or replace function public.my_credentials() returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'key', key,
        'token', token,
        'scope', scope,
        'rubric', rubric,
        'version', content_version,
        'issued_at', issued_at,
        'revoked_at', revoked_at
    ) order by issued_at), '[]'::jsonb)
    from public.academy_credentials
    where account = auth.uid();
$$;

/**
 * What a verification link shows, to anyone holding it: the credential, who
 * holds it, and whether it still stands. Null for a token that names nothing.
 */
create or replace function public.verify_credential(p_token text) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'key', c.key,
        'pathway', c.pathway,
        'holder', coalesce(nullif(p.display_name, ''), p.handle),
        'scope', c.scope,
        'rubric', c.rubric,
        'version', c.content_version,
        'issued_at', c.issued_at,
        'revoked_at', c.revoked_at,
        'valid', c.revoked_at is null
    )
    from public.academy_credentials c
    join public.profiles p on p.id = c.account
    where c.token = p_token and p_token ~ '^[0-9a-f]{32}$';
$$;

-- ------------------------------------------------------------------ grants

revoke all on function public.academy_open(uuid, text, text) from public, anon, authenticated;
revoke all on function public.academy_verified(uuid, text) from public, anon, authenticated;
revoke all on function public.academy_capstone_of(uuid) from public, anon, authenticated;
revoke all on function public.academy_record(jsonb) from public, anon;
revoke all on function public.academy_mine(text) from public, anon;
revoke all on function public.academy_capstone() from public, anon;
revoke all on function public.academy_claim(text) from public, anon;
revoke all on function public.my_credentials() from public, anon;
revoke all on function public.verify_credential(text) from public;
grant execute on function public.academy_record(jsonb) to authenticated;
grant execute on function public.academy_mine(text) to authenticated;
grant execute on function public.academy_capstone() to authenticated;
grant execute on function public.academy_claim(text) to authenticated;
grant execute on function public.my_credentials() to authenticated;
grant execute on function public.verify_credential(text) to anon, authenticated;
