"""Build a minimal SVG sprite containing only the icons this app actually uses.

The upstream Bootstrap Icons sprite is ~1MB / 2000+ symbols. Extracting just the
icons referenced by the templates and JS keeps the payload small while staying
fully offline. Icon names are collected from the source rather than hand-listed,
so adding an icon to a template and re-running this picks it up.
"""
import io
import glob
import re
import sys
import xml.etree.ElementTree as ET

SRC = '/tmp/bi-src/sprite.svg'
OUT = 'src/main/resources/static/vendor/icons.svg'
FRAGMENT = 'src/main/resources/templates/fragments/icons.html'
SVG_NS = 'http://www.w3.org/2000/svg'

# Collect every icon name used in templates and JS.
used = set()
for path in glob.glob('src/main/resources/templates/*.html'):
    s = io.open(path, encoding='utf-8').read()
    # Templates reference the sprite directly: <svg class="ico"><use href="#name"/></svg>
    used.update(re.findall(r'<use href="#([a-z0-9-]+)"', s))
for path in glob.glob('src/main/resources/static/js/*.js'):
    s = io.open(path, encoding='utf-8').read()
    used.update(re.findall(r"icon\('([a-z0-9-]+)'\)", s))
    used.update(re.findall(r"iconHtml\('([a-z0-9-]+)'\)", s))
    # ICONS map values, e.g. ok: 'check-circle-fill'
    used.update(re.findall(r"^\s*\w+: '([a-z0-9-]+)',?$", s, re.M))

# Icons the JS builds at runtime; they never appear as a literal class in markup.
used.update({
    'sun-fill', 'moon-stars-fill', 'check-circle-fill', 'x-circle-fill',
    'exclamation-triangle-fill', 'info-circle-fill', 'x-lg', 'list',
})

print(f'icons referenced: {len(used)}')

ET.register_namespace('', SVG_NS)
tree = ET.parse(SRC)
root = tree.getroot()

available = {}
for sym in root.findall(f'{{{SVG_NS}}}symbol'):
    sid = sym.get('id')
    if sid:
        available[sid] = sym

missing = sorted(n for n in used if n not in available)
if missing:
    print('WARNING: not found upstream:', missing)

out = ET.Element(f'{{{SVG_NS}}}svg')
# display:none keeps the inlined sprite from occupying layout space.
out.set('style', 'display:none')
out.set('aria-hidden', 'true')

for name in sorted(used):
    sym = available.get(name)
    if sym is None:
        continue
    # Strip the upstream class attribute; styling comes from the .ico rule instead.
    if 'class' in sym.attrib:
        del sym.attrib['class']
    out.append(sym)

ET.ElementTree(out).write(OUT, encoding='utf-8', xml_declaration=False)

# Written as a Thymeleaf fragment so the layout can inline it: an inlined sprite
# lets <use href="#name"> resolve with no extra request, and works even when the
# page is opened from a file:// context.
body = io.open(OUT, encoding='utf-8').read()
wrapped = (
    '<!--/* 由 build-icons.py 生成，请勿手动编辑。'
    ' 只包含本项目实际用到的图标。 */-->\n'
    '<th:block xmlns:th="http://www.thymeleaf.org" th:fragment="sprite">\n'
    + body + '\n</th:block>\n'
)
io.open(FRAGMENT, 'w', encoding='utf-8').write(wrapped)

print(f'wrote {OUT}: {len(out)} symbols, {len(body) / 1024:.1f} KB')
print(f'wrote {FRAGMENT}')
