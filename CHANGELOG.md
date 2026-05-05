# Changelog

All notable changes to this project are documented here.

## [1.0.0] — 2026-05-05

Initial release. Towny-flavoured towns with Factions-flavoured land claiming, raid mechanics, power growth via valuable blocks, and diplomacy.

### Added

- **Stand-and-claim land system.** `/realm create <name>`, `/realm claim [N]`
  (1×1 default, N×N atomic with adjacency check), `/realm unclaim`,
  `/realm overclaim`. No golden shovel, no two-corner painting.
- **Skyblock-style power growth.** Realm power = base + members × bonus +
  ledger of valuable placed blocks (diamond, netherite, beacon, conduit,
  etc — fully tunable in `power-blocks.yml`). TNT mining valuable blocks
  drains the victim's power; once `claim_cost > capacity` the realm is
  weakened and outermost chunks become overclaimable by enemies after a
  10-minute hold.
- **Op-only peaceful flag.** Newly created realms are raidable; an
  operator can `/realm admin peaceful <realm> on` to bless a realm with
  immunity. Players can't toggle it themselves — stops mid-raid panic
  flips.
- **Diplomacy.** `/realm ally <realm>` (bilateral, both sides confirm),
  `/realm enemy <realm>` (unilateral war), `/realm neutral <realm>`
  (drops ally OR enemy; arms a redeclare cooldown for enemy). Only
  enemies can be overclaimed. `/realm allies`, `/realm enemies`,
  `/realm relations`.
- **Coarse outsider protection.** Members build, outsiders don't —
  block break/place/ignite, container/door/button/lever/plate/bed
  interactions, buckets, paintings/item-frames, armor stands, boats,
  minecarts, fishing-rod yanks. Allies get door/button/plate access
  but stay locked out of containers and workstations.
- **Per-realm flags.** `hostile-spawn`, `passive-spawn`, `mob-griefing`,
  `pvp` — toggleable by mayor/assistant via `/realm flag`. Default
  `mob-griefing: false` blocks endermen/silverfish/ravager/wither
  block changes and zombie door-breaking.
- **Explosion policy.** `tnt: vanilla` (raids work),
  `creeper/end-crystal/respawn-anchor/bed/wither/ghast: cancel`.
  Peaceful realms force CANCEL for ALL sources regardless of policy.
  Power-block ledger debits before tracked blocks break — Factions raid
  drain.
- **Territory feedback.** Title on chunk-cross (`Entering <Realm>` /
  `Wilderness`) with mayor + chunk-count subtitle. Persistent action
  bar showing current chunk owner, color-coded by relation. Opt-in
  boss bar with claim-cost fill ratio. Optional border-cross sound.
- **`/realm showclaim`** toggleable particle outline of nearby claim
  borders — `line` / `wall` / `corner` modes. Clientside particles
  only. Auto-pauses when server TPS drops below threshold.
- **`/realm map`.** 11×11 ASCII grid colored by relation.
- **Leaderboard.** `/realm top [power|members|chunks|age] [page]`,
  aliased `/realm leaderboard` and `/realm lb`.
- **Realm chat.** `/rc <msg>` for members-only chat. Optional
  ally-overhear flag.
- **Home + spawn.** `/realm sethome` (mayor, must stand in own claim),
  `/realm home` / `/realm spawn` (warmup + cooldown, cancel-on-move
  and cancel-on-damage). Home auto-clears if its chunk is unclaimed
  or overclaimed.
- **LuckPerms prefix coexistence.** Realm tag layers ON TOP of admin
  role prefixes — never replaces them. Default `prefix-mode:
  chat-event` composes with admin prefix in `AsyncPlayerChatEvent`;
  `luckperms-meta` mode publishes a transient PrefixNode at low
  weight (default 100) for chat plugins that template both weights.
- **Admin tools.** `/realm admin peaceful <realm> <on|off>`,
  `/realm admin bypass` (per-player toggle), `/realm reload`.
- **SQLite-backed persistence.** WAL + async writer thread + in-memory
  hot caches. Tables: realms, residents, claims, power_ledger, flags,
  relations, relation_cooldowns, display_prefs.
- **Permissions.** `realms.use` (default true), `realms.admin`
  (default op).

### Build

- Java 21, Paper API 1.21.1, Maven.
- Shaded `org.xerial:sqlite-jdbc:3.46.1.0` is **not** relocated — its
  bundled native lib has hardcoded `Java_org_sqlite_*` JNI symbols.
  Each plugin classloader loads its own copy.
- Soft-dep `net.luckperms:api:5.4`.
- `mvnw` wrapper bundled. `apache-maven-3.9.9/` is gitignored —
  download per machine or use a system Maven.
