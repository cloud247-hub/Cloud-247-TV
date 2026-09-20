package no.cloud247.tv

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

data class GroupItem(val key: String, val label: String, val count: Int)

class GroupAdapter(private val activity: Activity) : BaseAdapter() {
    private val items = mutableListOf<GroupItem>()
    var activeKey: String = "__all__"
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun setItems(newItems: List<GroupItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): GroupItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_group, parent, false)
        val item = getItem(position)
        view.findViewById<TextView>(R.id.groupName).text = item.label
        view.findViewById<TextView>(R.id.groupCount).text = item.count.toString()
        view.isActivated = item.key == activeKey
        return view
    }
}
