package eu.kanade.tachiyomi.extension.vi.moetruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class MoeTruyen : ParsedHttpSource() {

    override val name = "Mòe Truyện"

    override val baseUrl = "https://moetruyen.net"

    override val lang = "vi"

    override val supportsLatest = true

    // Imgx Decryptor Cache and Structures
    private val imgxGrants = ConcurrentHashMap<String, ImgxPageEntry>()

    data class ImgxGrant(
        val version: Int,
        val algorithm: String,
        val imageId: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val nonce: String,
        val keyNonce: String,
        val keyHash: String,
        val signature: String,
        val wrappedDecodeKey: String?,
        val decodeKey: String?
    )

    data class ImgxPageEntry(
        val pageIndex: Int,
        val downloadUrl: String,
        val storageKey: String,
        val grant: ImgxGrant
    )

    // Decrypting OkHttp Interceptor to decode IMGX secure slices automatically
    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val urlString = request.url.toString()

                val entry = imgxGrants[urlString]
                if (entry != null) {
                    val scrambledBytes = response.body?.bytes() ?: throw Exception("IMGX: Tải ảnh thất bại")
                    try {
                        val decodeKey = unwrapDecodeKeyFromGrant(entry.grant, entry.storageKey)
                        val decryptedBytes = decodeImgxWithKey(scrambledBytes, decodeKey)

                        val mediaType = "image/webp".toMediaTypeOrNull()
                        val body = decryptedBytes.toResponseBody(mediaType)
                        response.newBuilder().body(body).build()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw e
                    }
                } else {
                    response
                }
            }
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Cookie", "moetruyen_full_web=Moetruyen123456")
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga?sort=views&page=$page", headers)

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

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga?sort=updated&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/manga?q=$query&page=$page", headers)

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
        val readerPages = document.select(".reader-pages").first()
        val totalPagesStr = readerPages?.attr("data-reader-total-pages") ?: ""
        val initialPagesJson = readerPages?.attr("data-reader-imgx-initial-pages") ?: ""

        if (initialPagesJson.isNotEmpty()) {
            val totalPages = totalPagesStr.toIntOrNull() ?: 0
            val initialGrants = mutableMapOf<Int, ImgxPageEntry>()

            try {
                val decodedJson = java.net.URLDecoder.decode(initialPagesJson, "UTF-8")
                val jsonArray = org.json.JSONArray(decodedJson)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val index = item.getInt("pageIndex")
                    val downloadUrl = item.getString("downloadUrl")
                    val storageKey = item.getString("storageKey")
                    val grantObj = item.getJSONObject("grant")

                    val grant = ImgxGrant(
                        version = grantObj.getInt("version"),
                        algorithm = grantObj.getString("algorithm"),
                        imageId = grantObj.getString("imageId"),
                        issuedAt = grantObj.getLong("issuedAt"),
                        expiresAt = grantObj.getLong("expiresAt"),
                        nonce = grantObj.getString("nonce"),
                        keyNonce = grantObj.getString("keyNonce"),
                        keyHash = grantObj.getString("keyHash"),
                        signature = grantObj.getString("signature"),
                        wrappedDecodeKey = grantObj.optString("wrappedDecodeKey", null),
                        decodeKey = grantObj.optString("decodeKey", null)
                    )

                    initialGrants[index] = ImgxPageEntry(index, downloadUrl, storageKey, grant)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val remainingIndexes = (0 until totalPages).filter { !initialGrants.containsKey(it) }
            val pageAccessUrl = readerPages?.attr("abs:data-reader-imgx-access-url") ?: ""

            if (pageAccessUrl.isNotEmpty() && remainingIndexes.isNotEmpty()) {
                val chunkSize = 5
                for (chunk in remainingIndexes.chunked(chunkSize)) {
                    try {
                        val jsonBody = org.json.JSONObject()
                        val array = org.json.JSONArray()
                        chunk.forEach { array.put(it) }
                        jsonBody.put("pageIndexes", array)

                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                        val requestBody = jsonBody.toString().toRequestBody(mediaType)

                        val request = Request.Builder()
                            .url(pageAccessUrl)
                            .post(requestBody)
                            .headers(headers)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val respStr = response.body?.string() ?: ""
                                val respObj = org.json.JSONObject(respStr)
                                val pagesArray = respObj.getJSONArray("pages")
                                for (i in 0 until pagesArray.length()) {
                                    val item = pagesArray.getJSONObject(i)
                                    val index = item.getInt("pageIndex")
                                    val downloadUrl = item.getString("downloadUrl")
                                    val storageKey = item.getString("storageKey")
                                    val grantObj = item.getJSONObject("grant")

                                    val grant = ImgxGrant(
                                        version = grantObj.getInt("version"),
                                        algorithm = grantObj.getString("algorithm"),
                                        imageId = grantObj.getString("imageId"),
                                        issuedAt = grantObj.getLong("issuedAt"),
                                        expiresAt = grantObj.getLong("expiresAt"),
                                        nonce = grantObj.getString("nonce"),
                                        keyNonce = grantObj.getString("keyNonce"),
                                        keyHash = grantObj.getString("keyHash"),
                                        signature = grantObj.getString("signature"),
                                        wrappedDecodeKey = grantObj.optString("wrappedDecodeKey", null),
                                        decodeKey = grantObj.optString("decodeKey", null)
                                    )

                                    initialGrants[index] = ImgxPageEntry(index, downloadUrl, storageKey, grant)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            for (index in 0 until totalPages) {
                val entry = initialGrants[index]
                if (entry != null) {
                    imgxGrants[entry.downloadUrl] = entry
                    pages.add(Page(index, "", entry.downloadUrl))
                }
            }
        } else {
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
        }

        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Không sử dụng")

    // Cryptographic IMGX Decryption Logic

    private fun fnv1a32(bytes: ByteArray): Long {
        var hash = 0x811c9dc5L
        for (b in bytes) {
            hash = hash xor (b.toInt() and 0xff).toLong()
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        return if (hash == 0L) 0x9e3779b9L else hash
    }

    private fun nextXorShift32(value: Long): Long {
        var x = value and 0xffffffffL
        x = x xor ((x shl 13) and 0xffffffffL)
        x = x xor (x ushr 17)
        x = x xor ((x shl 5) and 0xffffffffL)
        return x and 0xffffffffL
    }

    private fun createGrantKeyMask(material: String, byteLength: Int): ByteArray {
        val mask = ByteArray(byteLength)
        val materialBytes = material.toByteArray(Charsets.UTF_8)
        var seed = fnv1a32(materialBytes)
        for (index in 0 until byteLength) {
            if (index % 4 == 0) {
                seed = nextXorShift32((seed + index + 0x9e3779b9L) and 0xffffffffL)
            }
            mask[index] = ((seed ushr ((index % 4) * 8)) and 0xffL).toByte()
        }
        return mask
    }

    private fun createGrantKeyWrapMaterial(grant: ImgxGrant, storageKey: String): String {
        val cleanKey = storageKey.trim().replace(Regex("^/+"), "")
        return listOf(
            "IMGX-GRANT-WRAP-v1",
            grant.version.toString(),
            grant.algorithm,
            grant.imageId,
            grant.issuedAt.toString(),
            grant.expiresAt.toString(),
            grant.nonce,
            grant.keyNonce,
            grant.signature,
            cleanKey
        ).joinToString(".")
    }

    private fun unwrapDecodeKeyFromGrant(grant: ImgxGrant, storageKey: String): ByteArray {
        if (grant.wrappedDecodeKey != null) {
            val wrapped = base64UrlDecode(grant.wrappedDecodeKey)
            val material = createGrantKeyWrapMaterial(grant, storageKey)
            val mask = createGrantKeyMask(material, wrapped.size)
            val decodeKey = ByteArray(wrapped.size)
            for (i in wrapped.indices) {
                decodeKey[i] = (wrapped[i].toInt() xor mask[i].toInt()).toByte()
            }
            return decodeKey
        }
        if (grant.decodeKey != null) {
            return base64UrlDecode(grant.decodeKey)
        }
        throw Exception("IMGX: Thiếu mã khóa giải mã")
    }

    private fun seedFromKey(key: ByteArray): Long {
        if (key.size < 4) return 0x9e3779b9L
        val b0 = (key[0].toInt() and 0xff).toLong()
        val b1 = (key[1].toInt() and 0xff).toLong()
        val b2 = (key[2].toInt() and 0xff).toLong()
        val b3 = (key[3].toInt() and 0xff).toLong()
        val seed = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return if (seed == 0L) 0x9e3779b9L else seed
    }

    private fun swapByte(bytes: ByteArray, left: Int, right: Int) {
        if (left == right) return
        val tmp = bytes[left]
        bytes[left] = bytes[right]
        bytes[right] = tmp
    }

    private fun unshuffleBytesInPlace(bytes: ByteArray, key: ByteArray) {
        val swaps = IntArray(bytes.size)
        var seed = seedFromKey(key)
        for (index in bytes.size - 1 downTo 1) {
            seed = nextXorShift32(seed)
            swaps[index] = (seed % (index + 1)).toInt()
        }
        for (index in 1 until bytes.size) {
            swapByte(bytes, index, swaps[index])
        }
    }

    private fun xorInPlace(bytes: ByteArray, key: ByteArray) {
        for (index in bytes.indices) {
            bytes[index] = (bytes[index].toInt() xor (key[index % key.size].toInt() and 0xff)).toByte()
        }
    }

    private fun decodeImgxWithKey(binary: ByteArray, decodeKey: ByteArray): ByteArray {
        if (binary.size < 13) throw Exception("IMGX: Dữ liệu ảnh quá ngắn")
        val payload = binary.copyOfRange(13, binary.size)
        unshuffleBytesInPlace(payload, decodeKey)
        xorInPlace(payload, decodeKey)
        return payload
    }

    private fun base64UrlDecode(text: String): ByteArray {
        return android.util.Base64.decode(text, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }
}
