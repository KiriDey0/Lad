// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*

class ApiError(val code: Int, message: String) : Exception(message)

class YandexApi(
    private val token: () -> String,
    val base: String = "https://api.music.yandex.net",
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build(),
    private val quasarBase: String = "https://quasar.yandex.net",
    private val iotBase: String = "https://api.iot.yandex.net",
) {
    companion object {
        const val MUSIC_CLIENT = "YandexMusicAndroid/24023621"
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
    }

    suspend fun raw(url: String, form: Map<String, String>? = null, auth: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", "LadAndroid/0.1")
            if (auth) {
                request.header("X-Yandex-Music-Client", MUSIC_CLIENT)
                if (token().isNotBlank()) request.header("Authorization", "OAuth ${token()}")
            }
            if (form != null)
                request.post(
                    FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
                )
            http.newCall(request.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val error = runCatching { JSONObject(body).text("error") }.getOrDefault("")
                    throw ApiError(
                        r.code,
                        when {
                            error == "authorization_pending" -> "authorization_pending"
                            error == "slow_down" -> "slow_down"
                            r.code == 401 -> "Сессия Яндекса истекла. Войди снова."
                            r.code == 404 -> "Запись недоступна"
                            else -> "Яндекс не выполнил запрос (${r.code})"
                        },
                    )
                }
                body
            }
        }

    suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ): Any {
        val url =
            base +
                path +
                (if (query.isEmpty()) ""
                else
                    query.entries.joinToString("&", "?") {
                        "${encode(it.key)}=${encode(it.value)}"
                    })
        val parsed = JSONTokener(raw(url, form, true)).nextValue()
        if (parsed is JSONObject) {
            if (parsed.has("error")) throw ApiError(400, "Яндекс не выполнил запрос")
            return parsed.opt("result") ?: parsed
        }
        return parsed
    }

    suspend fun obj(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ) = request(path, query, form) as? JSONObject ?: JSONObject()

    suspend fun arr(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ) = request(path, query, form) as? JSONArray ?: JSONArray()

    suspend fun profile(): Profile {
        val j = obj("/account/status")
        val a = j.getJSONObject("account")
        return Profile(
            a.text("uid"),
            a.text("displayName").ifBlank { a.text("login") },
            j.optJSONObject("plus")?.optBoolean("hasPlus") == true,
        )
    }

    suspend fun deviceCode(): JSONObject =
        JSONObject(
            raw(
                "https://oauth.yandex.ru/device/code",
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_id" to java.util.UUID.randomUUID().toString(),
                    "device_name" to "Лад · Android",
                ),
            )
        )

    suspend fun pollCode(code: String): JSONObject =
        JSONObject(
            raw(
                "https://oauth.yandex.ru/token",
                mapOf(
                    "grant_type" to "device_code",
                    "code" to code,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                ),
            )
        )

    suspend fun tracks(ids: List<String>): List<Track> {
        val result = mutableMapOf<String, Track>()
        for (batch in ids.chunked(150)) arr(
                "/tracks",
                form = mapOf("track-ids" to batch.joinToString(",")),
            )
            .objects()
            .forEach {
                val t = Track.fromApi(it)
                result[t.id] = t
            }
        return ids.mapNotNull { result[it] }
    }

    suspend fun liked(uid: String, offset: Int = 0, limit: Int = 100): Catalog {
        val refs =
            obj("/users/${encode(uid)}/likes/tracks").optJSONObject("library")?.array("tracks")
                ?: JSONArray()
        val ids = refs.objects().map { it.text("id") }
        return Catalog(
            "Мне нравится",
            tracks(ids.drop(offset).take(limit)).map { it.copy(liked = true) },
            total = ids.size,
            more = offset + limit < ids.size,
        )
    }

    suspend fun library(uid: String): Library = coroutineScope {
        val own = async {
            arr("/users/${encode(uid)}/playlists/list").objects().map(Card::playlist).distinctBy {
                it.key
            }
        }
        val liked = async {
            arr("/users/${encode(uid)}/likes/playlists")
                .objects()
                .mapNotNull { it.optJSONObject("playlist") }
                .map(Card::playlist)
                .distinctBy { it.key }
        }
        val albums = async {
            arr("/users/${encode(uid)}/likes/albums", mapOf("rich" to "true"))
                .objects()
                .mapNotNull { it.optJSONObject("album") }
                .map(Card::album)
                .distinctBy { it.key }
        }
        val artists = async {
            try {
                val cards =
                    arr("/users/${encode(uid)}/likes/artists", mapOf("with-timestamps" to "true"))
                        .objects()
                        .mapNotNull {
                            it.optJSONObject("artist")
                                ?: it.takeIf { j -> j.has("name") && j.has("id") }
                        }
                        .map(Card::artist)
                        .distinctBy { it.key }
                cards to ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList<Card>() to (e.message ?: "Не удалось загрузить исполнителей")
            }
        }
        val ownCards = own.await()
        val likedCards = liked.await()
        val artistData = artists.await()
        Library(
            (ownCards + likedCards).distinctBy { it.key },
            albums.await(),
            ownCards,
            likedCards,
            artistData.first,
            artistData.second,
        )
    }

    suspend fun collection(card: Card, page: Int = 0): Catalog =
        when (card.type) {
            "album" -> {
                val j = obj("/albums/${encode(card.id)}/with-tracks")
                val tracks =
                    j.array("volumes")
                        .let { v ->
                            (0 until v.length()).flatMap { v.optJSONArray(it)?.objects().orEmpty() }
                        }
                        .map(Track::fromApi)
                Catalog(j.text("title", card.title), tracks, total = tracks.size)
            }
            "artist" -> {
                val j =
                    obj(
                        "/artists/${encode(card.id)}/tracks",
                        mapOf("page" to "$page", "page-size" to "100"),
                    )
                val cards =
                    if (page == 0)
                        runCatching {
                                obj(
                                        "/artists/${encode(card.id)}/direct-albums",
                                        mapOf("page-size" to "20"),
                                    )
                                    .array("albums")
                                    .objects()
                                    .map(Card::album)
                            }
                            .getOrDefault(emptyList())
                    else emptyList()
                val tracks = j.array("tracks").objects().map(Track::fromApi)
                Catalog(
                    card.title,
                    tracks,
                    cards,
                    j.optInt("total", tracks.size),
                    tracks.size == 100,
                )
            }
            else -> {
                val j = obj("/users/${encode(card.owner)}/playlists/${encode(card.id)}")
                val refs = j.array("tracks").objects()
                val found =
                    tracks(refs.filter { it.optJSONObject("track") == null }.map { it.text("id") })
                        .associateBy { it.id }
                val tracks =
                    refs.mapNotNull { r ->
                        r.optJSONObject("track")?.let(Track::fromApi) ?: found[r.text("id")]
                    }
                Catalog(j.text("title", card.title), tracks, total = tracks.size)
            }
        }

    suspend fun search(query: String, type: String = "all", page: Int = 0): Catalog {
        val j =
            obj(
                "/search",
                mapOf("text" to query, "type" to type, "page" to "$page", "nocorrect" to "false"),
            )
        val ts = j.optJSONObject("tracks")
        val cards =
            j.optJSONObject("albums")?.array("results")?.objects().orEmpty().map(Card::album) +
                j.optJSONObject("playlists")
                    ?.array("results")
                    ?.objects()
                    .orEmpty()
                    .map(Card::playlist) +
                j.optJSONObject("artists")?.array("results")?.objects().orEmpty().map(Card::artist)
        val t = ts?.array("results")?.objects().orEmpty().map(Track::fromApi)
        return Catalog(
            "Поиск",
            t,
            cards,
            ts?.optInt("total") ?: cards.size,
            if (type == "track") (page + 1) * 20 < (ts?.optInt("total") ?: 0)
            else type != "all" && (t.isNotEmpty() || cards.isNotEmpty()),
        )
    }

    suspend fun home(): Catalog {
        val history =
            runCatching { obj("/music-history", mapOf("fullModelsCount" to "30")) }.getOrNull()
        val found = mutableListOf<Track>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("fullModel")?.let { m ->
                        if (m.has("durationMs") && m.has("artists")) found.add(Track.fromApi(m))
                    }
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        walk(history)
        val landing =
            runCatching { obj("/landing3", mapOf("blocks" to "personalplaylists,new-releases")) }
                .getOrNull()
        val cards = mutableListOf<Card>()
        landing?.array("blocks")?.objects()?.forEach { block ->
            block.array("entities").objects().forEach { entity ->
                val data = entity.optJSONObject("data")
                val pl = data?.optJSONObject("playlist") ?: data
                if (pl?.has("kind") == true) cards.add(Card.playlist(pl))
            }
        }
        if (found.isNotEmpty())
            return Catalog(
                "Продолжить слушать",
                found.distinctBy { it.id }.take(20),
                cards.distinctBy { it.key },
            )
        val chart = obj("/landing3/chart", mapOf("chart" to "russia")).optJSONObject("chart")
        return Catalog(
            "Сейчас в чарте",
            chart
                ?.array("tracks")
                ?.objects()
                .orEmpty()
                .mapNotNull { it.optJSONObject("track") }
                .map(Track::fromApi)
                .take(20),
            cards,
        )
    }

    suspend fun waveSettings(): com.lad.music.wave.WavePreferences {
        val first =
            arr("/rotor/station/user:onyourwave/info").optJSONObject(0)
                ?: throw IllegalStateException("Яндекс пока не вернул настройки волны")
        return com.lad.music.wave.WavePreferences.parse(first)
    }

    suspend fun saveWaveSettings(value: com.lad.music.wave.WavePreferences) {
        value.validate()
        val json = JSONObject().put("type", "rotor")
        value.selected.forEach { (key, v) ->
            json.put(if (key == "mood_energy") "moodEnergy" else key, v)
        }
        val response = rawJson("${base}/rotor/station/user:onyourwave/settings3", json)
        check(response.optString("result") == "ok") { "Яндекс не подтвердил настройки волны" }
    }

    suspend fun wave(after: String = ""): Pair<List<Track>, String> {
        val j =
            obj(
                "/rotor/station/user:onyourwave/tracks",
                if (after.isBlank()) emptyMap() else mapOf("queue" to after),
            )
        return j.array("sequence")
            .objects()
            .mapNotNull { it.optJSONObject("track") }
            .map(Track::fromApi) to j.text("batchId")
    }

    suspend fun radioFeedback(type: String, track: Track?, batch: String, seconds: Long = 0) {
        val j =
            JSONObject()
                .put("type", type)
                .put("timestamp", java.time.Instant.now().toString())
                .put("from", "lad-android")
        if (track != null) j.put("trackId", track.id)
        if (seconds > 0 || type == "skip" || type == "trackFinished")
            j.put("totalPlayedSeconds", seconds.coerceAtLeast(0))
        rawJson("${base}/rotor/station/user:onyourwave/feedback?batch-id=${encode(batch)}", j)
    }

    suspend fun rawJson(url: String, json: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val body = json.toString().toRequestBody("application/json".toMediaType())
            http
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "OAuth ${token()}")
                        .post(body)
                        .build()
                )
                .execute()
                .use {
                    if (!it.isSuccessful)
                        throw ApiError(it.code, "Не удалось отправить событие воспроизведения")
                    JSONObject(it.body?.string().orEmpty())
                }
        }

    suspend fun like(uid: String, id: String, type: String, value: Boolean) {
        val result =
            request(
                "/users/${encode(uid)}/likes/${type}s/${if(value) "add-multiple" else "remove"}",
                form = mapOf("$type-ids" to id),
            )
        if (type == "album" && result != "ok") throw ApiError(400, "Яндекс не сохранил лайк")
        if (type == "track" && (result !is JSONObject || !result.has("revision")))
            throw ApiError(400, "Яндекс не сохранил лайк")
    }

    suspend fun lyrics(id: String): Lyrics {
        var forbidden: ApiError? = null
        val trackId = id.substringBefore(':')
        for (format in listOf("LRC", "TEXT")) {
            try {
                val stamp = (System.currentTimeMillis() / 1000).toString()
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec("p93jhgh689SBReK6ghtw62".toByteArray(), "HmacSHA256"))
                val sign =
                    Base64.getEncoder()
                        .encodeToString(
                            mac.doFinal((id.substringBefore(':') + stamp).toByteArray())
                        )
                val j =
                    obj(
                        "/tracks/${encode(trackId)}/lyrics",
                        mapOf("format" to format, "timeStamp" to stamp, "sign" to sign),
                    )
                val url = j.text("downloadUrl")
                if (url.startsWith("https://")) return parseLyrics(raw(url))
            } catch (e: ApiError) {
                when (e.code) {
                    400,
                    404 -> Unit
                    403 ->
                        forbidden =
                            ApiError(
                                403,
                                "Яндекс отклонил запрос текста (403). Попробуй повторить.",
                            )
                    else -> throw e
                }
            }
        }
        forbidden?.let { throw it }
        return Lyrics()
    }

    suspend fun stream(id: String): String {
        val info =
            arr("/tracks/${encode(id)}/download-info")
                .objects()
                .filter { it.text("codec") == "mp3" }
                .maxByOrNull { it.optInt("bitrateInKbps") }
                ?: throw ApiError(404, "Аудиофайл недоступен. Проверь подписку Яндекс Плюс.")
        val metadata = info.text("downloadInfoUrl").replace("http://", "https://")
        require(metadata.startsWith("https://"))
        val xml = raw(metadata)
        fun tag(name: String) =
            Regex("<$name>([^<]+)</$name>").find(xml)?.groupValues?.get(1)
                ?: throw ApiError(400, "Некорректная ссылка на аудиофайл")
        val path = tag("path")
        val host = tag("host")
        require(host.matches(Regex("[A-Za-z0-9.-]+")))
        val sign =
            MessageDigest.getInstance("MD5")
                .digest(("XGRlBW9FXlekgbPrRHuSiA" + path.drop(1) + tag("s")).toByteArray())
                .joinToString("") { "%02x".format(it) }
        return "https://$host/get-mp3/$sign/${tag("ts")}$path"
    }

    private suspend fun quasar(path: String): JSONObject {
        for (attempt in 0..1) {
            try {
                return JSONObject(raw(quasarBase + path, auth = true))
            } catch (e: IOException) {
                if (attempt == 1) throw e
                delay(300)
            }
        }
        error("Unreachable")
    }

    suspend fun devices(): List<Device> {
        val j = quasar("/glagol/device_list")
        if (!j.has("devices")) throw ApiError(401, "Яндекс не вернул Станции. Войди снова.")
        val devices = j.array("devices").objects().mapNotNull { d ->
            val cert =
                d.optJSONObject("glagol")
                    ?.optJSONObject("security")
                    ?.text("server_certificate")
                    .orEmpty()
            if (cert.isBlank()) null
            else Device(d.text("id"), d.text("name", "Станция"), d.text("platform"), cert)
        }
        val info = try { deviceMetadata() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        return enrichDeviceIdentities(devices, info)
    }

    private suspend fun deviceMetadata(): JSONObject = withContext(Dispatchers.IO) {
        // A separate read-only request. Never send the token through a redirect.
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(iotBase + "/v1.0/user/info")
            .header("Authorization", "Bearer ${token()}").get().build()
        client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Не удалось получить имена и комнаты" }
            JSONObject(r.body?.string().orEmpty())
        }
    }

    suspend fun stationToken(device: Device): String {
        val j =
            quasar(
                "/glagol/token?device_id=${encode(device.id)}&platform=${encode(device.platform)}"
            )
        return j.text("token").ifBlank { throw ApiError(401, "Не удалось авторизовать Станцию") }
    }
}
