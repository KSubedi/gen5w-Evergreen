#!/usr/bin/env python3
"""The unified installer's checks: the combined AR report and merging cars' AR builds."""
import importlib.util, json, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "tools" / (name + ".py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m


check = module("check-carplay-ar-build")
installer = module("build-carplay-ar-installer")


class UnifiedTest(unittest.TestCase):
    def report(self, data):
        d = Path(tempfile.mkdtemp()); (d / "patches").mkdir()
        (d / "ar-build.json").write_text(json.dumps(data))
        return d / "patches", d / "ar-build.json"

    def test_unified_report_needs_its_cars(self):
        for bad in ({"unified": True, "schema": 2, "variants": {}},
                    {"unified": True, "schema": 1, "variants": {"ev6": {"variant": "ev6"}}},
                    {"unified": True, "schema": 2, "variants": {"ev6": {"variant": "ev6_ca"}}},
                    {"unified": True, "schema": 2, "enabled_by_default": True,
                     "variants": {"ev6": {"variant": "ev6", "enabled_by_default": False}}}):
            with self.assertRaises(ValueError, msg=bad):
                check.validate(*self.report(bad))

    def test_every_car_in_cars_has_an_installer_entry(self):
        for name, car in installer.CARS.items():
            self.assertTrue((ROOT / "patches" / name / "manifest").is_file(), name)
            self.assertIn("products", car); self.assertNotIn(",", car["products"])
        self.assertIn("EV6", installer.UNIFIED["car"])

    def test_gated_fills_both_placeholders(self):
        out = installer.gated(b"case x in @AR_PRODUCTS@) ;; *) echo @AR_CAR@ ;; esac", installer.CARS["ev6"])
        self.assertNotIn(b"@AR_", out); self.assertIn(b"CVEV.USA.*.260507", out)
        with self.assertRaises(ValueError):
            installer.gated(b"no placeholders", installer.CARS["ev6"])


if __name__ == "__main__":
    unittest.main()
