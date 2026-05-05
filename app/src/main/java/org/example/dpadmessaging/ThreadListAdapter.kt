package org.example.dpadmessaging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ThreadItem(val id: Long, val address: String, val snippet: String)

class ThreadListAdapter(private val items: List<ThreadItem>) : RecyclerView.Adapter<ThreadListAdapter.VH>() {
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val addr: TextView = itemView.findViewById(R.id.addr)
        val snip: TextView = itemView.findViewById(R.id.snip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_thread, parent, false)
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.addr.text = t.address
        holder.snip.text = t.snippet
        holder.itemView.setOnClickListener {
            // TODO: open thread view
        }
    }

    override fun getItemCount(): Int = items.size
}
