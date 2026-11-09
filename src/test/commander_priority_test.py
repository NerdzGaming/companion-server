"""Source-boundary regressions for the isolated Forge adapter (no engine artifact needed)."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HUMAN = (ROOT / 'src/main/java/com/playtest/HumanBridgeController.java').read_text()
PATCH = (ROOT / 'patch-strategy.py').read_text()


class CommanderPriorityTests(unittest.TestCase):
    def test_food_chain_returns_to_engine_before_next_priority(self):
        priority = HUMAN.split('public List<SpellAbility> chooseSpellAbilityToPlay()', 1)[1].split('private int deferredTuck', 1)[0]
        activation = priority.split('if (sa.isManaAbility()) {', 1)[1].split('// Do not call getAi()', 1)[0]
        self.assertIn('return List.of(sa);', activation)
        self.assertNotIn('activateHumanMana(sa)', priority, 'Must not consume actions inside the priority selection loop')

    def test_exile_and_sacrifice_mana_sources_share_the_boundary(self):
        priority = HUMAN.split('public List<SpellAbility> chooseSpellAbilityToPlay()', 1)[1].split('private int deferredTuck', 1)[0]
        branch = priority.split('if (sa.isManaAbility()) {', 1)[1].split('// Do not call getAi()', 1)[0]
        self.assertIn('return List.of(sa);', branch)
        self.assertNotIn('getName()', branch, 'No Food Chain-only special casing')

    def test_standalone_mana_resolves_without_stack_and_reports_success(self):
        play = HUMAN.split('public boolean playChosenSpellAbility(SpellAbility sa)', 1)[1].split('private boolean activateHumanMana', 1)[0]
        branch = play.split('if (sa.isManaAbility()) {', 1)[1].split('return castWithHumanChoices', 1)[0]
        self.assertIn('activateHumanMana(sa)', branch)
        self.assertIn('return false;', branch)
        self.assertIn('return true;', branch, 'Engine must reset the pass sequence after successful activation')
        self.assertNotIn('addAndUnfreeze', branch)

    def test_payment_activations_do_not_interrupt_casting(self):
        activate = HUMAN.split('private boolean activateHumanMana(SpellAbility sa)', 1)[1].split('private boolean castWithHumanChoices', 1)[0]
        self.assertIn('AbilityUtils.resolve(sa);', activate)
        self.assertNotIn('checkStateEffects', activate)
        self.assertNotIn('moveToCommand', activate)

    def test_commander_confirmation_uses_actual_card_and_owner(self):
        confirm = HUMAN.split('public boolean confirmAction(', 1)[1].split('public <T extends GameEntity>', 1)[0]
        self.assertIn('cardToShow != null ? cardToShow', confirm)
        self.assertIn('movedCard.getOwner() == getPlayer()', confirm)
        self.assertIn('return a.path("yes").asBoolean(false);', confirm, 'Leaving commander in exile remains possible')

    def test_pinned_engine_context_patch_keeps_state_based_timing(self):
        self.assertIn('ChangeZoneToAltDestination', PATCH)
        self.assertIn('zone since the last time state-based actions were checked', PATCH)
        self.assertIn('zone.", c, null)', PATCH)


if __name__ == '__main__':
    unittest.main()