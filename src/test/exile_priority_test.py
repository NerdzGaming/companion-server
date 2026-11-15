"""Adapter regressions; full engine legality remains a deployed-match check."""
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[2] / 'src/main/java/com/playtest/HumanBridgeController.java').read_text()
PRIORITY = SOURCE.split('public List<SpellAbility> chooseSpellAbilityToPlay()', 1)[1].split('private int deferredTuck', 1)[0]


class ExilePriorityTests(unittest.TestCase):
    def test_ragavan_candidates_include_every_seat_exile(self):
        self.assertIn('for (Player seat : me.getGame().getPlayers())', PRIORITY)
        self.assertIn('seat.getCardsIn(ZoneType.Exile)', PRIORITY)
        self.assertIn('if (!c.isFaceDown()) candidates.put(c.getId(), c)', PRIORITY)

    def test_engine_permissions_and_costs_still_gate_the_action(self):
        self.assertIn('c.getAllPossibleAbilities(me, true)', PRIORITY)
        self.assertIn('if (!sa.canPlay()) continue;', PRIORITY)
        self.assertIn('canPayHumanCost(sa.getPayCosts(), sa, false)', PRIORITY)
        self.assertNotIn('seat.getCardsIn(ZoneType.Hand)', PRIORITY)
        self.assertNotIn('seat.getCardsIn(ZoneType.Library)', PRIORITY)

    def test_candidate_identity_is_unique(self):
        self.assertIn('Map<Integer, Card> candidates = new LinkedHashMap<>()', PRIORITY)
        self.assertIn('for (Card c : candidates.values())', PRIORITY)


if __name__ == '__main__':
    unittest.main()