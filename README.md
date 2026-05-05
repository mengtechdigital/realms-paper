# realms-paper

A PaperMC plugin that fuses **Towny's** social hierarchy (towns, mayors, residents) with **Factions'** land mechanics (chunk-claim by standing on it, raidable, power-based). No golden shovel, no two-corner painting, no WorldEdit selection — just walk to a chunk and `/realm claim`.

> Built for **Paper 1.21.x**, requires **Java 21+**.

---

## Highlights

- **Stand-and-claim.** `/realm claim` claims the chunk you're in. `/realm claim 3` (or 5 / 7) claims an N×N area centered on you, atomically.
- **Skyblock-style power growth.** Power = base + members + valuable-blocks-placed. Place diamond/netherite/beacon blocks in your claims → realm power grows → claim more land.
- **Factions raids.** Realms are raidable by default. Drain an enemy's power (TNT-mine their valuable blocks), then `/realm overclaim` weakened chunks after a 10-minute hold.
- **Op-only peaceful.** Realms can be made immune via an operator-blessed `peaceful` flag. Players can't toggle it themselves — stops mid-raid panic-flips.
- **Diplomacy.** `/realm ally` (bilateral, both confirm), `/realm enemy` (unilateral declaration), `/realm neutral` (drop a relation). Only enemies can be overclaimed.
- **Coarse protection.** Members build, outsiders don't. No granular flag matrix — Faction-style simplicity.
- **Per-realm flags.** `hostile-spawn`, `passive-spawn`, `mob-griefing`, `pvp` — toggleable by mayor/assistants. `peaceful` is op-only.
- **Territory feedback.** Title on chunk-cross (`Entering Westhold`), persistent action bar showing current chunk owner, optional boss bar with power fill, optional border-cross sound.
- **Show-claim particles.** `/realm showclaim` toggles a clientside particle outline of nearby claim borders, color-coded by relation.
- **Leaderboard.** `/realm top` ranks realms by power, chunks, members, or age.
- **Realm chat.** `/rc <msg>` for members-only chat.
- **LuckPerms prefix coexistence.** Realm tag layers ON TOP of admin role prefixes — never replaces them.
- **SQLite-backed persistence.** WAL + async writer + in-memory hot maps. Database file lives in the plugin data folder.
- **Reloadable.** `/realm reload` re-reads `config.yml`, `messages.yml`, and `power-blocks.yml`.

---

## Install

1. Drop `Realms.jar` into your server's `plugins/` folder.
2. Restart. It generates `plugins/Realms/config.yml`, `messages.yml`, `power-blocks.yml`, and `realms.db`.
3. Tweak to taste, then run `/realm reload`.

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/realm` (or `/r`, `/town`) | `realms.use` (true) | Show usage / help |
| `/realm create <name>` | `realms.use` | Found a realm on the chunk you're standing in |
| `/realm disband` | mayor only | Disband your realm (`confirm` required) |
| `/realm claim [N]` | member | Claim 1×1 (default) or N×N area, N odd, atomic |
| `/realm unclaim` | member | Release the chunk you're standing in |
| `/realm overclaim` | member | Take a weakened enemy chunk (after 10-min hold) |
| `/realm invite <player>` | mayor / assistant | Invite a player; they have 60s to join |
| `/realm join <realm>` | `realms.use` | Accept an invite |
| `/realm leave` | member | Leave your realm |
| `/realm kick <player>` | mayor / assistant | Remove a member |
| `/realm promote <player>` / `demote <player>` | mayor only | Adjust resident role |
| `/realm transfer <player>` | mayor only | Hand off mayorship |
| `/realm sethome` | mayor only | Set realm spawn (must stand in claim) |
| `/realm home` / `/realm spawn` | member | Teleport to realm spawn |
| `/realm info [name]` | true | Show realm summary |
| `/realm here` | true | Who owns the chunk you're in |
| `/realm who [name]` | true | List members of a realm |
| `/realm list` | true | List all realms |
| `/realm map` | true | ASCII mini-map of nearby chunks |
| `/realm power` | member | Power breakdown for your realm |
| `/realm top [page\|sort]` / `leaderboard` / `lb` | true | Leaderboard |
| `/realm flag [<name> <on\|off>]` | mayor / assistant | Per-realm flags |
| `/realm ally <realm>` / `enemy` / `neutral` | mayor / assistant | Diplomacy |
| `/realm allies` / `enemies` / `relations` | true | List relations |
| `/realm display ...` / `togglebar` | true | Per-player display preferences |
| `/realm showclaim [line\|wall\|corner\|off]` (`/realm sc`) | true | Toggle particle border outline |
| `/realmchat <msg>` (or `/rc`) | member | Realm-only chat |
| `/realm reload` | `realms.admin` (op) | Reload configs |
| `/realm admin peaceful <realm> <on\|off>` | `realms.admin` | Op-only peaceful toggle |
| `/realm admin delete <realm>` / `unclaim <realm>` / `bypass` | `realms.admin` | Force ops |

---

## Configuration

See [config.yml](realms/src/main/resources/config.yml), [messages.yml](realms/src/main/resources/messages.yml), and [power-blocks.yml](realms/src/main/resources/power-blocks.yml) for the annotated defaults. Highlights:

- `power.base` (30), `power.per-member-bonus` (20), `power.cost-per-chunk` (1) — the headline economy.
- `claim.max-diameter` (7), `claim.confirm-required-from-diameter` (5).
- `raid.enabled` (true), `raid.peaceful-default` (false), `raid.weakened-grace-seconds` (600), `raid.enemy-redeclare-cooldown-seconds` (3600).
- `explosions.tnt: vanilla` (raids work), creeper/end-crystal/wither/ghast → `cancel`.
- `flag-defaults.mob-griefing: false` (anti-grief by default).
- `prefix-mode: chat-event` (composes with admin LuckPerms prefix).
- `display.action-bar.enabled-default: true`, `display.boss-bar.enabled-default: false`.
- `display.see-claims.radius: 5`, `max-concurrent-users: 50`, `tps-threshold: 18.0`.
- Color palette under `colors:` (wilderness gray, own green, ally aqua, enemy red, neutral yellow, peaceful gold).

---

## Build

```bash
cd realms
./mvnw -DskipTests package
```

Produces `realms/target/Realms.jar`.

The repo bundles `apache-maven-3.9.9/` so `mvnw` works without a system Maven install (not committed — download separately or use your own Maven).
