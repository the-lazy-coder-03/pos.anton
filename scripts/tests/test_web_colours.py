import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('web_colours', ROOT/'scripts/audit-web-colours.py')
colours = importlib.util.module_from_spec(spec)
spec.loader.exec_module(colours)


class WebColoursTest(unittest.TestCase):
    def test_css_colour_formats_share_one_rgb_policy(self):
        for value in ['#2563eb', 'rgb(37, 99, 235)', 'rgba(37,99,235,.24)', 'rgb(14.5098% 38.8235% 92.1569%)']:
            self.assertEqual('#2563EB', colours.normalise(value))
        self.assertEqual('#0000FF', colours.normalise('hsl(240 100% 50%)'))
        self.assertEqual('#1E90FF', colours.normalise('dodgerblue'))
        self.assertEqual('#003366', colours.normalise('#036'))

    def test_audit_distinguishes_colours_from_identifiers_and_entities(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            templates = root/'src/main/resources/templates'; templates.mkdir(parents=True)
            (templates/'page.html').write_text('''<p>Order #12345 &amp; &#039; blue cheese</p>
                <style>#abc { color: dodgerblue; background: rgba(37,99,235,.1); }</style>''')
            result = colours.audit(root)
            self.assertEqual(['dodgerblue'], [i['literal'] for i in result['unapproved_blues']])

    def test_encoded_svg_colours_are_audited(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); static = root/'src/main/resources/static'; static.mkdir(parents=True)
            (static/'icons.css').write_text('select { background-image: url("data:image/svg+xml,%3csvg fill=%230d6efd/%3e"); }')
            self.assertEqual('#0D6EFD', colours.audit(root)['unapproved_blues'][0]['rgb_hex'])

    def test_application_owned_sources_use_only_approved_blues(self):
        self.assertEqual([], colours.audit(ROOT)['unapproved_blues'])

    def test_every_template_loads_the_shared_colour_theme(self):
        templates = sorted((ROOT/'src/main/resources/templates').glob('*.html'))
        self.assertTrue(templates)
        for template in templates:
            self.assertIn('/css/theme.css', template.read_text(), template.name)


if __name__ == '__main__':
    unittest.main()
