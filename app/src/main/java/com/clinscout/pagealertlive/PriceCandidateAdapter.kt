package com.clinscout.pagealertlive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

class PriceCandidateAdapter(private val context: Context, private val candidates: List<PriceCandidate>) : BaseAdapter() {
    private data class Holder(val amount: TextView, val selector: TextView, val preview: TextView)
    override fun getCount() = candidates.size
    override fun getItem(position: Int) = candidates[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val card = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(23, 35, 45)); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(57, 77, 90))
            }
            val amount = TextView(context).apply {
                textSize = 21f; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(113, 226, 188))
            }
            val selector = TextView(context).apply {
                textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(234, 243, 248))
                maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
            }
            val preview = TextView(context).apply {
                textSize = 13f; setTextColor(Color.rgb(151, 171, 187)); maxLines = 3
                ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
            }
            addView(amount, LinearLayout.LayoutParams(-1, -2))
            addView(selector, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            tag = Holder(amount, selector, preview)
        }
        val candidate = getItem(position)
        (card.tag as Holder).apply {
            amount.text = candidate.price.toPlainString()
            selector.text = candidate.selector
            preview.text = candidate.preview
            preview.visibility = if (candidate.preview.isBlank()) View.GONE else View.VISIBLE
        }
        return card
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
