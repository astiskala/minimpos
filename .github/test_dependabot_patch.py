import tomllib
import unittest
from pathlib import Path

from dependabot_patch import eligible


class DependabotPatchTest(unittest.TestCase):
    catalog = (Path(__file__).resolve().parents[1] / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    versions = tomllib.loads(catalog)["versions"]

    def patch(self, key):
        version = self.versions[key]
        major, minor, patch = version.split(".")
        return f'{key} = "{version}"', f'{key} = "{major}.{minor}.{int(patch) + 1}"'

    def check_edit(self, replacements, expected):
        head = self.catalog
        for old, new in replacements:
            self.assertIn(old, head)
            head = head.replace(old, new, 1)
        self.assertEqual(eligible(tomllib.loads(self.catalog), tomllib.loads(head)), expected)

    def test_one_or_more_library_patches(self):
        patches = [self.patch("libphonenumber")]
        self.check_edit(patches, True)
        self.check_edit(patches + [self.patch("coreKtx")], True)

    def test_one_ineligible_version_blocks_the_group(self):
        for key in ("adyen", "okhttp", "xerces", "androidMail", "room", "agp", "kotlin"):
            with self.subTest(key=key):
                self.check_edit([self.patch("libphonenumber"), self.patch(key)], False)

    def test_nonpatch_and_catalog_structure_changes_stay_manual(self):
        old, new = self.patch("libphonenumber")
        major, minor, patch = map(int, self.versions["libphonenumber"].split("."))
        for version in (f"{major}.{minor}.{patch}", f"{major}.{minor + 1}.0", f"{major + 1}.0.0",
                        f"{major}.{minor}.{patch + 1}-rc.1", f"{major}.{minor}.0{patch + 1}"):
            with self.subTest(version=version):
                self.check_edit([(old, f'libphonenumber = "{version}"')], False)
        self.check_edit([(old, 'new-name = "9.0.45"')], False)
        self.check_edit([
            (old, new),
            ("com.googlecode.libphonenumber:libphonenumber", "example:libphonenumber"),
        ], False)
        self.check_edit([], False)
