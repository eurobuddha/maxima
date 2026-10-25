#!/usr/bin/env python3
"""Build the Parlons website from the Markdown in this repo (the single source).

    python3 docs/guide/build.py                    # -> ~/Projects/web/parlons-site/
    python3 docs/guide/build.py --out /some/dir

Sources: docs/guide/index.md (Get started), docs/guide/privacy.md, the three manuals in
docs/manuals/, and docs/guide/site/ (landing.html template, site.css, img/). No dependencies: the
renderer below covers exactly the Markdown the manuals use (headings, paragraphs, bold, italic,
code, links, autolinks, nested ordered/unordered lists with code inside, fenced code, tables,
> notes, ---). Download links live in LINKS so a release bump is one edit here; pages refer to
them as {{ANDROID_APK}} etc.

Old guide URLs (android.html, mac.html …) are written as redirects into the manuals, resolved
against the real heading anchors at build time, so a renamed heading fails the build instead of
breaking a link.
"""
import html
import hashlib
import os
import json
import re
import shutil
import sys

LINKS = {
    "ANDROID_VERSION": "0.6.147",
    "ANDROID_APK": "https://github.com/eurobuddha/maxima/releases/download/v0.6.147/parlons-0.6.147.apk",
    "PORTAL_VERSION": "0.2.92",
    "PORTAL_APK": "https://github.com/eurobuddha/maxima/releases/download/portal-v0.2.92/parlons-cloud-portal-0.2.92-release.apk",
    "DESKTOP_VERSION": "1.5.116",
    "DESKTOP_DMG": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.116/MaximaNode-1.5.116-notarized.dmg",
    "DESKTOP_MSI": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.116/MaximaNode-1.5.116.msi",
    "DESKTOP_DEB": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.116/maximanode_1.5.116_amd64.deb",
    "CLOUD_VERSION": "0.11.125",
    "NODE_VERSION": "0.2.139",
    "NODE_JAR": "https://github.com/eurobuddha/maxima/releases/download/node-v0.2.139/parlons-node-0.2.139.jar",
    "RELEASES": "https://github.com/eurobuddha/maxima/releases",
    "REPO": "https://github.com/eurobuddha/maxima",
    "ISSUES": "https://github.com/eurobuddha/maxima/issues",
    "NODE_INSTALL_SH": "https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh",
    "INSTALL_SH": "https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh",
    "INSTALL_PS1": "https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.ps1",
    "PANDAAPPS": "https://github.com/eurobuddha/minima-core-android-pandaapps",
}

HERE = os.path.dirname(os.path.abspath(__file__))

# (code, html lang, file suffix, name as the apps write it). English has no suffix.
LANGS = [("en", "en", "", "English"), ("zh", "zh-Hans", ".zh", "简体中文"), ("ru", "ru", ".ru", "Русский"), ("uk", "uk", ".uk", "Українська")]
STR = json.load(open(os.path.join(HERE, "i18n", "strings.json"), encoding="utf-8"))
# Pages that exist in every language. A manual joins once all its translations are in i18n/<lang>/manuals/.
TRANSLATED = {"index.html", "guide/index.html", "guide/privacy.html"}
START_ROUTES = {
    "android": "chat-on-your-android-phone",
    "desktop": "chat-on-your-computer",
    "pair": "pair-an-account",
    "host": "one-account-on-all-your-devices",
    "node": "run-a-full-node",
}
TRANSLATED |= {"guide/start-%s.html" % name for name in START_ROUTES}
MANUAL_SRC = {"parlons": "parlons.md", "cloud": "parlons-cloud-portal.md", "node": "parlons-node.md"}
TRANSLATED |= {"guide/%s.html" % k for k, f in MANUAL_SRC.items()
               if all(os.path.exists(os.path.join(HERE, "i18n", c, "manuals", f)) for c, _, _, _ in LANGS[1:])}


def lsrc(lang, en_path, name):
    """A page's source in this language: docs/guide/i18n/<lang>/<name>, English from its usual place."""
    return en_path if lang == "en" else os.path.join(HERE, "i18n", lang, name)


def lpath(rel, lang):
    """guide/index.html -> guide/index.zh.html when that page is translated; else the English page."""
    suf = dict((c, x) for c, _, x, _ in LANGS)[lang]
    return rel[:-5] + suf + ".html" if suf and rel in TRANSLATED else rel


def localize_links(body, lang, in_guide=False):
    """Keep internal navigation in the chosen language, including setup routes."""
    def replace(match):
        path = match.group(1)
        rel = "guide/" + path if in_guide else path
        translated = lpath(rel, lang)
        return 'href="' + (translated[6:] if in_guide else translated)
    return re.sub(r'href="([a-z/-]+\.html)', replace, body)
DOCS = os.path.dirname(HERE)

# (output name, source, nav label, kind)
PAGES = [
    ("index", os.path.join(HERE, "index.md"), "Get started", "guide"),
    ("parlons", os.path.join(DOCS, "manuals", "parlons.md"), "Parlons! manual", "manual"),
    ("cloud", os.path.join(DOCS, "manuals", "parlons-cloud-portal.md"), "Cloud manual", "manual"),
    ("node", os.path.join(DOCS, "manuals", "parlons-node.md"), "Node manual", "manual"),
    ("privacy", os.path.join(HERE, "privacy.md"), "Privacy", "guide"),
]
MD_TO_PAGE = {"parlons.md": "parlons.html", "parlons-cloud-portal.md": "cloud.html", "parlons-node.md": "node.html",
              "index.md": "index.html", "privacy.md": "privacy.html"}
MD_TITLE = {"parlons.md": "Parlons! manual", "parlons-cloud-portal.md": "Parlons Cloud manual", "parlons-node.md": "Parlons Node manual"}

# old guide page -> (manual page, a phrase from the target heading); resolved against real anchors
REDIRECTS = {
    "android": ("parlons", "Install"), "mac": ("parlons", "Install"), "windows": ("parlons", "Install"),
    "linux": ("parlons", "Install"), "iphone": ("cloud", "Install the app"),
    "your-account": ("cloud", "One account on your own machine"), "hosted": ("cloud", "Many accounts"),
    "node": None,   # node.html is the Node manual itself now
    "security": ("parlons", "Security and privacy"), "help": ("parlons", "Troubleshooting"),
}

MARK = '<img src="assets/img/parlons-icon.png" width="512" height="512" alt="" decoding="async">'
FAVICON = "assets/img/parlons-icon.png"
FONTS = ('<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
         '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@1&family=Manrope:wght@400;500;600;700;800&display=swap">')


# ---------------------------------------------------------------- inline Markdown

CODE = re.compile(r"(`+)(.+?)\1")
AUTOLINK = re.compile(r"&lt;(https?://[^\s&]+(?:&amp;[^\s&]+)*)&gt;")
LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")
BOLD = re.compile(r"\*\*(.+?)\*\*")
# ASCII word chars only, so an italic can sit right against Chinese text
ITALIC = re.compile(r"(?<![A-Za-z0-9_*])\*(?![\s*])([^*\n]+?)(?<![\s*])\*(?![A-Za-z0-9_*])")


def href(url):
    if url.startswith(("http://", "https://", "#", "mailto:")):
        return url
    base, _, frag = url.partition("#")
    name = os.path.basename(base)
    if name in MD_TO_PAGE:
        return MD_TO_PAGE[name] + ("#" + frag if frag else "")
    return url


def inline(text):
    """Code spans are held as placeholders while the rest is formatted, so **bold `code`** works."""
    spans = []

    def hold(m):
        spans.append("<code>" + html.escape(m.group(2).strip()) + "</code>")
        return "\x00%d\x00" % (len(spans) - 1)
    t = _inline_text(CODE.sub(hold, text))
    return re.sub(r"\x00(\d+)\x00", lambda m: spans[int(m.group(1))], t)


def _inline_text(t):
    t = html.escape(t, quote=False)
    t = AUTOLINK.sub(lambda m: '<a href="%s" target="_blank" rel="noopener">%s</a>' % (m.group(1), m.group(1)), t)

    def link(m):
        u = href(html.unescape(m.group(2)))
        ext = u.startswith("http")
        label = MD_TITLE.get(m.group(1), m.group(1))     # a manual linked by file name shows its name
        return '<a href="%s"%s>%s</a>' % (html.escape(u), ' target="_blank" rel="noopener"' if ext else "", label)
    t = LINK.sub(link, t)
    t = BOLD.sub(r"<strong>\1</strong>", t)
    t = ITALIC.sub(r"<em>\1</em>", t)
    return t


def slug(text, used):
    """GitHub-style anchor: lowercase, drop punctuation, spaces to hyphens; repeats get -1, -2."""
    plain = re.sub(r"[`*]", "", re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)).strip().lower()
    s = re.sub(r"[^\w\- ]", "", plain).replace(" ", "-")
    base, n = s, 1
    while s in used:
        s = "%s-%d" % (base, n); n += 1
    used.add(s)
    return s


# ---------------------------------------------------------------- block Markdown

LIST_ITEM = re.compile(r"^( *)([-*+]|\d+\.) +(.*)$")
FENCE = re.compile(r"^ *(```|~~~)")


def indent_of(s):
    return len(s) - len(s.lstrip(" "))


def split_row(line):
    s = line.strip()
    s = s[1:] if s.startswith("|") else s
    s = s[:-1] if s.endswith("|") and not s.endswith("\\|") else s
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", s)]


class Doc:
    def __init__(self, ids=None):
        self.used = set()
        self.toc = []        # (level, id, text)
        self.ids = []        # every heading id, in order
        self.given = list(ids) if ids is not None else None   # a translation reuses the English ids


def render(lines, doc, top=False):
    out, i, para = [], 0, []

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(s.strip() for s in para)) + "</p>")
            para.clear()

    while i < len(lines):
        ln = lines[i]
        s = ln.strip()
        if not s:
            flush(); i += 1; continue
        if FENCE.match(ln):
            flush()
            ind, mark = indent_of(ln), FENCE.match(ln).group(1)
            j, code = i + 1, []
            while j < len(lines) and not lines[j].strip().startswith(mark):
                code.append(lines[j][ind:] if lines[j][:ind].strip() == "" else lines[j].lstrip())
                j += 1
            out.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
            i = j + 1; continue
        if s.startswith("<div") or s.startswith("<!--"):
            flush()
            closing = "</div" if s.startswith("<div") else "-->"
            while i < len(lines):
                out.append(lines[i].strip())
                if closing in lines[i]:
                    i += 1; break
                i += 1
            continue
        m = re.match(r"^(#{1,4}) +(.*?) *#*$", s)
        if m and indent_of(ln) < 4:
            flush()
            level, text = len(m.group(1)), m.group(2)
            fixed = re.match(r"^(.*?)\s*\{#([\w-]+)\}$", text)
            if fixed:
                text, hid = fixed.group(1), fixed.group(2); doc.used.add(hid)
            else:
                hid = slug(text, doc.used)
            if doc.given is not None:
                if not doc.given:
                    raise SystemExit("translation has more headings than the English: %r" % text)
                hid = doc.given.pop(0)
            doc.ids.append(hid)
            if top and level <= 2:
                doc.toc.append((level, hid, text))
            num = re.match(r"^(\d+(?:\.\d+)*\.?)\s+(.*)$", text) if level == 2 else None
            body = ('<span class="n">%s</span>%s' % (html.escape(num.group(1).rstrip(".")), inline(num.group(2)))) if num else inline(text)
            out.append('<h%d id="%s">%s</h%d>' % (level, hid, body, level))
            i += 1; continue
        if re.fullmatch(r"(-{3,}|\*{3,}|_{3,})", s):
            flush(); out.append("<hr>"); i += 1; continue
        if s.startswith(">"):
            flush()
            block = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                block.append(re.sub(r"^\s*> ?", "", lines[i])); i += 1
            first = block[0] if block else ""
            cls = "note warn" if re.match(r"\*\*(Important|Warning|Never|Do not)", first) else "note"
            out.append('<div class="%s">%s</div>' % (cls, render(block, doc)))
            continue
        if s.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i])); i += 1
            body = [r for r in rows[1:] if not all(re.fullmatch(r":?-+:?", c or "-") for c in r)]
            t = '<div class="tablewrap"><table><thead><tr>' + "".join("<th>%s</th>" % inline(c) for c in rows[0]) + "</tr></thead><tbody>"
            for r in body:
                t += "<tr>" + "".join("<td>%s</td>" % inline(c) for c in r) + "</tr>"
            out.append(t + "</tbody></table></div>")
            continue
        lm = LIST_ITEM.match(ln)
        if lm and indent_of(ln) < 4:
            flush()
            html_list, i = render_list(lines, i, doc)
            out.append(html_list); continue
        para.append(ln); i += 1
    flush()
    return "\n".join(out)


def render_list(lines, i, doc):
    first = LIST_ITEM.match(lines[i])
    base = indent_of(lines[i])
    ordered = first.group(2)[0].isdigit()
    start = int(first.group(2)[:-1]) if ordered else 1
    items = []
    while i < len(lines):
        m = LIST_ITEM.match(lines[i])
        if not m or indent_of(lines[i]) != base or m.group(2)[0].isdigit() != ordered:
            break
        width = len(m.group(1)) + len(m.group(2)) + 1
        body = [m.group(3)]
        i += 1
        while i < len(lines):
            nxt = lines[i]
            if not nxt.strip():
                # a blank line continues the item only if indented content follows
                k = i
                while k < len(lines) and not lines[k].strip():
                    k += 1
                if k < len(lines) and indent_of(lines[k]) > base:
                    body.extend([""] * (k - i)); i = k; continue
                break
            if indent_of(nxt) > base:
                body.append(nxt[min(width, indent_of(nxt)):]); i += 1; continue
            if LIST_ITEM.match(nxt) or nxt.strip().startswith(("|", ">", "#", "```")):
                break
            body.append(nxt.strip()); i += 1     # lazy continuation of the item's paragraph
        inner = render(body, doc)
        if inner.startswith("<p>"):
            inner = inner[3:].replace("</p>", "", 1)       # the item's first paragraph sits on the marker line
        box = re.match(r"^\[([ xX])\] +", inner)
        if box:      # a task-list item: - [ ] / - [x]
            inner = '<span class="box%s" aria-hidden="true"></span>' % (" done" if box.group(1) != " " else "") + inner[box.end():]
        items.append("<li%s>" % (' class="task"' if box else "") + inner + "</li>")
        # skip blank lines between items of the same list
        k = i
        while k < len(lines) and not lines[k].strip():
            k += 1
        if k < len(lines) and LIST_ITEM.match(lines[k]) and indent_of(lines[k]) == base \
                and LIST_ITEM.match(lines[k]).group(2)[0].isdigit() == ordered:
            i = k
    if ordered:
        attr = ' style="counter-reset:step %d" start="%d"' % (start - 1, start) if start != 1 else ""
        return "<ol%s>%s</ol>" % (attr, "".join(items)), i
    return "<ul>%s</ul>" % "".join(items), i


def drop_contents(lines):
    """The manuals carry their own '## Contents' list; the site shows a contents rail instead."""
    out, skip = [], False
    for ln in lines:
        if re.match(r"^## +(Contents|Table of contents|目录|Содержание|Зміст)\s*$", ln.strip(), re.I):
            skip = True; continue
        if skip and re.match(r"^#{1,2} ", ln):
            skip = False
        if skip:
            continue
        out.append(ln)
    return out


def expand(text):
    for k, v in LINKS.items():
        text = text.replace("{{%s}}" % k, v)
    left = re.findall(r"\{\{[A-Z_]+\}\}", text)
    if left:
        raise SystemExit("unknown placeholder(s): %s" % ", ".join(sorted(set(left))))
    return text


# ---------------------------------------------------------------- page chrome

def stylesheet(name, root=""):
    """A changed stylesheet gets a new URL even when the host caches static assets."""
    with open(os.path.join(HERE, "site", name), "rb") as f:
        version = hashlib.sha256(f.read()).hexdigest()[:12]
    return "%sassets/%s?v=%s" % (root, name, version)


def head(title, desc, root, lang="en", rel=None):
    hl = dict((c, h) for c, h, _, _ in LANGS)[lang]
    alts = "".join('<link rel="alternate" hreflang="%s" href="%s%s">' % (h, root, lpath(rel, c)) for c, h, _, _ in LANGS) if rel in TRANSLATED else ""
    return ("<!doctype html>\n<html lang=\"%s\"><head><meta charset=\"utf-8\">"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
            "<title>%s</title><meta name=\"description\" content=\"%s\">"
            "<meta property=\"og:title\" content=\"%s\"><meta property=\"og:description\" content=\"%s\">"
            "<meta name=\"theme-color\" content=\"#2A2E33\"><link rel=\"icon\" href=\"%s\">%s%s"
            "<link rel=\"stylesheet\" href=\"%s\"></head><body>\n"
            % (hl, html.escape(title), html.escape(desc), html.escape(title), html.escape(desc), root + FAVICON, FONTS, alts, stylesheet("site.css", root)))


# Remembers an explicit language choice; the English home page uses it (or the browser's
# language, on a first visit) to open the right translation.
LANG_JS = """<script>
document.querySelectorAll('.langs a[data-lang]').forEach(function(a){a.addEventListener('click',function(){try{localStorage.setItem('parlons-lang',a.dataset.lang)}catch(e){}})});
</script>"""
HOME_JS = """<script>
(function(){var p=null;try{p=localStorage.getItem('parlons-lang')}catch(e){}
var l=p||((navigator.languages&&navigator.languages[0])||navigator.language||'').toLowerCase().slice(0,2);
if(l==='zh'||l==='ru'||l==='uk')location.replace('index.'+l+'.html');})();
</script>"""


def bar(root, current, lang="en", rel=None):
    t = STR[lang]
    links = [(lpath("guide/index.html", lang), t["nav_start"], "index", ""), (lpath("guide/parlons.html", lang), "Parlons!", "parlons", ""),
             (lpath("guide/cloud.html", lang), "Cloud", "cloud", ""), (lpath("guide/node.html", lang), "Node", "node", ""),
             (LINKS["REPO"], t["nav_source"], "", "opt")]
    nav = "".join('<a%s href="%s"%s>%s</a>' % (
        (' class="%s"' % " ".join(c for c in (cls, "on" if key == current else "") if c)) if (cls or key == current) else "",
        u if u.startswith("http") else root + u, ' target="_blank" rel="noopener"' if u.startswith("http") else "", txt)
        for u, txt, key, cls in links)
    langs = ""
    if rel in TRANSLATED:
        here = {"en": "EN", "zh": "中文", "ru": "РУС", "uk": "УКР"}[lang]
        items = "".join('<a%s data-lang="%s" lang="%s" href="%s%s">%s</a>' % (' class="on"' if c == lang else "", c, h, root, lpath(rel, c), n)
                        for c, h, _, n in LANGS)
        langs = '<details class="langs"><summary aria-label="%s">%s</summary><div>%s</div></details>' % (t["lang"], here, items)
    return ('<header class="bar"><div class="wrap"><a class="brand" href="%s%s">%s<span>Parlons!<small>%s</small></span></a>'
            '<nav aria-label="Site">%s</nav>%s</div></header>\n' % (root, lpath("index.html", lang), MARK.replace('src="assets/', 'src="' + root + 'assets/'), t["tagline"], nav, langs))


def foot(root, lang="en"):
    t = STR[lang]
    return ('<footer class="foot"><div class="wrap"><nav aria-label="Footer"><a href="%s%s">%s</a><a href="%s%s">%s</a>'
            '<a href="%s%s">%s</a><a href="%s%s">%s</a><a href="%s%s">%s</a>'
            '<a href="%s" target="_blank" rel="noopener">%s</a><a href="%s" target="_blank" rel="noopener">%s</a>'
            '<a href="%s" target="_blank" rel="noopener">%s</a></nav>'
            '<p>%s</p><nav aria-label="Ecosystem"><a href="https://github.com/eurobuddha/maxima">Maxima</a><a href="https://minima.global/">minima.global ↗</a><a href="https://eurobuddha.com/">eurobuddha.com ↗</a></nav></div></footer>\n%s' % (root, lpath("guide/index.html", lang), t["nav_start"], root, lpath("guide/parlons.html", lang), t["foot_parlons"],
                                               root, lpath("guide/cloud.html", lang), t["foot_cloud"], root, lpath("guide/node.html", lang), t["foot_node"], root, lpath("guide/privacy.html", lang), t["foot_privacy"],
                                               LINKS["REPO"], t["nav_source"], LINKS["RELEASES"], t["foot_releases"], LINKS["ISSUES"], t["foot_report"],
                                               t["foot_text"], LANG_JS))


TOC_JS = """<script>
(function(){var links=[].slice.call(document.querySelectorAll('.toc a[href^="#"]'));if(!links.length||!('IntersectionObserver' in window))return;
var map={};links.forEach(function(a){map[a.getAttribute('href').slice(1)]=a});var cur=null;
var io=new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){var a=map[e.target.id];if(a&&a!==cur){if(cur)cur.classList.remove('on');a.classList.add('on');cur=a;
var t=a.closest('.toc');if(t){var r=a.getBoundingClientRect(),q=t.getBoundingClientRect();if(r.top<q.top||r.bottom>q.bottom)a.scrollIntoView({block:'nearest'})}}}})},{rootMargin:'-80px 0px -70% 0px'});
Object.keys(map).forEach(function(id){var h=document.getElementById(id);if(h)io.observe(h)})})();
</script>"""


def manual_page(name, md, lang="en", ids=None):
    t = STR[lang]
    lines = md.split("\n")
    title_line = next(l for l in lines if l.startswith("# "))
    title = title_line[2:].strip()
    body_lines = lines[lines.index(title_line) + 1:]
    doc = Doc(ids)
    body = render(drop_contents(body_lines), doc, top=True)
    if doc.given:
        raise SystemExit("%s (%s): translation has %d fewer headings than the English" % (name, lang, len(doc.given)))
    body = localize_links(body, lang, in_guide=True)
    toc_items = []
    for level, hid, text in doc.toc:
        if level == 1:
            toc_items.append('<li class="part">%s</li>' % inline(text))
        else:
            toc_items.append('<li><a href="#%s">%s</a></li>' % (hid, inline(text)))
    toc = "".join(toc_items)
    mobile = "".join(x for x in toc_items if 'class="part"' not in x)
    order = [p[0] for p in PAGES if p[3] == "manual"]
    idx = order.index(name)
    titles = {k: t["foot_" + k] for k in order}
    pg = lambda k: lpath("guide/%s.html" % k, lang)[6:]
    prev_ = order[idx - 1] if idx > 0 else None
    next_ = order[idx + 1] if idx + 1 < len(order) else None
    pager = '<nav class="pager" aria-label="%s">%s%s</nav>' % (t["manuals"],
        '<a href="%s"><span>%s</span>%s</a>' % (pg(prev_), t["prev_manual"], titles[prev_]) if prev_ else '<a href="%s"><span>%s</span>%s</a>' % (pg("index"), t["back_to"], t["nav_start"]),
        '<a class="next" href="%s"><span>%s</span>%s</a>' % (pg(next_), t["next_manual"], titles[next_]) if next_ else '<a class="next" href="%s"><span>%s</span>%s</a>' % (pg("privacy"), t["read"], t["foot_privacy"]))
    rel = "guide/%s.html" % name
    page = (head(title + " · Parlons!", t["blurb_" + name], "../", lang, rel) + bar("../", name, lang, rel) +
            '<div class="wrap doc"><aside class="toc" aria-label="%s"><span class="label">%s</span><ol>%s</ol></aside>'
            '<main class="prose" id="top"><div class="doc-head"><span class="label">%s</span><h1>%s</h1><p class="lede">%s</p></div>'
            '<details class="toc-m"><summary>%s</summary><ol>%s</ol></details>%s%s</main></div>'
            '<a class="top-link" href="#top">%s</a>' % (t["contents"], t["contents"], toc, t["manual"], inline(title), t["blurb_" + name],
                                                         t["contents"], mobile, body, pager, t["top"])
            + foot("../", lang) + TOC_JS + "</body></html>\n")
    return page, doc


def guide_page(name, md, lang="en"):
    lines = md.split("\n")
    title_line = next(l for l in lines if l.startswith("# "))
    title = title_line[2:].strip()
    doc = Doc()
    body = render(lines[lines.index(title_line) + 1:], doc)
    body = localize_links(body, lang, in_guide=True)
    rel = "guide/%s.html" % name
    setup = name.startswith("start-")
    back = '<a class="guide-back" href="../%s#start">← %s</a>' % (lpath("index.html", lang), STR[lang]["nav_start"]) if setup else ""
    contents = ""
    if name != "privacy":
        headings = re.findall(r'<h2 id="([^"]+)">(.*?)</h2>', body)
        contents = '<nav class="guide-jumps" aria-label="%s">%s</nav>' % (STR[lang]["contents"], "".join('<a href="#%s">%s</a>' % h for h in headings))
    page = (head(title + " · Parlons!", STR[lang]["desc_index" if setup else "desc_" + name], "../", lang, rel) + bar("../", "index" if setup else name, lang, rel) +
            '<div class="wrap"><main class="prose guide" id="main">%s<div class="doc-head"><h1>%s</h1></div>%s%s</main></div>'
            % (back, inline(title), contents, body) + foot("../", lang) + "</body></html>\n")
    return page, doc


def setup_pages(md):
    """Derive focused setup guides from the same translated getting-started source.

    Explicit anchors are shared by all languages and preserve existing incoming links.
    Everyday use, backup and help follow each setup so the reader has a complete path.
    """
    headings = list(re.finditer(r'^## (.+?) \{#([\w-]+)\}\s*$', md, re.M))
    sections = {}
    for i, match in enumerate(headings):
        end = headings[i + 1].start() if i + 1 < len(headings) else len(md)
        sections[match.group(2)] = (match.group(1), md[match.end():end].strip())
    common = "\n\n".join("## %s {#%s}\n\n%s" % (sections[k][0], k, sections[k][1])
                             for k in ("add-a-friend", "everyday-use", "backups", "need-more"))
    return {name: "# %s\n\n%s\n\n%s\n" % (sections[anchor][0], re.sub(r'^### ', '## ', sections[anchor][1], flags=re.M), common)
            for name, anchor in START_ROUTES.items()}


def redirect(target, title):
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>%s · Parlons!</title>'
            '<meta http-equiv="refresh" content="0; url=%s"><link rel="canonical" href="%s"></head>'
            '<body><p>This page moved: <a href="%s">%s</a>.</p></body></html>\n' % (html.escape(title), target, target, target, target))


def main():
    out_dir = os.path.expanduser("~/Projects/web/parlons-site")
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
    gdir = os.path.join(out_dir, "guide")
    os.makedirs(gdir, exist_ok=True)
    adir = os.path.join(out_dir, "assets")
    if os.path.isdir(adir):
        shutil.rmtree(adir)
    shutil.copytree(os.path.join(HERE, "site", "img"), os.path.join(adir, "img"))
    shutil.copy(os.path.join(HERE, "site", "site.css"), os.path.join(adir, "site.css"))
    shutil.copy(os.path.join(HERE, "site", "landing.css"), os.path.join(adir, "landing.css"))

    anchors = {}
    for name, src, navlabel, kind in PAGES:
        md = expand(open(src, encoding="utf-8").read())
        page, doc = manual_page(name, md) if kind == "manual" else guide_page(name, md)
        anchors[name] = doc.toc
        with open(os.path.join(gdir, name + ".html"), "w", encoding="utf-8") as f:
            f.write(page)
        print("wrote", os.path.join(gdir, name + ".html"))
        if "guide/%s.html" % name in TRANSLATED:
            for lang, _, suf, _ in LANGS[1:]:
                if kind == "guide":
                    md = expand(open(lsrc(lang, src, name + ".md"), encoding="utf-8").read())
                    tpage = guide_page(name, md, lang)[0]
                else:
                    md = expand(open(os.path.join(HERE, "i18n", lang, "manuals", MANUAL_SRC[name]), encoding="utf-8").read())
                    tpage = manual_page(name, md, lang, doc.ids)[0]
                with open(os.path.join(gdir, name + suf + ".html"), "w", encoding="utf-8") as f:
                    f.write(tpage)

    for lang, _, suf, _ in LANGS:
        src = lsrc(lang, os.path.join(HERE, "index.md"), "index.md")
        for route, md in setup_pages(expand(open(src, encoding="utf-8").read())).items():
            name = "start-" + route
            with open(os.path.join(gdir, name + suf + ".html"), "w", encoding="utf-8") as f:
                f.write(guide_page(name, md, lang)[0])

    for old, target in REDIRECTS.items():
        if target is None:
            continue
        page, phrase = target
        hit = next((hid for lvl, hid, text in anchors[page] if phrase.lower() in text.lower()), None)
        if hit is None:
            raise SystemExit("redirect %s.html: no heading containing %r in %s" % (old, phrase, page))
        with open(os.path.join(gdir, old + ".html"), "w", encoding="utf-8") as f:
            f.write(redirect("%s.html#%s" % (page, hit), old))
    for old, target in (("ios", "guide/cloud.html"), ("privacy", "guide/privacy.html"), ("support", "guide/index.html")):
        with open(os.path.join(out_dir, old + ".html"), "w", encoding="utf-8") as f:
            f.write(redirect(target, old))

    for lang, _, suf, _ in LANGS:
        landing = open(lsrc(lang, os.path.join(HERE, "site", "landing.html"), "landing.html"), encoding="utf-8").read()
        # Translate content links before inserting chrome, whose language selector
        # and hreflang links deliberately point to other languages.
        landing = localize_links(landing, lang)
        landing = landing.replace("{{{MARK}}}", MARK).replace("{{{HEAD}}}", head(
            STR[lang]["home_title"], STR[lang]["home_desc"], "", lang, "index.html").replace(
            "</head>", '<link rel="stylesheet" href="%s">' % stylesheet("landing.css") + (HOME_JS if lang == "en" else "") + "</head>")
        ).replace("{{{BAR}}}", bar("", "home", lang, "index.html")).replace("{{{FOOT}}}", foot("", lang))
        landing = expand(landing)
        with open(os.path.join(out_dir, "index%s.html" % suf), "w", encoding="utf-8") as f:
            f.write(landing)
        print("wrote", os.path.join(out_dir, "index%s.html" % suf))
    check_links(out_dir)
    with open(os.path.join(out_dir, "404.html"), "w", encoding="utf-8") as f:
        f.write(head("Not found · Parlons!", "This page does not exist.", "/") + bar("/", "") +
                '<div class="wrap"><main class="prose guide"><div class="doc-head"><h1>That page is not here.</h1></div>'
                '<p>Try <a href="/guide/index.html">Get started</a> or one of the manuals.</p></main></div>' + foot("/") + "</body></html>\n")


def check_links(out_dir):
    """Fail the build on any internal link to a missing page or a missing #anchor."""
    ids, pages = {}, []
    for dirpath, _, files in os.walk(out_dir):
        for fn in files:
            if fn.endswith(".html"):
                path = os.path.join(dirpath, fn)
                text = open(path, encoding="utf-8").read()
                ids[os.path.normpath(path)] = set(re.findall(r'\sid="([^"]+)"', text))
                pages.append((path, text))
    bad = []
    for path, text in pages:
        if 'http-equiv="refresh"' in text:
            targets = re.findall(r'url=([^"]+)"', text)
        else:
            targets = re.findall(r'href="([^"]+)"', text)
        for t in targets:
            if t.startswith(("http:", "https:", "mailto:", "data:", "/")):
                continue
            file_, _, frag = t.partition("#")
            file_ = file_.partition("?")[0]
            dest = os.path.normpath(os.path.join(os.path.dirname(path), file_)) if file_ else os.path.normpath(path)
            if not os.path.exists(dest):
                bad.append("%s -> %s (no such file)" % (os.path.relpath(path, out_dir), t))
            elif frag and dest.endswith(".html") and frag not in ids.get(dest, set()):
                bad.append("%s -> %s (no such anchor)" % (os.path.relpath(path, out_dir), t))
    if bad:
        raise SystemExit("broken links:\n  " + "\n  ".join(sorted(set(bad))))
    print("links: all internal links and anchors resolve")


if __name__ == "__main__":
    main()
