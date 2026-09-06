#!/usr/bin/env python3
"""Render the user guide (docs/guide/*.md, the single source) into the website.

    python3 docs/guide/build.py                 # -> ~/Projects/web/parlons-site/guide/*.html
    python3 docs/guide/build.py --out /some/dir

No dependencies: a small Markdown subset (headings, paragraphs, bold, code, links, lists,
fenced code, tables, > notes, ---) is all the guide uses. Download links live in LINKS below so
a release bump is one edit here; every page refers to them as {{ANDROID_APK}} etc. The same
placeholders are expanded into the Markdown copies committed in the repo (docs/guide/*.md are
the source; GitHub renders the placeholders literally, so README links point at the website).
"""
import html
import os
import re
import sys

LINKS = {
    "ANDROID_VERSION": "0.6.56",
    "ANDROID_APK": "https://github.com/eurobuddha/maxima/releases/download/v0.6.56/parlons-0.6.56.apk",
    "PORTAL_VERSION": "0.2.1",
    "PORTAL_APK": "https://github.com/eurobuddha/maxima/releases/download/portal-v0.2.1/parlons-cloud-portal-0.2.1-release.apk",
    "DESKTOP_VERSION": "1.5.36",
    "DESKTOP_DMG": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.36/MaximaNode-1.5.36.dmg",
    "DESKTOP_MSI": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.36/MaximaNode-1.5.36.msi",
    "DESKTOP_DEB": "https://github.com/eurobuddha/maxima/releases/download/desktop-v1.5.36/maximanode_1.5.36_amd64.deb",
    "RELEASES": "https://github.com/eurobuddha/maxima/releases",
    "REPO": "https://github.com/eurobuddha/maxima",
    "ISSUES": "https://github.com/eurobuddha/maxima/issues",
    "INSTALL_SH": "https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh",
    "INSTALL_PS1": "https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.ps1",
    "PANDAAPPS": "https://github.com/eurobuddha/minima-core-android-pandaapps",
    "CLOUD_VERSION": "0.11.40",
}

PAGES = [  # (file, nav title, page title)
    ("index", "Get started", "Get started with Parlons"),
    ("android", "Android", "Parlons on Android"),
    ("iphone", "iPhone", "Parlons on iPhone"),
    ("mac", "Mac", "Parlons on a Mac"),
    ("windows", "Windows", "Parlons on Windows"),
    ("linux", "Linux", "Parlons on Linux"),
    ("your-account", "Your account", "Run your own Parlons account"),
    ("hosted", "Hosted accounts", "Hosted accounts"),
    ("node", "Parlons Node", "Run a Parlons Node"),
    ("security", "Security", "Your seed, your keys, what Parlons protects"),
    ("help", "Help", "Help and troubleshooting"),
    ("privacy", "Privacy", "Privacy"),
]
NAV = [("index", "Get started"), ("android", "Android"), ("iphone", "iPhone"), ("mac", "Computer"),
       ("your-account", "Your account"), ("help", "Help")]

CSS = """
  :root{--cream:#FBF6EE;--cream-2:#F4ECDF;--card:#FFFFFF;--ink:#1B1712;--ink-2:#5B5347;--faint:#9A9081;--line:#E7DECF;--orange:#EF4A1E;--orange-soft:#FFF1EA;--blue:#2E6BE6;--blue-soft:#EAF1FE;--shadow-sm:0 2px 10px rgba(60,40,20,.06);--maxw:860px}
  *{box-sizing:border-box} body{margin:0;background:var(--cream);color:var(--ink);font-family:"Manrope",system-ui,-apple-system,sans-serif;font-size:17px;line-height:1.6;-webkit-font-smoothing:antialiased}
  .wrap{max-width:var(--maxw);margin:0 auto;padding:0 24px} a{color:var(--blue);text-decoration:none} a:hover{text-decoration:underline}
  header{position:sticky;top:0;z-index:5;background:rgba(251,246,238,.88);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)} .nav{display:flex;align-items:center;gap:16px;height:60px;overflow-x:auto;white-space:nowrap}
  .brand{font-weight:800;color:var(--ink);letter-spacing:-.02em;font-size:19px;flex:none} .sp{flex:1} .lk{color:var(--ink-2);font-weight:600;font-size:14px;flex:none} .lk.on{color:var(--ink);border-bottom:2px solid var(--orange)}
  h1{font-size:clamp(32px,6vw,46px);letter-spacing:-.03em;line-height:1.06;margin:44px 0 12px;font-weight:800;text-wrap:balance}
  h2{font-size:25px;letter-spacing:-.02em;margin:44px 0 10px;font-weight:800;line-height:1.15} h3{font-size:18px;margin:26px 0 6px;font-weight:700}
  p{margin:0 0 14px} li{margin:5px 0} ul,ol{padding-left:24px;margin:0 0 14px}
  code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.9em;background:var(--cream-2);padding:1px 5px;border-radius:5px;word-break:break-all}
  pre{background:#1B1712;color:#F4ECDF;border-radius:12px;padding:14px 16px;overflow-x:auto;margin:0 0 16px;font-size:14px;line-height:1.5} pre code{background:none;padding:0;color:inherit;word-break:normal;font-size:inherit}
  .note{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--orange);border-radius:12px;padding:12px 16px;margin:0 0 16px;box-shadow:var(--shadow-sm)} .note p:last-child{margin:0}
  .tablewrap{overflow-x:auto;margin:0 0 16px;border:1px solid var(--line);border-radius:12px;background:var(--card)} table{border-collapse:collapse;width:100%;font-size:15px} td,th{border-bottom:1px solid var(--line);padding:9px 12px;text-align:left;vertical-align:top} th{color:var(--faint);font-size:12px;letter-spacing:.1em;text-transform:uppercase} tr:last-child td{border-bottom:0}
  hr{border:0;border-top:1px solid var(--line);margin:34px 0}
  .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:14px;margin:0 0 20px} .cards a{display:block;background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px 20px;color:var(--ink);box-shadow:var(--shadow-sm)} .cards a:hover{border-color:var(--ink);text-decoration:none} .cards b{display:block;font-size:17px;margin-bottom:4px} .cards span{color:var(--ink-2);font-size:14px}
  footer{margin:64px 0 40px;color:var(--faint);font-size:14px;border-top:1px solid var(--line);padding-top:20px} footer a{color:var(--ink-2);margin-right:14px}
  @media (max-width:600px){ .wrap{padding:0 18px} h2{font-size:22px} }
"""

HEAD = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title} — Parlons!</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&display=swap">
<style>{css}</style></head><body>
<header><div class="wrap nav"><a class="brand" href="../index.html">Parlons!</a><span class="sp"></span>{nav}</div></header>
<main class="wrap">
"""
FOOT = """</main>
<footer class="wrap">{navlinks}<a href="{repo}" target="_blank" rel="noopener">Source</a><a href="privacy.html">Privacy</a><br><br>Parlons! — a conversation with no center, on Maxima &amp; the Minima blockchain. No account with us. No profits. No data leak.</footer>
</body></html>
"""

INLINE_CODE = re.compile(r"`([^`]+)`")
BOLD = re.compile(r"\*\*(.+?)\*\*")
LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")


def inline(text):
    """Escape, then bring back the few inline constructs."""
    parts = []
    pos = 0
    for m in INLINE_CODE.finditer(text):
        parts.append(_inline_no_code(text[pos:m.start()]))
        parts.append("<code>" + html.escape(m.group(1)) + "</code>")
        pos = m.end()
    parts.append(_inline_no_code(text[pos:]))
    return "".join(parts)


def _inline_no_code(t):
    t = html.escape(t, quote=False)
    t = LINK.sub(lambda m: '<a href="%s"%s>%s</a>' % (
        m.group(2), ' target="_blank" rel="noopener"' if m.group(2).startswith("http") else "", m.group(1)), t)
    t = BOLD.sub(r"<b>\1</b>", t)
    return t


def render(md):
    out = []
    lines = md.split("\n")
    i = 0
    para = []

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(s.strip() for s in para)) + "</p>")
            para.clear()

    while i < len(lines):
        ln = lines[i]
        s = ln.strip()
        if s.startswith("```"):
            flush()
            j = i + 1
            code = []
            while j < len(lines) and not lines[j].strip().startswith("```"):
                code.append(lines[j])
                j += 1
            out.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
            i = j + 1
            continue
        if s.startswith("<div") or s.startswith("</div"):
            flush(); out.append(s); i += 1; continue
        if s.startswith("#"):
            flush()
            level = len(s) - len(s.lstrip("#"))
            text = s[level:].strip()
            slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
            out.append('<h%d id="%s">%s</h%d>' % (level, slug, inline(text), level))
            i += 1; continue
        if s == "---":
            flush(); out.append("<hr>"); i += 1; continue
        if s.startswith(">"):
            flush()
            block = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                block.append(lines[i].strip()[1:].strip()); i += 1
            out.append('<div class="note">' + render("\n".join(block)) + "</div>")
            continue
        if s.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")]); i += 1
            body = [r for r in rows[1:] if not all(re.fullmatch(r":?-+:?", c or "-") for c in r)]
            t = '<div class="tablewrap"><table><tr>' + "".join("<th>%s</th>" % inline(c) for c in rows[0]) + "</tr>"
            for r in body:
                t += "<tr>" + "".join("<td>%s</td>" % inline(c) for c in r) + "</tr>"
            out.append(t + "</table></div>")
            continue
        if re.match(r"^(-|\d+\.)\s", s):
            flush()
            ordered = s[0].isdigit()
            items = []
            while i < len(lines) and re.match(r"^\s*(-|\d+\.)\s", lines[i]):
                item = re.sub(r"^\s*(-|\d+\.)\s", "", lines[i])
                i += 1
                while i < len(lines) and lines[i].startswith("  ") and lines[i].strip() and not re.match(r"^\s*(-|\d+\.)\s", lines[i]):
                    item += " " + lines[i].strip(); i += 1
                items.append(item)
            tag = "ol" if ordered else "ul"
            out.append("<%s>" % tag + "".join("<li>%s</li>" % inline(x) for x in items) + "</%s>" % tag)
            continue
        if s == "":
            flush(); i += 1; continue
        para.append(ln); i += 1
    flush()
    return "\n".join(out)


def expand(text):
    for k, v in LINKS.items():
        text = text.replace("{{%s}}" % k, v)
    return text


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.expanduser("~/Projects/web/parlons-site/guide")
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
    os.makedirs(out_dir, exist_ok=True)
    navlinks = "".join('<a href="%s.html">%s</a>' % (f, t) for f, t in NAV)
    for fname, navtitle, title in PAGES:
        src = os.path.join(here, fname + ".md")
        md = expand(open(src, encoding="utf-8").read())
        nav = "".join('<a class="lk%s" href="%s.html">%s</a>' % (" on" if f == fname or (fname in ("windows", "linux") and f == "mac") else "", f, t) for f, t in NAV)
        page = HEAD.format(title=html.escape(title), css=CSS, nav=nav) + render(md) + FOOT.format(navlinks=navlinks, repo=LINKS["REPO"])
        with open(os.path.join(out_dir, fname + ".html"), "w", encoding="utf-8") as f:
            f.write(page)
        print("wrote", os.path.join(out_dir, fname + ".html"))


if __name__ == "__main__":
    main()
