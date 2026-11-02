# Companion Server v0.29


## v0.29: rejoin a game after refresh or leaving — a disconnected game stays open 5 minutes (yields after 1 minute if another player needs the table); a returning browser replaces its stale socket.
## v0.28: readable zone-change log lines for every card, real draws vs tutors, shuffles

## v0.27: MULTIPICK prompt for "one card of each type" effects (Atraxa); each pick logged

## v0.26: battlefield creatures report firstStrike (first or double strike)

## v0.25: mulligan prompt says who goes first (onThePlay, firstPlayer)



## v0.24: stuck "server busy" recovery
- Matches whose browser goes silent for 75s (closed tab, refresh, dropped connection) are reclaimed.
- Ending a match waits for its worker to fully exit before a new one starts.
- A new match waits up to 30s for a free table instead of failing immediately.
## v0.23: undo, targets and mana sources
- Undo keeps its checkpoint across priority re-prompts, so the AI-match Undo button stays usable after casting or tapping.
- Target and mana-source choices include tapped state and attached cards (e.g. Utopia Sprawl) so identical lands are distinguishable.
- Mana sources report produced mana, extra activation costs (Nykthos {2}) and whether the source is the spell's own target.
- Auto-pay avoids tapping the spell's own battlefield targets when another way to pay exists.


## v0.22: X and Phyrexian payment
- Human spells and activated mana/non-mana abilities announce X before targets and costs, matching the pinned PlaySpellAbility lifecycle. Copies and fixed X definitions are preserved.
- X uses engine announcement bounds, not an unreliable mana estimate or artificial 99 limit. Forge validates actual affordability at payment; enter large values directly in the website number picker.
- The mana window runs inside the engine payment callback after cost adjustments and X expansion, including pool-only and life-only casts with no battlefield sources.
- Each distinct Phyrexian symbol offers a life counter. Selected symbols cost exactly two life each; declined colours stay payable with mana. Auto-pay retains the explicit life selection and cannot choose additional life payment.
- No life is charged when residual mana payment fails. Activated mana remains subject to the existing engine behavior on failed casts.
- Preserves transport protection, undo, human choices and the 15-minute inactivity default. Keep FORGE_TRANSPORT_SECRET unchanged.

Deploy on Render, publish the matching website and start a NEW match. Test Fireball/Torment at X=0 and X=4, an activated X ability, Mutagenic Growth with no lands and two life, mixed colour Phyrexian choices, insufficient life, and a spell with cost reductions/tax. Full pinned compilation and real-game verification are still required after deployment.

## v0.18: colorless mana, library tops and human mana abilities
- Colorless pool counts use ManaAtom.COLORLESS, not the card-characteristic MagicColor.COLORLESS; Sol Ring's two mana can now be displayed without changing Forge payment rules.
- Snapshots expose only the library top authorized for the actual website viewer. Removing look permission, changing the top or shuffling recomputes visibility; hidden identities are not serialized.
- Own visible library tops enter normal engine ability enumeration. Courser only permits legal lands; Future Sight permits engine-legal spells. Visibility alone never grants casting permission, and hidden libraries are never scanned.
- Both explicit priority mana activations and selected payment sources pay through HumanCostDecision and resolve outside the stack. Discard/exile/sacrifice costs no longer bypass these prompts through ComputerUtil.playNoStack.
- Matching website double-click and menus offer every engine-legal ability, including non-tap and loyalty options; multiple abilities ask which one. No manual tapping bypasses the engine.
- Preserves v0.16 transport protection and v0.17 attack/loyalty/surveil support. Keep FORGE_TRANSPORT_SECRET unchanged.

Deploy this package, publish the matching website and start a NEW match. Validate Sol Ring's two colorless mana, a cheaper spell leaving mana in the same step, Courser with land/nonland tops, Future Sight, permission loss/shuffle, loyalty and sacrifice-for-mana choices. Full pinned Forge compilation and live card-specific matches remain separate from source/API and website checks. Auto-tap, rare inherited choices and prompt-timeout fallbacks remain beta limitations.

## v0.17: planeswalkers and surveil
- Attack choices include the engine's actual player, planeswalker and battle defenders, separately for each attacker. Invalid attacks re-prompt and engine attack requirements still apply.
- Face-up battlefield planeswalkers stream numeric current loyalty, including changes after ability costs or damage. Forge still removes zero-loyalty planeswalkers; the website does not alter counters or state-based actions.
- Surveil shows the human's looked-at card art, lets them choose graveyard cards and top-first ordering, and returns the exact partition to Forge. Hidden cards never enter public snapshots/logs. Unanswered surveil keeps the original order instead of AI milling cards.
- Keeps all v0.16 connection protection. Keep your existing FORGE_TRANSPORT_SECRET unchanged.

Deploy this package on Render, publish the matching website, then start a NEW match. Older workers will not gain these changes.
Verification: pure human-choice and transport checks run separately from a full pinned Forge build; real planeswalker/surveil matches must be verified after deployment.

## Protect the Render service

1. In the Lovable secure secret form, click Generate for FORGE_TRANSPORT_SECRET, copy the value, and save.
2. Render dashboard → your companion service → Environment → Add environment variable.
   Set the name to FORGE_TRANSPORT_SECRET and paste the SAME value. Never use the visitor beta password here.
3. Replace the repository's matching source files with this package's contents, preserving hosting settings and repository metadata; deploy on Render.
4. Wait for Render to report a successful deploy. With no valid secret, this version intentionally refuses to start.
5. Publish the updated website in Lovable; the public website needs the new connection code and secret.
6. Start a NEW opponent match through the website after entering the visitor beta password.

The public GET / health check stays available but cannot create, read or control games.
POST /match/create requires a server-held Bearer credential. Its no-store response returns a unique opaque ticket for that match only.
WebSocket upgrades require that ticket, expire five minutes after creation, and allow two minutes to reconnect after disconnect.
Only one socket can control a match at a time. LEAVE deletes the match and invalidates its ticket. Keep access logs free of query strings because tickets are bearer credentials.

Verify: unauthenticated HTTP creation returns 401; missing/wrong/expired socket tickets cannot connect; unlocked website creation, heartbeat, reconnection and Leave still work. The transport layer was tested locally; full Forge/Render deployment and real matches must still be checked.

## v0.15 changes
- Alternative/additional casting variants are listed using the pinned engine APIs; optional kicker/buyback costs ask before casting.
- Normal casting now uses the human cost decision handler instead of bypassing it with AiCostDecision. Discard/exile/sacrifice selections reach the browser.
- Shock lands ask whether to pay 2 life to enter untapped through the engine's UnlessCost payment hook. Life is deducted only by engine payment.
- Human controller reports isAI false; tutors can use Forge's human multi-select path.
- Public face-up token snapshots include token and typeLine so website art searches can distinguish tokens from same-name cards. Face-down identities stay masked.
- Website loading arcade and Join game gate are presentation-only; combat substep alerts use existing streamed engine phases.

Verification: website regression tests and controlled browser checks are separate from companion validation. The full Java/Docker build and real card-specific matches still need verification after deployment.

v0.9: London mulligan (always draw seven, then you pick cards for the bottom via a BOTTOM prompt answered with {cards:[ids]}).


Drop-in source update for NerdzGaming/companion-server. Upload the contents of this
folder to the repository root, not a nested companion-server folder.

## Install

1. Unzip the download.
2. Upload Dockerfile, pom.xml, README.md, fixtures/ and src/ into the repository,
   replacing matching paths. Preserve your .git folder and hosting settings.
3. Commit to main and wait for Render to finish deploying.
4. Open Playtest → Game & opponents → AI Opponent and start a NEW match. Running workers use
   the previous version until they are stopped.

Changed since v0.9: PublicActionLog.java identifies the public drawing player.
pom.xml and Dockerfile agree on version 0.10.0. The website bundles consecutive
draws by player, raw engine turn and phase (for example, Player draws 7 cards).
Separate players are never bundled together. Website turn tags and turn-begin
messages display paired rounds: 1, 1, 2, 2. Raw engine turns remain unchanged
so priority passing and rules timing are preserved.

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
Unanswered prompts default to AI after PROMPT_TIMEOUT_MIN (default 15 minutes).
No general Cancel casting control is offered. The v0.7 cast adapter consumes
target failure before payment and uses Forge rollback for rejected spells.
Complex additional-cost rollback and splice behavior still need live testing.
Divided damage/counter allocation is rejected with a notice rather than cast
with incomplete targets.

The public Action log is a structured subset, not a complete rules-engine trace.
Match creation requires FORGE_TRANSPORT_SECRET. Browser sockets require a match-scoped ticket. This is beta access, not per-customer multiplayer authorization; do not raise capacity for public multiplayer without per-seat ownership checks.

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

## v0.9
- London mulligan: every mulligan shows a fresh seven; you choose cards to bottom only once, after you keep (CR 103.5).

## v0.19
- Undo: before each of your priority prompts the engine checkpoints the game (Forge UndoManager). Answering a priority prompt with `{"undo": true}` restores the previous checkpoint; draws, reveals, randomness and opponent actions remain engine barriers. Prompts include `canUndo`.

## v0.21
- Default prompt timeout raised to 15 minutes (PROMPT_TIMEOUT_MIN still overrides).

## v0.20
- Triggered abilities (enters-the-battlefield and others) ask you for targets and "you may" confirmations instead of Forge AI choosing.
- Return-a-permanent costs (e.g. Quirion Ranger) let you pick which permanent to return.
- X costs on spells and abilities ask for a value (capped by available mana).
