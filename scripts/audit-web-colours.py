#!/usr/bin/env python3
"""Audit application-owned web colour literals without reading dependencies or build output."""
import argparse
import colorsys
import json
import re
from pathlib import Path

APPROVED_BLUE = {'#2563EB', '#1E40AF', '#3B82F6', '#93C5FD', '#DBEAFE'}
BLUE_NAMES = {
    'blue': '#0000FF', 'royalblue': '#4169E1', 'dodgerblue': '#1E90FF',
    'cornflowerblue': '#6495ED', 'deepskyblue': '#00BFFF', 'skyblue': '#87CEEB',
    'lightskyblue': '#87CEFA', 'lightblue': '#ADD8E6', 'powderblue': '#B0E0E6',
    'steelblue': '#4682B4', 'lightsteelblue': '#B0C4DE', 'navy': '#000080',
    'darkblue': '#00008B', 'mediumblue': '#0000CD', 'midnightblue': '#191970',
    'slateblue': '#6A5ACD', 'mediumslateblue': '#7B68EE', 'darkslateblue': '#483D8B',
    'cadetblue': '#5F9EA0', 'cyan': '#00FFFF', 'aqua': '#00FFFF', 'darkcyan': '#008B8B',
    'turquoise': '#40E0D0', 'mediumturquoise': '#48D1CC', 'paleturquoise': '#AFEEEE',
    'azure': '#F0FFFF', 'aliceblue': '#F0F8FF', 'lightcyan': '#E0FFFF'
}
COLOUR = re.compile(r'(?<![\w&])\#(?:[\da-f]{8}|[\da-f]{6}|[\da-f]{4}|[\da-f]{3})\b'
                    r'|(?:rgba?|hsla?)\([^)]*\)'
                    r'|(?<![\w-])(?:' + '|'.join(BLUE_NAMES) + r')(?![\w-])', re.I)
PROPERTY = r'(?:--[\w-]+|(?:[\w-]*color)|background(?:-image)?|border(?:-[\w-]+)?|outline|(?:box|text)-shadow|fill|stroke|accent-color|caret-color)'
DECLARATION = re.compile(r'(?P<property>' + PROPERTY + r')\s*:\s*(?P<value>[^;{}]+)', re.I)


def normalise(value):
    value = value.lower().strip()
    if value in BLUE_NAMES:
        return BLUE_NAMES[value]
    if value.startswith('#'):
        digits = value[1:]
        if len(digits) in (3, 4):
            digits = ''.join(c * 2 for c in digits)
        return '#' + digits[:6].upper()
    function, rest = value.split('(', 1)
    parts = re.split(r'[,\s/]+', rest.rstrip(')').strip())
    if len(parts) < 3:
        return None
    try:
        if function.startswith('hsl'):
            hue = float(parts[0].removesuffix('deg')) % 360 / 360
            rgb = colorsys.hls_to_rgb(hue, float(parts[2].rstrip('%')) / 100, float(parts[1].rstrip('%')) / 100)
            rgb = [round(c * 255) for c in rgb]
        else:
            rgb = [round(float(p.rstrip('%')) * (2.55 if p.endswith('%') else 1)) for p in parts[:3]]
        return '#' + ''.join(f'{max(0, min(255, c)):02X}' for c in rgb)
    except ValueError:
        return None


def is_blue(value):
    if value in APPROVED_BLUE or value in BLUE_NAMES.values():
        return True
    r, g, b = [int(value[i:i+2], 16) for i in (1, 3, 5)]
    return b > r and b >= g and b - min(r, g) >= 40


def fragments(text, suffix):
    text = re.sub(r'/\*.*?\*/|<!--.*?-->', lambda m: '\n' * m.group().count('\n'), text, flags=re.S)
    if suffix in ('.css', '.scss', '.sass'):
        return [(0, text)]
    result = []
    if suffix == '.html':
        result += [(m.start(1), m[1]) for m in re.finditer(r'<style\b[^>]*>(.*?)</style>', text, re.S | re.I)]
        result += [(m.start(2), m[2]) for m in re.finditer(r'\bstyle\s*=\s*([\'"])(.*?)\1', text, re.S | re.I)]
    if suffix in ('.html', '.js', '.jsx', '.ts', '.tsx'):
        # CSS strings, style assignments and inline SVG colours; ignore prose, entities and identifiers.
        result += [(m.start(2), m[2]) for m in re.finditer(r'([\'"`])([^\'"`]*?(?:color|background|border|shadow|outline|fill|stroke)[^\'"`]*?)\1', text, re.S | re.I)]
        result += [(m.start(2), 'color:' + m[2]) for m in re.finditer(r'\b(?:fill|stroke|color|backgroundColor|borderColor)\s*[=:]\s*([\'"])([^\'"]+)\1', text, re.I)]
    if suffix == '.svg':
        result += [(m.start(2), 'color:' + m[2]) for m in re.finditer(r'\b(?:fill|stroke|color|stop-color)\s*=\s*([\'"])([^\'"]+)\1', text, re.I)]
    return result


def audit(root):
    files = sorted(p for folder in ['templates', 'static'] for p in (root/'src/main/resources'/folder).rglob('*')
                   if p.is_file() and p.suffix in {'.css', '.scss', '.sass', '.html', '.js', '.jsx', '.ts', '.tsx', '.svg'})
    literals, seen = [], set()
    for file in files:
        text = file.read_text()
        for offset, fragment in fragments(text, file.suffix):
            for declaration in DECLARATION.finditer(fragment):
                value = declaration['value'].replace('%23', '#')
                if declaration['property'].endswith('-rgb') and re.fullmatch(r'\s*\d+\s*,\s*\d+\s*,\s*\d+\s*', value):
                    value = 'rgb(' + value + ')'
                for match in COLOUR.finditer(value):
                    colour = normalise(match.group())
                    if not colour:
                        continue
                    line = text.count('\n', 0, offset + declaration.start()) + 1
                    item = {'file': str(file.relative_to(root)), 'line': line, 'property': declaration['property'],
                            'literal': match.group(), 'rgb_hex': colour, 'blue': is_blue(colour)}
                    key = (item['file'], line, colour)
                    if key not in seen:
                        seen.add(key); literals.append(item)
    return {'files_reviewed': [str(p.relative_to(root)) for p in files], 'literals': literals,
            'unapproved_blues': [item for item in literals if item['blue'] and item['rgb_hex'] not in APPROVED_BLUE]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = audit(args.root.resolve())
    output = json.dumps(result, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(output + '\n')
    print(json.dumps({'files_reviewed': len(result['files_reviewed']), 'unapproved_blues': result['unapproved_blues']}))
    raise SystemExit(1 if args.check and result['unapproved_blues'] else 0)


if __name__ == '__main__':
    main()
