import unittest

from dorahub_vision import health


class HealthTest(unittest.TestCase):
    def test_health(self) -> None:
        self.assertEqual(
            health(),
            {"status": "ok", "service": "dorahub-vision"},
        )


if __name__ == "__main__":
    unittest.main()

