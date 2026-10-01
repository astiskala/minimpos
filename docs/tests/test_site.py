"""Validate the standalone localized site with Python's standard library.

Run from any directory: python3 /absolute/path/to/docs/tests/test_site.py
No server, browser, package installation or network access is required.
"""

from html.parser import HTMLParser
from pathlib import Path
import re
import struct
import unittest
from urllib.parse import unquote, urlsplit

DOCS = Path(__file__).resolve().parents[1]
BASE = "https://astiskala.github.io/minimpos/"
LANGUAGES = ("en", "zh-CN", "ja")
GUIDE_SECTIONS = {
    "before", "try", "key", "install", "connect", "business", "products",
    "sell", "api", "tips", "preauth", "more", "live", "trouble",
}


def relative_page(language, guide):
    prefix = "" if language == "en" else language + "/"
    return prefix + ("getting-started.html" if guide else "index.html")


def public_url(language, guide):
    path = relative_page(language, guide)
    return BASE + (path[:-len("index.html")] if not guide else path)


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.tags = []
        self.ids = []
        self.sections = []
        self.code = []
        self.text = []
        self.code_depth = 0
        self.current_code = []
        self.feed(path.read_text(encoding="utf-8"))

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        self.tags.append((tag, values))
        if "id" in values:
            self.ids.append(values["id"])
        if tag == "section" and "id" in values:
            self.sections.append(values["id"])
        if tag == "code":
            self.code_depth += 1
            self.current_code = []

    def handle_endtag(self, tag):
        if tag == "code":
            self.code_depth -= 1
            self.code.append("".join(self.current_code))

    def handle_data(self, value):
        self.text.append(value)
        if self.code_depth:
            self.current_code.append(value)

    def matching(self, tag, **attributes):
        return [attrs for name, attrs in self.tags
                if name == tag and all(attrs.get(k) == v for k, v in attributes.items())]

    def single(self, tag, **attributes):
        matches = self.matching(tag, **attributes)
        if len(matches) != 1:
            raise AssertionError((self.path, tag, attributes, len(matches)))
        return matches[0]


class SiteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pages = {(lang, guide): Page(DOCS / relative_page(lang, guide))
                     for lang in LANGUAGES for guide in (False, True)}

    def resolve_local(self, page, href):
        parsed = urlsplit(href)
        if parsed.scheme or parsed.netloc:
            return None
        target = (page.path.parent / unquote(parsed.path)).resolve() if parsed.path else page.path
        if target.is_dir():
            target /= "index.html"
        self.assertTrue(DOCS in target.parents, (page.path, href))
        self.assertTrue(target.is_file(), (page.path, href))
        if parsed.fragment:
            self.assertEqual(target.suffix, ".html", (page.path, href))
            self.assertIn(unquote(parsed.fragment), Page(target).ids, (page.path, href))
        return target

    def test_page_language_and_metadata(self):
        for (language, guide), page in self.pages.items():
            with self.subTest(path=page.path):
                self.assertEqual(page.single("html")["lang"], language)
                self.assertEqual(page.single("link", rel="canonical")["href"], public_url(language, guide))
                self.assertEqual(page.single("meta", property="og:url")["content"], public_url(language, guide))
                locales = {"en": "en_US", "zh-CN": "zh_CN", "ja": "ja_JP"}
                self.assertEqual(page.single("meta", property="og:locale")["content"], locales[language])
                self.assertEqual({item["content"] for item in page.matching("meta", property="og:locale:alternate")},
                                 {value for key, value in locales.items() if key != language})
                for attributes in ({"name": "description"}, {"property": "og:title"},
                                   {"property": "og:description"}, {"property": "og:image:alt"},
                                   {"name": "twitter:title"}, {"name": "twitter:description"},
                                   {"name": "twitter:image:alt"}):
                    self.assertTrue(page.single("meta", **attributes)["content"])
                for name in ("og:image", "twitter:image"):
                    key = "property" if name.startswith("og:") else "name"
                    self.assertEqual(page.single("meta", **{key: name})["content"], BASE + "images/social.png")

    def test_reciprocal_alternate_links(self):
        for (_, guide), page in self.pages.items():
            alternates = {tag["hreflang"]: tag["href"] for tag in page.matching("link", rel="alternate")}
            expected = {lang: public_url(lang, guide) for lang in LANGUAGES}
            expected["x-default"] = public_url("en", guide)
            self.assertEqual(alternates, expected)

    def test_language_switches_keep_page_type(self):
        for (language, guide), page in self.pages.items():
            links = [tag for tag in page.matching("a") if "hreflang" in tag]
            self.assertEqual(len(links), 3, page.path)
            for link in links:
                target = link["hreflang"]
                self.assertEqual(link["lang"], target)
                self.assertEqual(self.resolve_local(page, link["href"]), DOCS / relative_page(target, guide))
                self.assertEqual(link.get("aria-current"), "page" if target == language else None)

    def test_local_links_fragments_and_assets(self):
        for page in self.pages.values():
            self.assertEqual(len(page.ids), len(set(page.ids)), page.path)
            for tag, attributes in page.tags:
                for attribute in ("src", "href"):
                    if attribute in attributes:
                        self.resolve_local(page, attributes[attribute])
                if tag in ("img", "script") and "src" in attributes:
                    self.assertFalse(urlsplit(attributes["src"]).scheme, page.path)
                if tag == "img":
                    self.assertIn("alt", attributes, page.path)
                    self.assertIn("width", attributes, page.path)
                    self.assertIn("height", attributes, page.path)
            self.assertFalse(page.matching("script"), page.path)
        css = (DOCS / "styles.css").read_text()
        for href in re.findall(r'url\([\'"]?([^\)\'\"]+)', css):
            self.assertFalse(urlsplit(href).scheme)
            self.assertTrue((DOCS / href).is_file(), href)

    def test_complete_guides_preserve_operational_links_and_code(self):
        english = self.pages[("en", True)]
        external = lambda page: {a["href"] for a in page.matching("a") if a["href"].startswith("https:")}
        for language in LANGUAGES:
            page = self.pages[(language, True)]
            self.assertEqual(set(page.sections), GUIDE_SECTIONS, page.path)
            self.assertEqual(page.code, english.code, page.path)
            self.assertEqual(external(page), external(english), page.path)
            self.assertIn("Checkout webservice role", " ".join(page.text))
            self.assertIn("Return adjust authorisation data", " ".join(" ".join(page.text).split()))
            for section in GUIDE_SECTIONS:
                self.assertTrue(page.matching("a", href="#" + section), (page.path, section))

    def test_translated_copy_and_screenshot_disclosures(self):
        for language, marker in [("en", "English demo"), ("zh-CN", "英文演示"), ("ja", "英語のデモ")]:
            page = self.pages[(language, False)]
            text = " ".join(page.text)
            self.assertIn(marker, text, page.path)
            self.assertNotIn('class="phone', page.path.read_text())
            if language != "en":
                self.assertNotIn("The whole checkout", text)
                self.assertNotIn("Everything a small shop needs", text)
                self.assertNotIn("Before you start", " ".join(self.pages[(language, True)].text))
        css = (DOCS / "styles.css").read_text()
        self.assertIn("object-fit: contain", css)
        self.assertNotIn("object-fit: cover", css)
        self.assertNotIn(".phone", css)

    def test_language_and_receipt_guidance(self):
        references = (
            "https://www.nta.go.jp/english/taxes/consumption_tax/pdf/2023/general_04.pdf",
            "https://inv-veri.chinatax.gov.cn/",
        )
        for language in LANGUAGES:
            page = self.pages[(language, True)]
            self.assertIn("language-receipts", page.ids, page.path)
            text = " ".join(" ".join(page.text).split())
            for term in ("Android 12", "Android 13", "税込", "税抜", "適格請求書"):
                self.assertIn(term, text, page.path)
            for reference in references:
                self.assertTrue(page.matching("a", href=reference), page.path)
            # Regulatory caveats belong in the guide, not the marketing page.
            landing = " ".join(self.pages[(language, False)].text)
            self.assertNotIn("適格請求書", landing, page.path)

    def test_documented_app_labels(self):
        # Actual UI wording from values-zh-rCN/strings.xml and values-ja/strings.xml.
        # Keep this test independent of Android tooling and runtime dependencies.
        labels = {
            "zh-CN": ("交易记录", "新建销售", "在收据上填写小费", "待填写小费", "已请求扣款",
                      "支付目标", "同一网络中的终端", "共享至另一台终端", "保存并测试 API 密钥",
                      "重新查询结果", "需处理", "设置 › 税"),
            "ja": ("取引履歴", "領収書にチップを記入", "チップ入力待ち", "キャプチャ要求済み",
                   "プリオーソリ商品", "プリオーソリをキャンセル", "APIキーを保存してテスト",
                   "別の端末に共有", "決済先", "アプリ情報", "設定 › 税"),
        }
        forbidden = {
            "zh-CN": ("历史记录", "新销售", "等待小费", "已申请扣款", "支付去向", "分享给另一台终端"),
            "ja": ("チップ待ち", "キャプチャ申請済み", "別の端末と共有", "アプリについて"),
        }
        for language, expected in labels.items():
            guide = " ".join(self.pages[(language, True)].text)
            for label in expected:
                self.assertIn(label, guide, language)
            for label in forbidden[language]:
                self.assertNotIn(label, guide, language)
            landing = " ".join(self.pages[(language, False)].text)
            self.assertIn(expected[0], landing, language)
            tip = "在收据上填写小费" if language == "zh-CN" else "領収書にチップを記入"
            self.assertIn(tip, landing, language)

    def test_social_image_dimensions(self):
        data = (DOCS / "images/social.png").read_bytes()
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        self.assertEqual(struct.unpack(">II", data[16:24]), (1200, 630))

    def test_terminal_frames_have_no_model_labels(self):
        for model in ("ams1", "s1f2"):
            artwork = (DOCS / f"images/terminal-{model}.svg").read_text()
            self.assertNotIn("<text", artwork)
        self.assertNotIn('M62 28', (DOCS / "images/terminal-ams1.svg").read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
