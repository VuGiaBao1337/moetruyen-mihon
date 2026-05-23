package eu.kanade.tachiyomi.extension.vi.moetruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

class MoeTruyen : ParsedHttpSource() {

    override val name = "Mòe Truyện"

    override val baseUrl = "https://moetruyen.net"

    override val lang = "vi"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Cookie", "moetruyen_full_web=Moetruyen123456")
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga?sort=views&page=$page", headers)
    }

    override fun popularMangaSelector() = "article.manga-card"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val linkEl = element.select("a").first()!!
        
        manga.setUrlWithoutDomain(linkEl.attr("href"))
        
        val titleEl = element.select("h3").first()!!
        manga.title = if (titleEl.hasAttr("title")) titleEl.attr("title").trim() else titleEl.text().trim()
        
        val imgEl = element.select(".cover img").first()
        manga.thumbnail_url = imgEl?.attr("abs:src") ?: imgEl?.attr("abs:data-src")
        
        return manga
    }

    override fun popularMangaNextPageSelector() = "nav.admin-pagination a.button[aria-label=Trang sau], nav.admin-pagination a:contains(Sau)"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga?sort=updated&page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/manga?q=$query&page=$page", headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        
        manga.title = document.select("h1.manga-detail-title").text().trim()
        manga.author = document.select(".manga-detail-meta-line:contains(Tác giả) a").text().trim()
        manga.description = document.select("[data-description-content]").text().trim()
        
        val genres = document.select(".chips.manga-detail-genre-chips a.chip").map { it.text().trim() }
        manga.genre = genres.joinToString(", ")
        
        val statusText = document.select(".manga-status-pill").text().lowercase()
        manga.status = when {
            statusText.contains("đang tiến hành") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("hoàn thành") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("tạm dừng") || statusText.contains("hiatus") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        
        manga.thumbnail_url = document.select(".cover--detail img").attr("abs:src")
        
        return manga
    }

    override fun chapterListSelector() = "ul.chapter-list li.chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val linkEl = element.select("a.chapter-link").first()!!
        
        chapter.setUrlWithoutDomain(linkEl.attr("href"))
        
        val chapNum = element.select(".chapter-num").text().trim()
        val chapTitle = element.select(".chapter-title").text().trim()
        chapter.name = if (chapTitle.isNotEmpty()) "$chapNum - $chapTitle" else chapNum
        
        val dateText = element.select(".chapter-time").text().trim()
        chapter.date_upload = parseRelativeDate(dateText)
        
        return chapter
    }

    private fun parseRelativeDate(dateStr: String): Long {
        val calendar = Calendar.getInstance()
        val dateNormalized = dateStr.lowercase()
        
        val numberPattern = "\\d+".toRegex()
        val number = numberPattern.find(dateNormalized)?.value?.toInt() ?: return 0L
        
        when {
            dateNormalized.contains("giây") || dateNormalized.contains("second") -> {
                calendar.add(Calendar.SECOND, -number)
            }
            dateNormalized.contains("phút") || dateNormalized.contains("minute") -> {
                calendar.add(Calendar.MINUTE, -number)
            }
            dateNormalized.contains("giờ") || dateNormalized.contains("hour") -> {
                calendar.add(Calendar.HOUR_OF_DAY, -number)
            }
            dateNormalized.contains("ngày") || dateNormalized.contains("day") -> {
                calendar.add(Calendar.DAY_OF_YEAR, -number)
            }
            dateNormalized.contains("tuần") || dateNormalized.contains("week") -> {
                calendar.add(Calendar.WEEK_OF_YEAR, -number)
            }
            dateNormalized.contains("tháng") || dateNormalized.contains("month") -> {
                calendar.add(Calendar.MONTH, -number)
            }
            dateNormalized.contains("năm") || dateNormalized.contains("year") -> {
                calendar.add(Calendar.YEAR, -number)
            }
            else -> return 0L
        }
        
        return calendar.timeInMillis
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        
        val imageElements = document.select(".reader-pages img.page-media")
        
        imageElements.forEachIndexed { index, element ->
            var imageUrl = element.attr("abs:data-src").trim()
            if (imageUrl.isEmpty()) {
                imageUrl = element.attr("abs:src").trim()
            }
            
            if (imageUrl.isNotEmpty()) {
                pages.add(Page(index, "", imageUrl))
            }
        }
        
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Không sử dụng")
}
