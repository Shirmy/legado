package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.dpToPx

@Suppress("unused", "MemberVisibilityCanBePrivate")
class LabelsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val unUsedViews = arrayListOf<TextView>()
    private val usedViews = arrayListOf<TextView>()
    var textSize = 12f
    var wrapLines = false

    fun setLabels(labels: List<String>, onClick: ((String) -> Unit)? = null, onLongClick: ((String) -> Boolean)? = null) {
        clear()
        labels.forEach {
            addLabel(it, onClick, onLongClick)
        }
    }

    fun clear() {
        unUsedViews.addAll(usedViews)
        usedViews.clear()
        removeAllViews()
    }

    fun addLabel(label: String, onClick: ((String) -> Unit)?, onLongClick: ((String) -> Boolean)?) {
        val tv = if (unUsedViews.isEmpty()) {
            AccentBgTextView(context, null).apply {
                setPadding(3.dpToPx(), 0, 3.dpToPx(), 0)
                setRadius(2)
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 2.dpToPx(), 0)
                layoutParams = lp
                text = label
                maxLines = 1
                usedViews.add(this)
            }
        } else {
            unUsedViews.last().apply {
                usedViews.add(this)
                unUsedViews.removeAt(unUsedViews.lastIndex)
            }
        }
        tv.textSize = textSize
        tv.text = label
        if (onClick != null) {
            tv.setOnClickListener { onClick.invoke(label) }
        }
        if (onLongClick != null) {
            tv.setOnLongClickListener { onLongClick.invoke(label) }
        }
        addView(tv)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!wrapLines) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels - paddingLeft - paddingRight
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }.coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var measuredWidth = 0
        var measuredHeight = paddingTop + paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > maxWidth) {
                measuredWidth = measuredWidth.coerceAtLeast(lineWidth)
                measuredHeight += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = lineHeight.coerceAtLeast(childHeight)
        }
        measuredWidth = measuredWidth.coerceAtLeast(lineWidth) + paddingLeft + paddingRight
        measuredHeight += lineHeight
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(measuredHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!wrapLines) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        val maxWidth = r - l - paddingLeft - paddingRight
        var lineStart = 0
        var lineWidth = 0
        var lineHeight = 0
        var top = paddingTop

        fun layoutLine(start: Int, end: Int, width: Int, height: Int) {
            var left = paddingLeft + ((maxWidth - width) / 2).coerceAtLeast(0)
            for (index in start until end) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val lp = child.layoutParams as MarginLayoutParams
                left += lp.leftMargin
                val childTop = top + lp.topMargin + ((height - child.measuredHeight - lp.topMargin - lp.bottomMargin) / 2).coerceAtLeast(0)
                child.layout(left, childTop, left + child.measuredWidth, childTop + child.measuredHeight)
                left += child.measuredWidth + lp.rightMargin
            }
        }

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > maxWidth) {
                layoutLine(lineStart, index, lineWidth, lineHeight)
                top += lineHeight
                lineStart = index
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = lineHeight.coerceAtLeast(childHeight)
        }
        layoutLine(lineStart, childCount, lineWidth, lineHeight)
    }
}
