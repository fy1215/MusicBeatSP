package com.music.bitchord.external

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.lyricseditor.data.LocalLyricsManager
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsEditorSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

/**
 * 確認版: ほかのアプリ(主に Spotify のローカルファイル再生)の曲を、このアプリのライブラリの曲と結びつけられるか、
 * その曲に歌詞があるかを、実機で調べるための表示。
 *
 * 歌詞を重ねて表示する本番の機能を作る前に、
 * - Spotify がローカルの曲をどんな名前・長さで知らせてくるか
 * - 再生位置がきちんと進むか
 * - ライブラリの曲と一致させられるか
 * を確かめる。結果は「結果をコピー」でまとめてクリップボードに入れられる。
 */
object ExternalLyricsMatcher {

    data class Match(val song: Song, val score: Int, val reason: String)

    /** ライブラリ(読み込みに時間がかかるので一度だけ読む。「読み直す」で消す) */
    @Volatile private var library: List<Song>? = null

    suspend fun library(context: Context, reload: Boolean = false): List<Song> {
        if (reload) library = null
        library?.let { return it }
        return LocalMediaRepository.getLocalMusic(context.applicationContext).also { library = it }
    }

    /** 比べるための形にする(全角半角・大文字小文字・空白と記号の違いを無視する) */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("""[\s\p{Punct}・、。「」『』（）【】〜～　]+"""), "")

    /** "3:38" や "1:02:03" をミリ秒に */
    fun parseDuration(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
    }

    /** いちばん合いそうな曲(曲名が一致しないものは候補にしない) */
    fun find(now: ExternalMedia.NowPlaying, songs: List<Song>): List<Match> {
        val title = normalize(now.title)
        if (title.isEmpty()) return emptyList()
        val artist = normalize(now.artist)
        return songs.mapNotNull { song ->
            if (normalize(song.title) != title) return@mapNotNull null
            var score = 10
            val why = mutableListOf("曲名一致")
            val songArtists = listOfNotNull(song.artist, song.albumArtist).map(::normalize).filter { it.isNotEmpty() }
            if (artist.isNotEmpty() && songArtists.any { it == artist }) { score += 5; why += "アーティスト一致" }
            else if (artist.isNotEmpty() && songArtists.any { it.contains(artist) || artist.contains(it) }) { score += 3; why += "アーティスト部分一致" }
            else why += "アーティスト不一致"
            val songDur = parseDuration(song.durationText)
            if (now.durationMs > 0 && songDur != null) {
                val diff = kotlin.math.abs(now.durationMs - songDur)
                when {
                    diff <= 2_000 -> { score += 4; why += "長さ一致(差${diff / 1000}秒)" }
                    diff <= 5_000 -> { score += 1; why += "長さほぼ一致(差${diff / 1000}秒)" }
                    else -> { score -= 5; why += "長さ不一致(差${diff / 1000}秒)" }
                }
            } else why += "長さ比較なし"
            Match(song, score, why.joinToString("・"))
        }.sortedByDescending { it.score }
    }

    /** 曲の歌詞(埋め込み → 横の .lrc の順)。どこから読んだかと組で返す */
    suspend fun lyrics(context: Context, song: Song): Pair<String, String>? {
        for (source in listOf(LyricsEditorSource.Embedded, LyricsEditorSource.File)) {
            val text = runCatching { LocalLyricsManager.readLyrics(context, song, source) }.getOrNull()
            if (!text.isNullOrBlank()) return source.displayName to text
        }
        return null
    }

    private val LRC_TIME = Regex("""^\s*\[\d{1,3}:\d{2}([.:]\d{1,3})?]""")

    /** 時刻付きの行(LRC)の数 */
    fun syncedLineCount(text: String): Int = text.lineSequence().count { LRC_TIME.containsMatchIn(it) }
}

@Composable
fun ExternalLyricsCheckSection(now: ExternalMedia.NowPlaying) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ExternalLyricsMatcher.Match>>(emptyList()) }
    var lyricsInfo by remember { mutableStateOf("") }
    var reloadKey by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(now.positionNow()) }

    // 再生位置が進むかを見るため、0.5 秒ごとに表示を更新する
    LaunchedEffect(now) {
        while (true) {
            position = now.positionNow()
            delay(500)
        }
    }

    // 曲が変わったら、ライブラリから探し直す
    LaunchedEffect(now.packageName, now.title, now.artist, now.durationMs, reloadKey) {
        status = "ライブラリを読み込み中…"
        matches = emptyList()
        lyricsInfo = ""
        val songs = runCatching { ExternalLyricsMatcher.library(context, reload = reloadKey != 0L) }
            .getOrElse { status = "ライブラリを読めませんでした: ${it.message}"; return@LaunchedEffect }
        val found = ExternalLyricsMatcher.find(now, songs)
        matches = found
        status = when {
            songs.isEmpty() -> "ライブラリが空です(ストレージの許可を確認してください)"
            found.isEmpty() -> "ライブラリ ${songs.size} 曲のうち、一致する曲はありません"
            else -> "ライブラリ ${songs.size} 曲のうち、候補 ${found.size} 曲"
        }
        val best = found.firstOrNull() ?: return@LaunchedEffect
        val lyr = ExternalLyricsMatcher.lyrics(context, best.song)
        lyricsInfo = if (lyr == null) {
            "歌詞: なし"
        } else {
            val (from, text) = lyr
            val synced = ExternalLyricsMatcher.syncedLineCount(text)
            val head = text.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")
            "歌詞: あり($from・時刻付き $synced 行)\n$head"
        }
    }

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    val report = buildString {
        appendLine("【MusicBeatSP 確認版の結果】")
        appendLine("アプリ: ${now.appName} (${now.packageName})")
        appendLine("曲名: ${now.title}")
        appendLine("アーティスト: ${now.artist}")
        appendLine("アルバム: ${now.album}")
        appendLine("長さ: ${fmt(now.durationMs)} (${now.durationMs}ms)")
        appendLine("再生位置: ${fmt(position)} / 再生中=${now.playing} / 速さ=${now.speed} / シーク可=${now.canSeek}")
        appendLine("-- メタデータ --")
        ExternalMedia.rawMetadata.forEach { (k, v) -> appendLine("$k = $v") }
        appendLine("-- 照合 --")
        appendLine(status)
        matches.take(3).forEach { m ->
            appendLine("・${m.song.title} / ${m.song.artist} / ${m.song.durationText} [${m.score}点: ${m.reason}]")
            appendLine("  ${m.song.localPath ?: m.song.localUri ?: "(場所不明)"}")
        }
        if (lyricsInfo.isNotEmpty()) appendLine(lyricsInfo)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("歌詞の重ね表示: 確認版", style = MaterialTheme.typography.titleMedium)
        Text(
            "再生位置 ${fmt(position)} / ${fmt(now.durationMs)}" + if (now.playing) "(再生中)" else "(停止中)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)
        matches.firstOrNull()?.let { m ->
            Text("一致: ${m.song.title} / ${m.song.artist}\n${m.reason}", style = MaterialTheme.typography.bodySmall)
        }
        if (lyricsInfo.isNotEmpty()) Text(lyricsInfo, style = MaterialTheme.typography.bodySmall)
        Text("受け取った情報", style = MaterialTheme.typography.labelLarge)
        Text(
            ExternalMedia.rawMetadata.joinToString("\n") { (k, v) -> "$k = $v" }.ifEmpty { "(なし)" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("MusicBeatSP check", report))
                Toast.makeText(context, "結果をコピーしました", Toast.LENGTH_SHORT).show()
            }) { Text("結果をコピー") }
            OutlinedButton(onClick = { scope.launch { reloadKey = System.currentTimeMillis() } }) { Text("ライブラリを読み直す") }
        }
    }
}
