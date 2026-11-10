"""Adapter boundary checks: candidates differ from final combat-group validation."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HUMAN = (ROOT / 'src/main/java/com/playtest/HumanBridgeController.java').read_text()
OPPONENT = (ROOT / 'src/main/java/com/playtest/OpponentMulliganController.java').read_text()


class BlockingOptionsTests(unittest.TestCase):
    def test_new_creatures_use_block_readiness_not_attack_sickness(self):
        block = HUMAN.split('public void declareBlockers(', 1)[1].split('public String chooseCardName', 1)[0]
        self.assertIn('CombatUtil.canBlock(c)', block)
        self.assertNotIn('isSick', block)
        self.assertNotIn('canAttack', block)

    def test_candidates_keep_evasion_but_not_partial_group_checks(self):
        legal = HUMAN.split('private static boolean legalBlock(', 1)[1].split('public String chooseCardName', 1)[0]
        self.assertIn('CombatUtil.canBlock(blocker)', legal)
        self.assertIn('CombatUtil.canBlock(attacker, blocker)', legal)
        self.assertNotIn('CombatUtil.canBlock(attacker, blocker, combat)', legal)

    def test_mulligan_publication_only_on_redraw(self):
        decision = OPPONENT.split('public boolean mulliganKeepHand(', 1)[1].split('public CardCollectionView tuckCardsViaMulligan', 1)[0]
        redraw = decision.split('} else {', 1)[1]
        self.assertIn('redraws++;', redraw)
        self.assertIn('MulliganNotice.text(getPlayer().getName(), hand.size(), redraws, MatchWorker.freeMulligans)', redraw)
        self.assertIn('MatchWorker.actionLog.mulligan(text)', redraw)
        self.assertIn('"category", "mulligan"', redraw)
        self.assertNotIn('getName()', redraw.replace('getPlayer().getName()', ''))

    def test_complete_groups_are_engine_validated_and_reprompted(self):
        block = HUMAN.split('public void declareBlockers(', 1)[1].split('private static boolean legalBlock', 1)[0]
        self.assertIn('while (true)', block)
        self.assertIn('CombatUtil.validateBlocks(combat, defender)', block)
        self.assertIn('combat.undoBlockingAssignment(blocker)', block)
        self.assertIn('if (error == null && refused.isEmpty()) return;', block)


if __name__ == '__main__':
    unittest.main()