"""Check the event boundary against Forge's unblocked-attacker sentinel."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class CombatLogTests(unittest.TestCase):
    def test_sentinel_filtered_by_identity_not_name(self):
        source = (ROOT / 'src/main/java/com/playtest/PublicActionLog.java').read_text()
        block = source.split('void block(', 1)[1].split('@Subscribe', 1)[0]
        self.assertIn('item.getKey().getId() == item.getValue().getId()', block)
        self.assertNotIn('getName().equals', block)

    def test_stack_token_metadata_excludes_face_down_hosts(self):
        source = (ROOT / 'src/main/java/com/playtest/MatchWorker.java').read_text()
        stack = source.split('List<Map<String, Object>> stack', 1)[1].split('s.put("stack"', 1)[0]
        self.assertIn('host != null && !host.isFaceDown()', stack)
        self.assertIn('e.put("token", host.isToken())', stack)
        self.assertIn('e.put("power", host.getNetPower())', stack)

if __name__ == '__main__':
    unittest.main()