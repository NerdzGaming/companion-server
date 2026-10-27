# Forge Companion Server v0.8

v0.8: London mulligan (always draw seven, then you pick cards for the bottom via a BOTTOM prompt answered with {cards:[ids]}).


Drop-in source update for NerdzGaming/companion-server. Upload the contents of this
folder to the repository root, not a nested companion-server folder.

## Install

1. Unzip the download.
2. Upload Dockerfile, pom.xml, README.md, fixtures/ and src/ into the repository,
   replacing matching paths. Preserve your .git folder and hosting settings.
3. Commit to main and wait for Render to finish deploying.
4. Open Playtest → Tools → Rules Mode and start a NEW match. Running workers use
   the previous version until they are stopped.

Changed server source: HumanBridgeController.java, MatchWorker.java; new source:
PublicActionLog.java. pom.xml and Dockerfile now agree on version 0.7.0.

## Changes

- Bounded, chronological Action log (latest 200 entries), with stable IDs and
  turn/phase stamps. Captures public casts, activations, resolutions, land plays,
  tapping, combat declarations, card damage, life, draws and public zone changes.
- Each player's current floating mana is exposed as display-only color/counts.
- Priority explicitly waits for Pass even when no ordinary spells can be cast.
  Legal mana abilities appear as actions; using one retains priority and does
  not advance to the next step. This prevents silent auto-passing from making
  a pool appear to empty immediately after a spell.
- Pay prompts include the existing pool. The website's Use pool submits no
  extra sources; the original Forge payment path spends floating mana first.
- Actual casting success is returned instead of unconditionally reporting success.
- Human target candidates include stack spells/abilities. Activating player is
  assigned before enumeration. AI strategic target checks no longer pre-filter
  human actions. Targets are chosen one at a time, rechecking dependencies;
  optional targeting offers Choose none.
- Public face-down cards use an anonymous name. Action logs do not serialize
  raw engine log text, target descriptions, private prompts, hands or libraries.

## Important limits and diagnosis

The pinned Forge cast helper does NOT clear the mana pool. No pool-clearing code
was removed, and mana is NOT recreated by color (which would lose restrictions).
Forge still empties mana at step/phase boundaries unless a rule preserves it.
If mana disappears without a phase change, use the Action log and report the
card, pool before/after and phase; that scenario still needs live reproduction.

This remains an AI-backed human bridge, not Forge's full desktop human controller.
Use pool and Tap selected & pay allow Forge to auto-tap any unpaid shortfall.
Non-mana additional costs, some mana-color/X choices, divided targeting, complex
modal/additional ability cases and other unoverridden decisions can still use AI.
The printed Pay cost is informational; Forge applies actual cost adjustments.
Unanswered prompts default to AI after PROMPT_TIMEOUT_MIN (default 5 minutes).
No general Cancel casting control is offered. The v0.7 cast adapter consumes
target failure before payment and uses Forge rollback for rejected spells.
Complex additional-cost rollback and splice behavior still need live testing.
Divided damage/counter allocation is rejected with a notice rather than cast
with incomplete targets.

The public Action log is a structured subset, not a complete rules-engine trace.
The server retains its current unauthenticated UUID-based proof-of-concept match
access; do not treat this package as authenticated multiplayer or raise capacity
for public multi-user play without a separate access-control upgrade.

## Verify after deployment

- Play a mana source producing multiple mana, activate it from priority, then
  cast a cheaper spell. Remaining mana must stay visible in the SAME phase.
- Select Use pool and check no extra source taps if the pool covers the cost.
- Pass priority/advance phases and verify normal mana-emptying rules still apply.
- Cast removal/activate a targeted ability: choose legal targets yourself.
- Cast a counterspell with a legal spell on the stack: it must appear as a target.
- Test an "up to one target" ability and choose none.
- Confirm Action log records casts/resolution, tapping, life and combat, and
  never reveals opponent hand/library or face-down card names.
- Leave and immediately start a new match; prior cleanup behavior is preserved.

Live engine snapshots no longer run on a background polling thread: snapshots
are emitted on the game thread from audited public events and before prompts.
This avoids showing temporary pool removals during affordability probes.

Website display tests passed against a controlled v0.7 message fixture. The
package APIs were checked against the exact pinned Forge source. The full Docker
build and these real-game checks have NOT been run here: deployment and live
card-specific behavior remain to be verified on Render.

## Runtime

Pinned Forge commit: a198319ac46debbc5dc0b3e6fec284360e511930.
One isolated JVM per match. Forge's dependencies stay outside the Javalin server
classpath to preserve the Jetty isolation fix. Defaults: WORKER_HEAP=1200m,
MAX_MATCHES=1; use a host with adequate memory (approximately 2 GB or greater).

Contract: GET /, POST /match/create with playerDeckDck/aiDeckDck and optional
Commander or Constructed format; WebSocket /match/{id} carries STATE/PROMPT and
accepts ACT/PING/LEAVE. v0.7 adds STATE.actionLog and player/opponent.manaPool.

Licence: Forge is GPL-3.0; the pinned upstream licence is included in the image
as FORGE-LICENSE. This package builds the engine and packages card scripts/assets;
artwork is loaded by the website separately.
