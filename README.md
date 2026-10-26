# Forge Companion Server (v0.2)

Drop-in replacement for the `NerdzGaming/companion-server` repo. Same contract the website already uses:

- `GET /` -> "Forge Companion Server is active"
- `POST /match/create` `{ playerDeckDck, aiDeckDck, format? }` -> `{ matchId, status }`
- `wss://.../match/{matchId}` -> `PING`/`PONG`, plus live `STATUS`, `STATE`, `GAME_OVER` messages

## What's new
Connecting to a match now starts a real Forge game in its own process and streams the board:
turn, phase, active player, life, hand/library counts, battlefield (tapped, power/toughness),
graveyard, exile, command zone and the stack. Your own hand is named; the opponent's isn't.

## Not yet
Your seat is played by Forge's AI for now. Player actions (pass, cast, attack) are acknowledged
but not applied — that's the next milestone (a human controller that turns Forge prompts into
socket messages).

## Deploy (Render)
1. Replace the repo contents with these files (delete the old Javalin skeleton, keep `.git`).
2. Commit and push to `main` — Render auto-deploys. First build compiles Forge: 10-20 minutes.
3. **Memory:** a Forge game needs about 1.5 GB. Render's free 512 MB instance will crash;
   use a Standard (2 GB) instance or larger. Optional env vars: `WORKER_HEAP` (default 1200m),
   `MAX_MATCHES` (default 1).

Licence: Forge is GPL-3.0; its licence ships in the image as FORGE-LICENSE.
