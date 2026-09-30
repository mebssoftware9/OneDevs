-- Mission names: 500 of them, drawn at random.
--
-- A new mission used to take the next of 24 names in order, and after the
-- 24th the same names again with a number. Now each new mission draws a name
-- at random from the 500 below that has been used the fewest times, so no
-- name comes back until every one has had its turn, and the order cannot be
-- guessed. A name on its second round carries the round: "Mission Kestrel 2".
--
-- Missions that already exist keep their names.

create table if not exists public.mission_names (
    name text primary key check (name ~ '^[A-Z][A-Za-z]+$'),
    uses int not null default 0
);

alter table public.mission_names enable row level security;
revoke all on public.mission_names from anon, authenticated;

insert into public.mission_names (name) values
    ('Aldebaran'), ('Altair'), ('Andromeda'), ('Antares'), ('Aquila'), ('Arcturus'),
    ('Aries'), ('Auriga'), ('Bellatrix'), ('Betelgeuse'), ('Canopus'), ('Capella'),
    ('Carina'), ('Cassiopeia'), ('Castor'), ('Centaurus'), ('Cepheus'), ('Cetus'),
    ('Columba'), ('Corvus'), ('Cygnus'), ('Deneb'), ('Draco'), ('Electra'),
    ('Eridanus'), ('Fomalhaut'), ('Gemini'), ('Hadar'), ('Hydra'), ('Lyra'), ('Maia'),
    ('Merope'), ('Mira'), ('Mizar'), ('Nova'), ('Orion'), ('Pegasus'), ('Perseus'),
    ('Phoenix'), ('Pleiades'), ('Pollux'), ('Polaris'), ('Procyon'), ('Pulsar'),
    ('Quasar'), ('Regulus'), ('Rigel'), ('Sagitta'), ('Scorpius'), ('Sirius'),
    ('Spica'), ('Taurus'), ('Vega'), ('Vela'), ('Zenith'), ('Nebula'), ('Comet'),
    ('Meteor'), ('Eclipse'), ('Solstice'), ('Equinox'), ('Aurora'), ('Corona'),
    ('Perihelion'), ('Aphelion'), ('Parallax'), ('Orbit'), ('Halo'), ('Meridian'),
    ('Lodestar'), ('Albatross'), ('Avocet'), ('Bittern'), ('Bluejay'), ('Bobolink'),
    ('Bunting'), ('Buzzard'), ('Canary'), ('Cardinal'), ('Cormorant'), ('Crane'),
    ('Curlew'), ('Dipper'), ('Dove'), ('Dunlin'), ('Egret'), ('Eider'), ('Falcon'),
    ('Finch'), ('Flamingo'), ('Gannet'), ('Goldcrest'), ('Goshawk'), ('Grebe'),
    ('Harrier'), ('Hawk'), ('Heron'), ('Hobby'), ('Hoopoe'), ('Ibis'), ('Jacana'),
    ('Kestrel'), ('Kingfisher'), ('Kite'), ('Kiwi'), ('Lapwing'), ('Lark'), ('Linnet'),
    ('Loon'), ('Magpie'), ('Mallard'), ('Martin'), ('Merlin'), ('Nightjar'),
    ('Nuthatch'), ('Oriole'), ('Osprey'), ('Owl'), ('Pelican'), ('Peregrine'),
    ('Petrel'), ('Plover'), ('Puffin'), ('Quail'), ('Raven'), ('Redstart'), ('Robin'),
    ('Sandpiper'), ('Shrike'), ('Skylark'), ('Sparrow'), ('Starling'), ('Swallow'),
    ('Swift'), ('Tanager'), ('Tern'), ('Thrush'), ('Toucan'), ('Wagtail'), ('Warbler'),
    ('Waxwing'), ('Wren'), ('Agate'), ('Alabaster'), ('Amber'), ('Amethyst'),
    ('Aquamarine'), ('Basalt'), ('Beryl'), ('Carnelian'), ('Citrine'), ('Cobalt'),
    ('Copper'), ('Coral'), ('Diamond'), ('Emerald'), ('Flint'), ('Garnet'),
    ('Granite'), ('Graphite'), ('Heliodor'), ('Iolite'), ('Iridium'), ('Jade'),
    ('Jasper'), ('Jet'), ('Kyanite'), ('Lapis'), ('Malachite'), ('Marble'), ('Mica'),
    ('Moonstone'), ('Obsidian'), ('Onyx'), ('Opal'), ('Pearl'), ('Peridot'),
    ('Platinum'), ('Pyrite'), ('Quartz'), ('Ruby'), ('Sapphire'), ('Slate'),
    ('Spinel'), ('Sunstone'), ('Tanzanite'), ('Titanium'), ('Topaz'), ('Tourmaline'),
    ('Turquoise'), ('Zircon'), ('Bismuth'), ('Cinnabar'), ('Chrome'), ('Acacia'),
    ('Alder'), ('Almond'), ('Aspen'), ('Balsam'), ('Bamboo'), ('Baobab'), ('Beech'),
    ('Birch'), ('Blackthorn'), ('Boxwood'), ('Cedar'), ('Chestnut'), ('Cypress'),
    ('Elder'), ('Elm'), ('Eucalyptus'), ('Fir'), ('Ginkgo'), ('Hawthorn'), ('Hazel'),
    ('Hemlock'), ('Hickory'), ('Holly'), ('Hornbeam'), ('Juniper'), ('Larch'),
    ('Laurel'), ('Linden'), ('Magnolia'), ('Mangrove'), ('Maple'), ('Myrtle'), ('Oak'),
    ('Olive'), ('Palm'), ('Pine'), ('Poplar'), ('Redwood'), ('Rowan'), ('Sequoia'),
    ('Spruce'), ('Sycamore'), ('Tamarack'), ('Teak'), ('Walnut'), ('Willow'), ('Yew'),
    ('Heather'), ('Fern'), ('Clover'), ('Thistle'), ('Lavender'), ('Sage'),
    ('Saffron'), ('Jasmine'), ('Lotus'), ('Orchid'), ('Iris'), ('Aster'), ('Dahlia'),
    ('Foxglove'), ('Marigold'), ('Primrose'), ('Tulip'), ('Violet'), ('Zinnia'),
    ('Bluebell'), ('Camellia'), ('Peony'), ('Poppy'), ('Breeze'), ('Cirrus'),
    ('Cloudburst'), ('Cumulus'), ('Cyclone'), ('Dewfall'), ('Drizzle'), ('Frost'),
    ('Gale'), ('Hail'), ('Horizon'), ('Monsoon'), ('Mistral'), ('Nimbus'),
    ('Rainfall'), ('Sirocco'), ('Squall'), ('Stratus'), ('Sunburst'), ('Tempest'),
    ('Thunder'), ('Tornado'), ('Typhoon'), ('Zephyr'), ('Chinook'), ('Lightning'),
    ('Rainbow'), ('Twilight'), ('Daybreak'), ('Dawn'), ('Dusk'), ('Midnight'),
    ('Noon'), ('Sunrise'), ('Sunset'), ('Starlight'), ('Moonrise'), ('Afterglow'),
    ('Solace'), ('Alpine'), ('Atlas'), ('Andes'), ('Canyon'), ('Cascade'), ('Delta'),
    ('Dune'), ('Everest'), ('Fjord'), ('Glacier'), ('Highland'), ('Island'),
    ('Kilimanjaro'), ('Lagoon'), ('Mesa'), ('Oasis'), ('Pampas'), ('Plateau'),
    ('Prairie'), ('Rapids'), ('Reef'), ('Ridge'), ('Savanna'), ('Sierra'), ('Summit'),
    ('Tundra'), ('Valley'), ('Volcano'), ('Waterfall'), ('Archipelago'), ('Atoll'),
    ('Bayou'), ('Butte'), ('Cove'), ('Crater'), ('Estuary'), ('Geyser'), ('Harbor'),
    ('Headland'), ('Isthmus'), ('Marsh'), ('Moor'), ('Peninsula'), ('Steppe'),
    ('Strait'), ('Tributary'), ('Ardent'), ('Bellwether'), ('Cinder'), ('Dovetail'),
    ('Ember'), ('Halcyon'), ('Indigo'), ('Kindred'), ('Lumen'), ('Quill'), ('Sable'),
    ('Umbra'), ('Vesper'), ('Valor'), ('Vigil'), ('Beacon'), ('Bastion'), ('Catalyst'),
    ('Compass'), ('Crescent'), ('Endeavor'), ('Fortitude'), ('Genesis'), ('Harmony'),
    ('Impulse'), ('Insight'), ('Keystone'), ('Legacy'), ('Liberty'), ('Momentum'),
    ('Odyssey'), ('Paragon'), ('Pinnacle'), ('Pioneer'), ('Prism'), ('Quest'),
    ('Radiant'), ('Resolve'), ('Sentinel'), ('Serenity'), ('Spark'), ('Spectrum'),
    ('Tenacity'), ('Triumph'), ('Unity'), ('Vanguard'), ('Venture'), ('Vertex'),
    ('Voyager'), ('Wander'), ('Wayfarer'), ('Zeal'), ('Anthem'), ('Ballad'),
    ('Cadence'), ('Chorus'), ('Harmonic'), ('Rhapsody'), ('Sonata'), ('Tempo'),
    ('Azure'), ('Burgundy'), ('Cerulean'), ('Crimson'), ('Cyan'), ('Ebony'),
    ('Fuchsia'), ('Gold'), ('Ivory'), ('Lilac'), ('Magenta'), ('Maroon'), ('Mauve'),
    ('Ochre'), ('Periwinkle'), ('Russet'), ('Scarlet'), ('Sepia'), ('Sienna'),
    ('Silver'), ('Tangerine'), ('Teal'), ('Ultramarine'), ('Vermilion'), ('Viridian'),
    ('Badger'), ('Beaver'), ('Bison'), ('Caribou'), ('Cheetah'), ('Cougar'),
    ('Coyote'), ('Dolphin'), ('Elk'), ('Ermine'), ('Fox'), ('Gazelle'), ('Gecko'),
    ('Ibex'), ('Jaguar'), ('Koala'), ('Leopard'), ('Lynx'), ('Manatee'), ('Marten'),
    ('Meerkat'), ('Moose'), ('Narwhal'), ('Ocelot'), ('Orca'), ('Otter'), ('Panda'),
    ('Panther'), ('Puma'), ('Seal'), ('Stag'), ('Tiger'), ('Walrus'), ('Wolf'),
    ('Wolverine'), ('Yak'), ('Zebra'), ('Mustang'), ('Stallion'), ('Anchor'),
    ('Barnacle'), ('Current'), ('Driftwood'), ('Harpoon'), ('Kelp'), ('Lighthouse'),
    ('Mariner'), ('Nautilus'), ('Riptide'), ('Seafarer'), ('Seashell'), ('Tidewater'),
    ('Undertow'), ('Voyage'), ('Wavecrest'), ('Starfish'), ('Seahorse'), ('Stingray'),
    ('Marlin'), ('Lantern'), ('Sundial'), ('Firefly'), ('Barracuda'), ('Sailfish'),
    ('Swordfish')
on conflict (name) do nothing;

-- The 24 names already given out count as used once, so the next missions
-- draw from names nobody has seen yet.
update public.mission_names n set uses = 1
where n.uses = 0
  and exists (select 1 from public.mission_groups g where g.name like 'Mission ' || n.name || '%');

/**
 * Draws a name for a new mission: a random one among those used least, and
 * marks it used. Locks the row it takes, so two missions opened at once
 * cannot draw the same name.
 */
create or replace function public.draw_mission_name() returns text
language plpgsql security definer set search_path = '' as $$
declare
    v_name text;
    v_uses int;
begin
    select n.name, n.uses into v_name, v_uses
    from public.mission_names n
    where n.uses = (select min(uses) from public.mission_names)
    order by random()
    limit 1
    for update skip locked;

    if v_name is null then
        -- Every least-used name is being drawn this instant; any will do.
        select n.name, n.uses into v_name, v_uses
        from public.mission_names n order by random() limit 1 for update;
    end if;

    update public.mission_names set uses = uses + 1 where name = v_name;
    return 'Mission ' || v_name || case when v_uses > 0 then ' ' || (v_uses + 1)::text else '' end;
end $$;

revoke all on function public.draw_mission_name() from public, anon, authenticated;

/** The group that is recruiting, opening a new one, with a drawn name, if none is. */
create or replace function public.recruiting_group() returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_id uuid;
begin
    select id into v_id from public.mission_groups where state = 'recruiting';
    if v_id is not null then return v_id; end if;

    insert into public.mission_groups (number, name)
    values (nextval('public.mission_group_number'), public.draw_mission_name())
    on conflict do nothing
    returning id into v_id;

    -- Another caller opened it first; the unique index saw to that.
    if v_id is null then
        select id into v_id from public.mission_groups where state = 'recruiting';
    end if;
    return v_id;
end;
$$;

revoke all on function public.recruiting_group() from public, anon, authenticated;
