package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.readText

class SearchMetadataTest {
    private val docs = Path.of(System.getProperty("minimpos.website"))
    private val pages =
        Files.walk(docs).use { paths ->
            paths.filter { it.extension == "html" }.toList().associateWith { Jsoup.parse(it.readText()) }
        }

    @Test
    fun `sitemap contains every published page exactly once at its canonical HTTPS URL`() {
        val parser = Parser.xmlParser().setTrackErrors(1)
        val sitemap = Jsoup.parse(docs.resolve("sitemap.xml").readText(), "", parser)
        assertThat(parser.errors).isEmpty()
        val root = sitemap.select("urlset").single()
        assertThat(root.attr("xmlns")).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        val locations = root.select("url > loc").eachText()
        assertThat(locations).containsNoDuplicates()
        assertThat(locations).containsExactlyElementsIn(pages.values.map { it.select("link[rel=canonical]").single().attr("href") })
        pages.forEach { (path, page) ->
            val relative =
                docs
                    .relativize(path)
                    .toString()
                    .replace('\\', '/')
                    .removeSuffix("index.html")
                    .removeSuffix(".html")
            assertWithMessage(path.toString()).that(page.select("link[rel=canonical]").single().attr("href")).isEqualTo(BASE + relative)
        }
    }

    @Test
    fun `robots allows crawling and advertises the canonical sitemap`() {
        val directives =
            docs
                .resolve("robots.txt")
                .readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { it.substringBefore(':') to it.substringAfter(':').trim() }
                .toList()
        assertThat(directives).containsExactly("User-agent" to "*", "Disallow" to "", "Sitemap" to BASE + "sitemap.xml")
    }

    @Test
    fun `every published site link uses extensionless page URLs`() {
        pages.forEach { (path, page) ->
            page.select("[href], meta[property=og:url]").forEach { element ->
                val reference = if (element.hasAttr("href")) element.attr("href") else element.attr("content")
                val url = URI(BASE).resolve(reference)
                if (url.host == "minimpos.app") {
                    assertWithMessage("$path: $reference").that(url.path.endsWith(".html")).isFalse()
                }
            }
        }
    }

    @Test
    fun `search and share metadata identifies each page consistently without duplicate titles or descriptions`() {
        assertThat(pages.values.map { it.title() }).containsNoDuplicates()
        assertThat(pages.values.map { it.meta("name", "description") }).containsNoDuplicates()
        pages.forEach { (path, page) ->
            assertWithMessage(path.toString()).that(page.select("title")).hasSize(1)
            assertWithMessage(path.toString()).that(page.select("main h1")).hasSize(1)
            assertThat(page.title()).contains("Mini mPOS")
            assertThat(page.meta("property", "og:title")).isEqualTo(page.title())
            assertThat(page.meta("name", "twitter:title")).isEqualTo(page.title())
            val description = page.meta("name", "description")
            assertThat(description).isNotEmpty()
            assertThat(page.meta("property", "og:description")).isEqualTo(description)
            assertThat(page.meta("name", "twitter:description")).isEqualTo(description)
            assertThat(page.meta("property", "og:site_name")).isEqualTo("Mini mPOS")
            assertThat(page.meta("name", "robots")).isEqualTo("max-image-preview:large")
        }
    }

    @Test
    fun `social image dimensions match the actual localized assets`() {
        pages.values.forEach { page ->
            val url = page.meta("property", "og:image")
            assertThat(url).startsWith(BASE)
            val image = ImageIO.read(docs.resolve(url.removePrefix(BASE)).toFile())
            assertThat(page.meta("property", "og:image:width")).isEqualTo(image.width.toString())
            assertThat(page.meta("property", "og:image:height")).isEqualTo(image.height.toString())
            assertThat(page.meta("property", "og:image:type")).isEqualTo("image/png")
        }
    }

    @Test
    fun `home pages expose the visible site name as microdata without adding scripts`() {
        pages.forEach { (path, page) ->
            val sites = page.select("[itemscope][itemtype='https://schema.org/WebSite']")
            if (path.fileName.toString() == "index.html") {
                val site = sites.single()
                assertThat(site.select(".brand [itemprop=name]").single().text()).isEqualTo("Mini mPOS")
                assertThat(site.select("[itemprop=url]").single().attr("href")).isEqualTo(BASE)
                assertThat(page.select("script")).isEmpty()
            } else {
                assertThat(sites).isEmpty()
            }
        }
    }

    @Test
    fun `page policies allow local assets while restricting executable content and credential transmission`() {
        pages.forEach { (path, page) ->
            val policy =
                page
                    .select("meta[http-equiv=Content-Security-Policy]")
                    .single()
                    .attr("content")
                    .split(';')
                    .associate { it.trim().substringBefore(' ') to it.trim().substringAfter(' ') }
            assertThat(policy["default-src"]).isEqualTo("'none'")
            assertThat(policy["script-src"])
                .isEqualTo("'self' 'unsafe-inline' https://static.cloudflareinsights.com https://ajax.cloudflare.com")
            assertThat(policy["script-src-attr"]).isEqualTo("'none'")
            assertThat(policy["style-src"]).isEqualTo("'self'")
            assertThat(policy["img-src"]).isEqualTo("'self'")
            assertThat(policy["manifest-src"]).isEqualTo("'self'")
            assertThat(policy["connect-src"])
                .isEqualTo("https://minimpos.app/cdn-cgi/ https://cloudflareinsights.com/cdn-cgi/rum")
            listOf("form-action", "base-uri", "object-src", "frame-src", "worker-src").forEach {
                assertThat(policy[it]).isEqualTo("'none'")
            }
            assertThat(page.meta("name", "referrer")).isEqualTo("no-referrer")
            assertWithMessage(path.toString()).that(page.select("[style], [onclick], [onsubmit]")).isEmpty()
        }
    }

    @Test
    fun `hosting bootstrap script elements execute but inline event handlers remain blocked`() {
        HelperBrowser().use { browser ->
            pages.keys.forEach { path ->
                browser.openPage(path)
                val blocked =
                    browser.executeAsyncScript(
                        """
                        const done = arguments[arguments.length - 1];
                        document.addEventListener('securitypolicyviolation', event => {
                          if (event.effectiveDirective === 'script-src-attr') {
                            done(window.hostingBootstrap === true && window.unexpectedHandler === undefined &&
                              window.evalBlocked === true && window.unexpectedEval === undefined);
                          }
                        });
                        const script = document.createElement('script');
                        script.textContent = `window.hostingBootstrap = true;
                          try { eval('window.unexpectedEval = true'); }
                          catch (error) { window.evalBlocked = error instanceof EvalError; }`;
                        document.head.append(script);
                        const button = document.createElement('button');
                        button.setAttribute('onclick', 'window.unexpectedHandler = true');
                        document.body.append(button);
                        button.click();
                        """.trimIndent(),
                    )
                assertWithMessage(path.toString()).that(blocked).isEqualTo(true)
            }
        }
    }

    @Test
    fun `helper CSP rejects arbitrary Adyen and hosting lookalike requests in every language`() {
        HelperBrowser().use { browser ->
            pages.keys.filter { it.fileName.toString() == "setup.html" }.forEach { path ->
                browser.open(path)
                val blocked =
                    browser.executeAsyncScript(
                        """
                        const done = arguments[arguments.length - 1];
                        const verify = async () => {
                          for (const url of [
                            'https://example.invalid/credential-test',
                            'https://checkout-test.adyen.com/v71/payments',
                            'https://minimpos.app/credentials',
                            'https://minimpos.app/cdn-cgi-lookalike/credentials',
                            'https://cloudflareinsights.com/not-rum'
                          ]) {
                            const violation = new Promise(resolve => {
                              document.addEventListener('securitypolicyviolation', event => {
                                resolve(event.effectiveDirective === 'connect-src');
                              }, {once: true});
                            });
                            const request = fetch(url).then(() => false, () => true);
                            if (!(await Promise.all([violation, request])).every(Boolean)) return false;
                          }
                          return true;
                        };
                        verify().then(done);
                        """.trimIndent(),
                    )
                assertWithMessage(path.toString()).that(blocked).isEqualTo(true)
            }
        }
    }

    private fun Document.meta(
        attribute: String,
        value: String,
    ) = select("meta[$attribute='$value']").single().attr("content")

    private companion object {
        const val BASE = "https://minimpos.app/"
    }
}
