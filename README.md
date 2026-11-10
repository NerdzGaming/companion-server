# Companion Server v0.57

## v0.57: cast-log build fix and guarded loop recording
- Resolve the full spell ability by the cast event's stack-instance ID before reading X; the event itself supplies view objects, not SpellAbility.
- Record one manual cycle at empty-stack human priority, finish at the same boundary, then replay 1–1000 additional repetitions. Decisions stay in the isolated match worker, with exact legal source/target identities and remapped option indexes.
- Preserve every engine action and opponent priority window. Stop on an opponent cast/activation/trigger, a changed/missing/ambiguous choice, turn/phase change, unsupported decision, timeout, or Stop loop. New token/card IDs and non-deterministic choices need manual intervention; this is not a freeform infinite-state shortcut.
- Recording holds Full control on the website. Start, finish and run require empty-stack priority; the stop icon stays available during execution. Existing Auto-pay and chosen floating-mana payment remain available.
- Deploy this package to Render and publish the matching website. Full pinned-engine compilation and live signed-in loop validation remain deployment checks.

## v0.54.6: stack token identity and accurate blocking logs
- Stack entries retain public host token/type/power/toughness metadata for face-aware artwork. Face-down hosts do not expose these details.
- Unblocked attacker drawing placeholders no longer produce false self-block log entries. Real blockers with different card IDs, including copies with the same name, remain logged.
- The matching website adds safe empty-window auto-pass, own-action priority passing, Full control and per-seat phase bookmarks. Legal upkeep/draw responses and all mandatory decisions remain available. Messaging Logs is resizable.
- Deploy to Render and publish the website; full pinned-engine compilation and live-match verification remain deployment checks.

## v0.54.5: complete public zone movement logs
- Every zone transition is recorded, including hand/library transfers, casts to the stack, spell resolution to the graveyard and land plays. Draws retain the existing bundled draw log. Moves between hidden zones use anonymous card descriptions; face-down identities remain hidden.
- All explicit public reveals remain recorded and retained in snapshot history. The matching website avoids duplicate notices for cards already displayed in the human selection window; unrelated and opponent reveal notices remain visible.
- Messaging Logs can be dragged across the whole playtest area, including the hand dock and Tools sidebar, without changing engine state. Deploy this companion to Render and publish the website; full engine compilation and live-match verification remain deployment checks.

## v0.54.4: blocker selection and public mulligan notices
- Human blocker choices use creature readiness and pairwise evasion legality, not partial combat-group validation; summoning sickness does not prevent blocking. Forge retains final validation of complete blocks, including menace, lure and maximum-blocker restrictions.
- Opponent redraws publish the resulting London kept-card count and free-mulligan usage to the bounded public action log and a non-blocking table notice. Hand identities, bottomed cards and strategy scores remain private.
- The matching website adds a default-open, minimizable desktop mini action log with full-history access; mobile keeps the existing history dialog. Regular solo Playtest is unchanged.
- Includes the v0.54.3 Docker packaging correction. Deploy to Render and start a new match; full engine compilation and live-game verification remain deployment checks.

## v0.54.2: commander choices after mana abilities
- Standalone mana activations return to the engine's priority loop before the next action. Food Chain's exile cost, sacrifice-for-mana abilities and other commander zone changes now reach the normal state-based commander decision before another activation or cast.
- Exile/graveyard choices occur after the complete ability resolves; costs and mana output are unchanged. Mana sources used while paying for a spell do not interrupt payment with premature state-based actions.
- The engine supplies the actual moved commander to its owner's confirmation, including commanders whose current face has no spell ability. Choosing to leave it in exile/graveyard remains supported; hand/library replacements and the AI owner's decisions remain engine-owned.
- Deploy this package to Render and start a new match. Full Docker compilation and live Food Chain verification remain deployment checks; regular solo Playtest is unchanged.

## v0.54: opponent removal, combat sequencing and constructed defaults
- Follow-up: initialize missing mana-ability activators before conditional production checks (including Sacred Foundry); existing activators remain unchanged. The matching website detaches retired socket callbacks and cancels stale retries to prevent duplicate reconnect loops.
- Burn targeting a player, recursion/tutors and multi-target effects stay with the engine's normal evaluation; extra removal patience applies only to single-target battlefield removal.
- Sorcery-speed removal can be used in Main 2 below the usual threat threshold. Public creature power, toughness and mana value increase threat scores; combo/engine/stax priorities remain intact.
- Available pre-land selection is considered independently of the land action. Haste enablers and attack-trigger plays may be sequenced before combat.
- Crackback estimates assign legal blockers once, fully stopping non-trample attackers and preserving trample overflow rather than subtracting blocker toughness from every attack.
- Missing format defaults to Constructed for decks without a Commander section; explicit format and site-provided life/mulligan values remain authoritative.
- Human decisions, solo Playtest, transport protection and match isolation are unchanged. Deploy to Render and start a new match to use this version.

## v0.53: human discard choices, including opponent hands
- Thoughtseize, Duress, Inquisition, Grief and related effects ask the actual human chooser instead of treating the affected opponent as the decision maker. The full effect-permitted hand stays visible, with ineligible cards disabled, including when only one discard is legal.
- Thirst for Knowledge, Wrench Mind and other discard-unless-type effects let the human choose one matching card or the required number of cards, then select those cards. Opponent decisions and freeform Playtest are unchanged; existing explicit timeout behavior is retained.
- Deploy this companion to Render and start a new match. Live card-specific verification and full engine compilation remain required after deployment.

## v0.52: alternative mana colours share one tap
- Payment choices carry exact permanent identity and tap-cost metadata. Selecting a tap ability reserves that permanent across every colour row, including grouped copies of dual lands.
- The companion validates the entire manual selection before activating any source, rejecting duplicate keys or two tap abilities from the same permanent. Multi-mana output from one activation and non-tapping abilities remain engine-controlled.
- Deploy this companion with the website and start a new match; real-game verification remains required.

## v0.51: shared public reveal batches and named life bars
- Explicit public reveals from either seat carry the full batch, public audience and stable ID, and remain in bounded snapshot history so missed broadcasts and reconnects do not lose them. Private looks/scry remain private; the casting player's selection layout and legal choices are unchanged.
- AI display names are chosen once per match from Spike, Timmy and Johnny. Internal player/opponent keys stay unchanged; the website life bars use the signed-in player's account name.
- Deploy this companion and publish the website, then start a new match. Live card-specific verification is still required after deployment.

## v0.50: your triggered abilities (attack triggers especially) always resolve
- Every trigger you control goes through your decisions instead of Forge AI's trigger evaluator, so mandatory attack triggers can no longer be silently declined.
- Optional "you may" triggers you accepted are not re-evaluated by AI when they resolve; unanswered optional prompts default to resolving.
- Trigger effects resolve with your own cost payments instead of AI cost heuristics.

## v0.48: complete choice context and effect-scoped public reveals
- Show the full remembered/delayed/temporary look batch alongside legal choices; nonmatching cards are display-only. Never scan another library to reconstruct a batch.
- Observe public reveals at the engine's GameAction boundary, including the human player's reveals. Log each full public batch once with its actual owner and zone; private looks and scry remain private.
- Website reveal notices use the affected player's name and exclude private peeks.
- Deploy this companion with the matching website and start a new match. Full pinned engine compilation and live gameplay still require deployment verification.

## v0.47: Render build fix — pinned Forge Cost has no isNoCost(); paid-trigger detection uses hasCost() (non-free cost parts)

## v0.46: look-at choices (Break Out, Dig) show every looked-at card; ineligible ones are dimmed and unselectable
## v0.46 also: format-based starting life and free mulligans, commander damage badges, Guide of Souls-style paid and reflexive triggers are your choice


## v0.45: active Forge strategy vetoes & combat wiring
- Hard Main Phase 1 and end-step phase vetoes: `OpponentMulliganController.chooseSpellAbilityToPlay()` returns null (passes priority) when `OpponentStrategy.shouldVeto()` rejects the engine's chosen play, and `sequence()` sorts vetoed plays at -10000. Non-haste creatures, non-combat spells and instant-speed draw now wait for Main 2 / the end step before the AI's turn; land drops are never vetoed except to let a pre-land cantrip resolve first.
- Commander threat-rubric defender assignment and lethal crackback veto in `OpponentMulliganController.declareAttackers()`: attackers are sent at the highest-priority player defender (public state only), and non-vigilance attackers pull back, lowest power first, when a non-lethal attack would die to the crackback.
- Utility/dork/engine/combo chump-block protection with a <= 4 life floor in `OpponentMulliganController.declareBlockers()`: protected creatures are removed from blocks where they die and the attacker survives, unless the unblocked hit would nearly kill us. Illegal block states are always reverted.
- Colorless/single-color-first mana tapping in `OpponentMulliganController.playChosenSpellAbility()`: multicolor lands and colors needed by held instant-speed interaction are temporarily masked as tapped while Forge pays, using the proven tap-masking pattern; masks are always restored.
- Removal patience hard floor: single-target removal against permanents scoring below 65 is vetoed unless it is the end step before our turn, we are discarding, or the removal wins this turn. Opposing auras, equipment and pump spells on the stack add +40 to the target's threat score and +40 to instant removal sequencing.

## v0.42: play sequencing
- AI Opponent orders its own legal plays: card selection before the land drop, only combat-relevant spells (haste, pumps, removal) in Main 1, lower-tier threats first into open counter mana, instant-speed draw held for the end step before its turn.
- Only ordering changes; Forge still decides whether and what to cast. Mana-tapping preference, chump-block protection and responding-to-triggers rules are written and tested but not yet wired into Forge.

## v0.41
- AI Opponent scores opposing players and permanents with the Commander threat rubric (draw/mana engines, apex commanders, combo pieces, lethal attackers, stax "enemy of my enemy"). Only public battlefield, hand counts and life are used.
- Removal targeting is boosted only for threats scoring 65+; stax that polices the table leader is protected. Combat-defender choice and sandbagging rules are scored but not yet wired into Forge combat decisions.


## v0.40: competitive opponent mulligans
- Opening hands use the supplied 0–100 rubric, play/draw roles, colour-pip assignment, early action, pregame/engine bonuses and keep thresholds 65/55/40/25/0.
- London redraws remain seven cards; the AI scores its virtual kept hand and bottoms once on Keep. Forge owns format/free-mulligan return counts; thresholds decay by actual redraw count.
- Bottoming follows excess lands (3, or 2 for low-curve aggro), off-colour spells, 5+ mana drops, redundant legends, then low-impact cards, preferring the highest-scoring virtual hand within each priority.
- Own archetype comes from the submitted deck's average spell cost, creature/interaction ratios and validated combo relationships. Opposing archetype starts unknown: the human deck is never read.
- Colour probabilities use aggregate submitted-deck source counts minus the known drawn hand, not either game library. Conditional/fetch lands are conservative estimates; these checks never grant casting permission or replace engine payments.
- Debug scores and bottoming reasons stay in private worker stderr, not the public action log. No human advisor or browser/solo changes.
- Upload v0.40 to Render and start a NEW match; keep MAX_MATCHES=4 and your transport secret unchanged. Pure policy tests are separate from full Docker compilation and live-match validation.

## v0.35: commander moves, public reveals, counters, convoke
- Commander leaving to graveyard/exile/hand/library asks the owner whether to send it to the command zone.
- Cards an opponent reveals are logged by name and sent as a REVEAL event.
- Battlefield cards include every counter (lore, burden, +1/+1…).
- Convoke/Improvise asks which untapped permanents help pay.

## v0.34: playable exile shelf
- Exile entries now carry the viewer's play grants (`playable`, `freeCast`) from Forge's `mayPlay` — impulse draws (Light Up the Stage, Reckless Impulse), theft (Ragavan, Gonti), and adventures exiled "on an adventure" — plus `counters` (suspend time counters).
- The website shelves those cards above the hand dock with art, counter badges and the usual cast actions; suspend cards cast themselves when the last time counter leaves, exactly as the engine rules it.

## v0.33: single-step undo
A checkpoint is recorded at every priority prompt and after each manual mana activation, so Undo rewinds only the last action (for example, untapping just the last land tapped) instead of the whole turn. Rules-engine barriers (draws, reveals, opponent priority, resolution) still block undo.

## v0.32: catalog-aware opponent strategy
- The website supplies the same Combo Finder feeds, matching complete combos in the AI deck, current Scryfall Game Changer flags and available USD prices.
- The pinned Forge source receives seven fail-closed scoring hooks: creature/combat valuation, noncreature removal, permanent selection/preservation, cast ordering, and simulation permanent/land evaluation. Forge still chooses legal actions and resolves all rules.
- Combo pieces gain contextual value as pieces become available. Opposing combos use face-up battlefield information only; opponent hand/library contents and deck lists are not inspected. Human priority/cost choices keep baseline evaluation.
- Game Changer bonus is 85; price adds at most 8, never replacing tactical strength. Price is name-level Scryfall USD reference metadata, not exact-printing/store valuation. Missing prices remain unknown.
- This improves recognition/protection and threat ranking; it is not a combo execution solver and cannot guarantee every loop is sequenced optimally.
- Include patch-strategy.py at the repository root when uploading. Docker patches the pinned engine before compiling; mismatched source stops the build instead of silently dropping strategy.
- Deploy this package on Render, then publish the matching website and start a NEW match. Solo Playtest is unchanged. Full Docker compilation and real-match strategy verification remain required after deployment.


## v0.31: X choices are capped at what your untapped sources and pool can actually pay; the prompt sends cost-per-X and base cost.

## v0.30: blocks are checked for evasion (flying, reach, menace) with legal pairs sent to the table; name-a-card effects (Demonic Consultation) ask you; full stack entries with controller, targets and copies plus the storm count.

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
classpath to preserve the Jetty isolation fix. Defaults: WORKER_HEAP=900m,
MAX_MATCHES=2 (override in the host env; budget ~1 GB RAM per table).

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
