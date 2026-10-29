package com.braveplayer.music.util

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.braveplayer.music.R
import com.braveplayer.music.model.SearchResponseParser
import java.util.regex.Pattern

/**
 * Utility to parse collaborative and featured artists and bind independent clickable spans
 * to TextViews across the entire application without triggering parent playback click handlers.
 */
object ArtistClickHelper {

    private val DELIMITER_PATTERN = Pattern.compile(
        "\\s*(?:,|&|/|\\||\\bfeaturing\\b|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bx\\b)\\s*",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Splits multi-artist strings (e.g. "Arijit Singh, Shreya Ghoshal", "Coldplay & BTS",
     * "Justin Bieber feat. Ludacris") into distinct, cleaned artist names.
     */
    fun splitArtistNames(rawArtists: String?): List<String> {
        if (rawArtists.isNullOrBlank()) return emptyList()
        val cleaned = SearchResponseParser.cleanHtmlEntities(rawArtists)
        return cleaned.split(DELIMITER_PATTERN.toRegex())
            .map { it.trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'").trim() }
            .filter { it.isNotBlank() && !it.equals("Various Artists", ignoreCase = true) }
            .distinct()
    }

    /**
     * Attaches independently clickable spans to the given [TextView] for each collaborating artist.
     * Tapping an artist invokes [onArtistClicked] and stops click event propagation to the parent.
     */
    fun attachClickableArtists(
        textView: TextView,
        rawArtists: String?,
        album: String? = null,
        onArtistClicked: (artistName: String) -> Unit
    ) {
        val artists = splitArtistNames(rawArtists)
        if (artists.isEmpty()) {
            val fallback = if (rawArtists.isNullOrBlank()) "Unknown Artist" else SearchResponseParser.cleanHtmlEntities(rawArtists)
            val fullText = if (!album.isNullOrBlank()) "$fallback • $album" else fallback
            textView.text = fullText
            textView.movementMethod = null
            return
        }

        val builder = SpannableStringBuilder()
        val context = textView.context
        val normalColor = ContextCompat.getColor(context, R.color.textSecondary)

        artists.forEachIndexed { index, name ->
            if (index > 0) {
                builder.append(", ")
            }
            val start = builder.length
            builder.append(name)
            val end = builder.length

            val span = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onArtistClicked(name)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = false
                    ds.color = normalColor
                }
            }
            builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (!album.isNullOrBlank()) {
            builder.append(" • ").append(SearchResponseParser.cleanHtmlEntities(album))
        }

        textView.text = builder
        textView.movementMethod = SelectiveLinkMovementMethod
    }

    /**
     * Custom MovementMethod that only consumes touch events when a ClickableSpan was tapped.
     * When tapping whitespace or outside spans, allows touch events to bubble up to the parent item view.
     */
    private object SelectiveLinkMovementMethod : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop

                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout ?: return false
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val spans = buffer.getSpans(off, off, ClickableSpan::class.java)

                if (spans.isNotEmpty()) {
                    if (action == MotionEvent.ACTION_UP) {
                        spans[0].onClick(widget)
                    }
                    return true
                }
            }
            return false
        }
    }
}
