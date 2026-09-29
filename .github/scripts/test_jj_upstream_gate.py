import unittest

from jj_upstream_gate import is_guarded, inspect_paths


class UpstreamGateTest(unittest.TestCase):
    def test_readme_and_unrelated_ui_can_auto_intake(self):
        self.assertEqual([], inspect_paths([
            "README.md",
            "composeApp/src/androidMain/kotlin/com/nuvio/app/features/shuffle/Shuffle.kt",
            "composeApp/src/commonMain/composeResources/values-fr/strings.xml",
        ]))

    def test_downloader_and_shared_dependencies_require_review(self):
        for name in [
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsRepository.kt",
            "composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt",
            "composeApp/build.gradle.kts",
            ".github/workflows/desktop-release.yml",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/core/network/Auth.kt",
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/Player.kt",
            "../unsafe.txt",
        ]:
            with self.subTest(name=name):
                self.assertTrue(is_guarded(name))


if __name__ == "__main__":
    unittest.main()
