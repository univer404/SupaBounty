# SupaBounty

A configurable Paper/Purpur bounty plugin for Minecraft 1.21.11. Uses Vault for economy integration and YAML files for persistent storage.

## Features
- `/bounty` and `/bounties` open a paginated bounty GUI.
- `/bounty <player>` checks a player's active bounty.
- `/bounty add <player> <amount>` opens a confirmation GUI before charging the player.
- Amount suffixes supported: `k`, `m`, `b`, and `t` (for example, `10k`).
- Killers receive the target's bounty when they kill that player.
- Sort by bounty amount or recently placed.
- UUID-based bounty storage in `plugins/SupaBounty/bounties.yml`.
- Configurable messages, GUI names/lore, minimum and maximum bounty, currency symbol, sounds, and behavior.
- Supports legacy `&` colors and hex colors in the form `<##RRGGBB>`.

## Requirements
- Paper/Purpur 1.21.11
- Java 21
- Vault and a Vault-compatible economy plugin

## Commands
- `/bounty` — open the bounty list.
- `/bounties` — alias for the bounty list.
- `/bounty <player>` — view a player's bounty.
- `/bounty add <player> <amount>` — place a bounty.
- `/bounty reload` — reload configuration (requires `supabounty.admin`).

## Build
Run `mvn clean package` with Java 21. The release JAR is written to `target/SupaBounty-1.0.0.jar`. GitHub Actions also builds the project and publishes the JAR as a workflow artifact.

## Configuration
Edit `plugins/SupaBounty/config.yml`. Bounties are saved separately in `plugins/SupaBounty/bounties.yml`.
