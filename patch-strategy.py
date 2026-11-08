"""Fail-closed hooks into the pinned Forge source; executed before Maven builds Forge."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'forge-ai/src/main/java/forge/ai'

def replace(path, old, new, count=1):
    text = path.read_text()
    if text.count(old) != count:
        raise RuntimeError(f'Pinned strategy patch mismatch: {path.name}: {old[:70]}')
    path.write_text(text.replace(old, new))

(root / 'NerdzStrategy.java').write_text('''package forge.ai;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import java.util.function.ToIntFunction;
/** One immutable provider per isolated match JVM. No browser or human-choice overrides. */
public final class NerdzStrategy {
    private static volatile ToIntFunction<Card> provider;
    private static volatile ToIntFunction<SpellAbility> sequencer;
    public static void installSequencer(ToIntFunction<SpellAbility> value) { sequencer = value; }
    public static int sequence(SpellAbility sa) {
        ToIntFunction<SpellAbility> current = sequencer;
        if (current == null || sa == null) return 0;
        try { return current.applyAsInt(sa); } catch (RuntimeException ignored) { return 0; }
    }
    private NerdzStrategy() { }
    public static void install(ToIntFunction<Card> value) { provider = value; }
    public static int bonus(Card card) {
        ToIntFunction<Card> current = provider;
        if (current == null || card == null) return 0;
        try { return current.applyAsInt(card); } catch (RuntimeException ignored) { return 0; }
    }
}
''')
replace(root / 'CreatureEvaluator.java', '        return value;\n    }\n\n    private int evaluateSpellAbility', '        return value + NerdzStrategy.bonus(c);\n    }\n\n    private int evaluateSpellAbility')
replace(root / 'ComputerUtilCard.java', '            value += c.getCounters(CounterEnumType.LOYALTY) * 10;\n            }', '            value += c.getCounters(CounterEnumType.LOYALTY) * 10;\n            }\n            value += NerdzStrategy.bonus(c);')
replace(root / 'ComputerUtilCard.java', 'int curCMC = card.getCMC();', 'int curCMC = card.getCMC() + NerdzStrategy.bonus(card) / 30;')
replace(root / 'ComputerUtilCard.java', 'c.getManaCost().getCMC() <= cheapest.getManaCost().getCMC()', 'c.getManaCost().getCMC() * 30 + NerdzStrategy.bonus(c) <= cheapest.getManaCost().getCMC() * 30 + NerdzStrategy.bonus(cheapest)')
replace(root / 'ComputerUtilAbility.java', '            a1 += getSpellAbilityPriority(a);', '            a1 += NerdzStrategy.bonus(a.getHostCard()) / 30;\n            b1 += NerdzStrategy.bonus(b.getHostCard()) / 30;\n            a1 += NerdzStrategy.sequence(a);\n            b1 += NerdzStrategy.sequence(b);\n            a1 += getSpellAbilityPriority(a);')
simulation = root / 'simulation/GameStateEvaluator.java'
replace(simulation, '            return evaluateLand(c);', '            return evaluateLand(c) + forge.ai.NerdzStrategy.bonus(c);')
replace(simulation, '        return value;\n    }\n\n    public static int evaluateLand', '        return value + forge.ai.NerdzStrategy.bonus(c);\n    }\n\n    public static int evaluateLand')
print('Applied seven pinned engine strategy hooks')

# Public reveal boundaries live in GameAction, not in individual viewer callbacks.
game_root = Path(sys.argv[1]) / 'forge-game/src/main/java/forge/game'
(game_root / 'NerdzReveal.java').write_text('''package forge.game;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.spellability.SpellAbility;
/** Per-match observer; private revealTo/controller looks never call this hook. */
public final class NerdzReveal {
    public interface Observer { void reveal(CardCollectionView cards, ZoneType zone, Player owner); }
    private static Observer observer;
    private NerdzReveal() { }
    public static void install(Observer value) { observer = value; }
    public static void publish(CardCollectionView cards, ZoneType zone, Player owner) {
        if (observer != null && cards != null && !cards.isEmpty()) observer.reveal(cards, zone, owner);
    }
    /** Portal reveal-and-select policy; plain private peeks without a reveal choice stay private. */
    public static void choiceBatch(CardCollectionView cards, ZoneType zone, Player owner, SpellAbility sa) {
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if ("True".equalsIgnoreCase(part.getParamOrDefault("Reveal", ""))) {
                owner.getGame().getAction().reveal(cards, zone, owner, false, null);
                return;
            }
        }
    }
}
''')
replace(game_root / 'GameAction.java',
    'public void reveal(CardCollectionView cards, ZoneType zt, Player cardOwner, boolean dontRevealToOwner, String messagePrefix, boolean msgAddSuffix) {',
    'public void reveal(CardCollectionView cards, ZoneType zt, Player cardOwner, boolean dontRevealToOwner, String messagePrefix, boolean msgAddSuffix) {\n        NerdzReveal.publish(cards, zt, cardOwner);')
print('Applied effect-scoped public reveal hook')
replace(game_root / 'ability/effects/PeekAndRevealEffect.java',
    '            params.put("Revealed", peekCards);',
    '            params.put("Revealed", peekCards);\n            forge.game.NerdzReveal.choiceBatch(peekCards, srcZone, zoneToPeek, sa);')