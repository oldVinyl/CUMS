import unittest
from retention import expired


class RetentionTest(unittest.TestCase):
    def test_daily_keeps_thirty_and_ignores_foreign_names(self):
        keys = [f'cums/daily/cums-202601{i:02d}T020000Z.tar.age' for i in range(1, 32)]
        keys += ['other/daily/cums-20250101T020000Z.tar.age',
                 'cums/daily/notes.txt', 'cums/daily/nested/cums-20240101T020000Z.tar.age']
        self.assertEqual(expired(keys, 'cums', 'daily', 30),
                         ['cums/daily/cums-20260101T020000Z.tar.age'])

    def test_monthly_keeps_twelve_and_empty_list(self):
        keys = [f'cums/monthly/{year}{month:02d}.tar.age'
                for year in [2025, 2026] for month in range(1, 13)]
        self.assertEqual(len(expired(keys, 'cums', 'monthly', 12)), 12)
        self.assertTrue(all('2025' in key for key in expired(keys, 'cums', 'monthly', 12)))
        self.assertEqual(expired([], 'cums', 'daily', 30), [])


if __name__ == '__main__':
    unittest.main()
