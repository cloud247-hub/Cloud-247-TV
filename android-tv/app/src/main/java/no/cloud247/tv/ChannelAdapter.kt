package no.cloud247.tv

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

class ChannelAdapter(
    private val activity: Activity,
    private val favorites: Set<String>,
    private val subtitleProvider: (Channel) -> String
) : BaseAdapter() {
    private val items = mutableListOf<Channel>()
    var activeChannel: Channel? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun setItems(newItems: List<Channel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Channel = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_channel, parent, false)
        val item = getItem(position)
        val logo = view.findViewById<ImageView>(R.id.channelLogo)
        val initials = view.findViewById<TextView>(R.id.channelInitials)
        val name = view.findViewById<TextView>(R.id.channelName)
        val subtitle = view.findViewById<TextView>(R.id.channelSubtitle)
        val favorite = view.findViewById<TextView>(R.id.channelFavorite)

        initials.text = initials(item.name)
        name.text = item.name
        subtitle.text = subtitleProvider(item)
        favorite.text = if (item.favoriteKey() in favorites) "★" else "☆"
        view.isActivated = activeChannel == item

        ImageLoader.load(activity, item.logo, logo, initials)
        return view
    }

    private fun initials(value: String): String {
        return value.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifBlank { "TV" }
    }
}
