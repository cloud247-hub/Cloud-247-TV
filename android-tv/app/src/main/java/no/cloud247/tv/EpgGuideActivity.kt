package no.cloud247.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EpgGuideActivity : Activity() {
    companion object {
        private var preparedChannels: List<Channel> = emptyList()
        private var preparedEpgData: EpgData = EpgData.EMPTY
        private var preparedSelectedIndex: Int = 0

        fun prepareSession(channels: List<Channel>, epgData: EpgData, selected: Channel? = null) {
            preparedChannels = channels.toList()
            preparedEpgData = epgData
            preparedSelectedIndex = selected?.let { selectedChannel ->
                preparedChannels.indexOfFirst {
                    it.url == selectedChannel.url && it.name == selectedChannel.name
                }.takeIf { it >= 0 }
            } ?: 0
        }
    }

    private lateinit var guideView: EpgGuideView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val channels = preparedChannels
        if (channels.isEmpty()) {
            finish()
            return
        }

        guideView = EpgGuideView(
            activity = this,
            channels = channels,
            epgData = preparedEpgData,
            initialChannelIndex = preparedSelectedIndex,
            onOpenChannel = { channel ->
                FullscreenPlayerActivity.prepareSession(channels, channel, preparedEpgData)
                startActivity(Intent(this, FullscreenPlayerActivity::class.java).apply {
                    putExtra(FullscreenPlayerActivity.EXTRA_URL, channel.url)
                    putExtra(FullscreenPlayerActivity.EXTRA_NAME, channel.name)
                })
            }
        )
        setContentView(guideView)
        guideView.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::guideView.isInitialized) return super.onKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                guideView.moveChannel(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                guideView.moveChannel(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                guideView.moveProgram(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                guideView.moveProgram(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                guideView.openSelected()
                true
            }
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_GUIDE -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

private class EpgGuideView(
    private val activity: Activity,
    private val channels: List<Channel>,
    private val epgData: EpgData,
    initialChannelIndex: Int,
    private val onOpenChannel: (Channel) -> Unit
) : View(activity) {
    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HALF_HOUR_MS = 30L * MINUTE_MS
        private const val HOUR_MS = 60L * MINUTE_MS
        private const val GUIDE_HOURS = 74L
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val channelWidth = dp(250f)
    private val headerHeight = dp(78f)
    private val rowHeight = dp(86f)
    private val hourWidth = dp(255f)
    private val pxPerMs = hourWidth / HOUR_MS.toFloat()

    private val navy = activity.getColor(R.color.navy)
    private val navy2 = activity.getColor(R.color.navy_2)
    private val navy3 = activity.getColor(R.color.navy_3)
    private val surface = activity.getColor(R.color.surface)
    private val surface2 = activity.getColor(R.color.surface_2)
    private val yellow = activity.getColor(R.color.yellow)
    private val white = activity.getColor(R.color.white)
    private val muted = activity.getColor(R.color.muted)
    private val line = activity.getColor(R.color.line)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE d. MMM", Locale.getDefault())

    private val now = System.currentTimeMillis()
    private val timelineStart = ((now - 2L * HOUR_MS) / HALF_HOUR_MS) * HALF_HOUR_MS
    private val timelineEnd = timelineStart + GUIDE_HOURS * HOUR_MS

    private var selectedChannelIndex = initialChannelIndex.coerceIn(0, channels.lastIndex)
    private var selectedTime = now
    private var horizontalOffset = 0f
    private var verticalOffset = 0f
    private var initialPositionApplied = false

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                horizontalOffset = clampHorizontal(horizontalOffset + distanceX)
                verticalOffset = clampVertical(verticalOffset + distanceY)
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                selectAt(e.x, e.y)
                performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                selectAt(e.x, e.y)
                openSelected()
                return true
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(navy)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialPositionApplied && w > 0) {
            horizontalOffset = clampHorizontal(
                ((now - timelineStart) * pxPerMs) - (w - channelWidth) * 0.22f
            )
            ensureSelectionVisible()
            initialPositionApplied = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawHeader(canvas)
        drawRows(canvas)
        drawNowLine(canvas)
    }

    fun moveChannel(delta: Int) {
        selectedChannelIndex = (selectedChannelIndex + delta).coerceIn(0, channels.lastIndex)
        ensureSelectionVisible()
        invalidate()
    }

    fun moveProgram(delta: Int) {
        val programs = EpgLookup.programsForChannel(channels[selectedChannelIndex], epgData)
        if (programs.isEmpty()) {
            selectedTime = (selectedTime + delta * HOUR_MS).coerceIn(timelineStart, timelineEnd)
        } else {
            val currentIndex = programIndexForTime(programs, selectedTime)
            val nextIndex = (currentIndex + delta).coerceIn(0, programs.lastIndex)
            val program = programs[nextIndex]
            val stop = EpgLookup.effectiveStop(programs, nextIndex)
            selectedTime = program.start.time + (stop.time - program.start.time) / 2L
        }
        ensureSelectionVisible()
        invalidate()
    }

    fun openSelected() {
        channels.getOrNull(selectedChannelIndex)?.let(onOpenChannel)
    }

    private fun selectAt(x: Float, y: Float) {
        if (y < headerHeight) return
        val row = ((y - headerHeight + verticalOffset) / rowHeight).toInt()
        if (row !in channels.indices) return

        selectedChannelIndex = row
        if (x >= channelWidth) {
            selectedTime = (
                timelineStart + ((x - channelWidth + horizontalOffset) / pxPerMs).toLong()
            ).coerceIn(timelineStart, timelineEnd)
        }
        ensureSelectionVisible()
        invalidate()
    }

    private fun drawHeader(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        paint.color = yellow
        paint.textSize = sp(18f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("TV-GUIDE", dp(18f), dp(29f), paint)

        paint.color = muted
        paint.textSize = sp(11f)
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(dayFormat.format(Date()), dp(18f), dp(49f), paint)
        canvas.drawText("OK: se kanal", dp(18f), dp(66f), paint)

        val save = canvas.save()
        canvas.clipRect(channelWidth, 0f, width.toFloat(), headerHeight)

        var mark = timelineStart
        while (mark <= timelineEnd) {
            val x = timeToX(mark)
            if (x >= channelWidth - hourWidth && x <= width + hourWidth) {
                paint.color = line
                paint.strokeWidth = dp(if (mark % HOUR_MS == 0L) 1.2f else 0.7f)
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)

                if (mark % HOUR_MS == 0L) {
                    paint.color = white
                    paint.textSize = sp(14f)
                    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    canvas.drawText(timeFormat.format(Date(mark)), x + dp(8f), dp(31f), paint)

                    paint.color = muted
                    paint.textSize = sp(10f)
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    canvas.drawText(dayFormat.format(Date(mark)), x + dp(8f), dp(50f), paint)
                }
            }
            mark += HALF_HOUR_MS
        }
        canvas.restoreToCount(save)
    }

    private fun drawRows(canvas: Canvas) {
        val contentHeight = height - headerHeight
        if (contentHeight <= 0f) return

        val firstRow = max(0, (verticalOffset / rowHeight).toInt())
        val lastRow = min(
            channels.lastIndex,
            ((verticalOffset + contentHeight) / rowHeight).toInt() + 1
        )

        for (index in firstRow..lastRow) {
            val channel = channels[index]
            val top = headerHeight + index * rowHeight - verticalOffset
            val bottom = top + rowHeight
            val selected = index == selectedChannelIndex

            paint.style = Paint.Style.FILL
            paint.color = if (index % 2 == 0) navy else navy2
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)

            paint.color = if (selected) surface2 else surface
            canvas.drawRect(0f, top, channelWidth, bottom, paint)

            if (selected) {
                paint.color = yellow
                canvas.drawRect(0f, top, dp(5f), bottom, paint)
            }

            paint.color = if (selected) white else muted
            paint.textSize = sp(16f)
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            drawEllipsized(
                canvas,
                channel.name,
                dp(18f),
                top + dp(31f),
                channelWidth - dp(30f),
                paint
            )

            paint.color = muted
            paint.textSize = sp(11f)
            paint.typeface = android.graphics.Typeface.DEFAULT
            drawEllipsized(
                canvas,
                channel.group,
                dp(18f),
                top + dp(55f),
                channelWidth - dp(30f),
                paint
            )

            val programs = EpgLookup.programsForChannel(channel, epgData)
            val save = canvas.save()
            canvas.clipRect(channelWidth, top, width.toFloat(), bottom)

            if (programs.isEmpty()) {
                paint.color = muted
                paint.textSize = sp(13f)
                canvas.drawText("Ingen EPG-data", channelWidth + dp(18f), top + dp(46f), paint)
            } else {
                for (programIndex in programs.indices) {
                    val program = programs[programIndex]
                    val stop = EpgLookup.effectiveStop(programs, programIndex)
                    if (stop.time < timelineStart || program.start.time > timelineEnd) continue

                    val left = timeToX(program.start.time) + dp(3f)
                    val right = max(left + dp(64f), timeToX(stop.time) - dp(3f))
                    if (right < channelWidth || left > width) continue

                    val rect = RectF(left, top + dp(7f), right, bottom - dp(7f))
                    val isCurrent = now >= program.start.time && now < stop.time
                    val isSelectedProgram = selected && programIndex == programIndexForTime(programs, selectedTime)

                    paint.style = Paint.Style.FILL
                    paint.color = if (isCurrent) surface2 else navy3
                    canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(if (isSelectedProgram) 3f else 1f)
                    paint.color = if (isSelectedProgram) yellow else line
                    canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)

                    paint.style = Paint.Style.FILL
                    paint.color = white
                    paint.textSize = sp(13f)
                    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    drawEllipsized(
                        canvas,
                        program.title,
                        rect.left + dp(10f),
                        rect.top + dp(25f),
                        max(dp(20f), rect.width() - dp(20f)),
                        paint
                    )

                    paint.color = muted
                    paint.textSize = sp(10f)
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    val timeText = "${timeFormat.format(program.start)}–${timeFormat.format(stop)}"
                    drawEllipsized(
                        canvas,
                        timeText,
                        rect.left + dp(10f),
                        rect.top + dp(46f),
                        max(dp(20f), rect.width() - dp(20f)),
                        paint
                    )
                }
            }
            canvas.restoreToCount(save)

            paint.style = Paint.Style.FILL
            paint.color = line
            canvas.drawRect(0f, bottom - dp(1f), width.toFloat(), bottom, paint)
        }
    }

    private fun drawNowLine(canvas: Canvas) {
        if (now !in timelineStart..timelineEnd) return
        val x = timeToX(now)
        if (x < channelWidth || x > width) return

        paint.style = Paint.Style.FILL
        paint.color = yellow
        canvas.drawRect(x - dp(1f), headerHeight, x + dp(1f), height.toFloat(), paint)
        canvas.drawRoundRect(
            RectF(x - dp(18f), dp(56f), x + dp(18f), dp(75f)),
            dp(5f),
            dp(5f),
            paint
        )
        paint.color = navy
        paint.textSize = sp(9f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("NÅ", x - dp(8f), dp(69f), paint)
    }

    private fun drawEllipsized(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        sourcePaint: Paint
    ) {
        if (maxWidth <= 0f) return
        textPaint.set(sourcePaint)
        val fitted = TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END)
        canvas.drawText(fitted.toString(), x, baseline, sourcePaint)
    }

    private fun programIndexForTime(programs: List<Program>, time: Long): Int {
        if (programs.isEmpty()) return 0
        for (index in programs.indices) {
            val stop = EpgLookup.effectiveStop(programs, index).time
            if (time >= programs[index].start.time && time < stop) return index
            if (programs[index].start.time > time) return index
        }
        return programs.lastIndex
    }

    private fun ensureSelectionVisible() {
        val selectedTop = selectedChannelIndex * rowHeight
        val viewportHeight = max(0f, height - headerHeight)
        if (selectedTop < verticalOffset) {
            verticalOffset = selectedTop
        } else if (selectedTop + rowHeight > verticalOffset + viewportHeight) {
            verticalOffset = selectedTop + rowHeight - viewportHeight
        }
        verticalOffset = clampVertical(verticalOffset)

        val programs = EpgLookup.programsForChannel(channels[selectedChannelIndex], epgData)
        val selectedIndex = programIndexForTime(programs, selectedTime)
        val selectedProgram = programs.getOrNull(selectedIndex)
        val selectedStart = selectedProgram?.start?.time ?: selectedTime
        val selectedStop = selectedProgram?.let {
            EpgLookup.effectiveStop(programs, selectedIndex).time
        } ?: selectedTime + HALF_HOUR_MS

        val left = channelWidth + (selectedStart - timelineStart) * pxPerMs - horizontalOffset
        val right = channelWidth + (selectedStop - timelineStart) * pxPerMs - horizontalOffset
        val margin = dp(40f)

        if (left < channelWidth + margin) {
            horizontalOffset -= channelWidth + margin - left
        } else if (right > width - margin) {
            horizontalOffset += right - (width - margin)
        }
        horizontalOffset = clampHorizontal(horizontalOffset)
    }

    private fun timeToX(time: Long): Float {
        return channelWidth + (time - timelineStart) * pxPerMs - horizontalOffset
    }

    private fun clampHorizontal(value: Float): Float {
        val contentWidth = (timelineEnd - timelineStart) * pxPerMs
        val viewportWidth = max(0f, width - channelWidth)
        return value.coerceIn(0f, max(0f, contentWidth - viewportWidth))
    }

    private fun clampVertical(value: Float): Float {
        val contentHeight = channels.size * rowHeight
        val viewportHeight = max(0f, height - headerHeight)
        return value.coerceIn(0f, max(0f, contentHeight - viewportHeight))
    }

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * scaledDensity
}
