package com.gpsemulator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SavedLocationAdapter(
    private val items: MutableList<SavedLocation>,
    private val onLoad: (SavedLocation) -> Unit,
    private val onDelete: (SavedLocation) -> Unit
) : RecyclerView.Adapter<SavedLocationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tv_history_name)
        val tvDetail: TextView = v.findViewById(R.id.tv_history_detail)
        val btnLoad: TextView = v.findViewById(R.id.btn_history_load)
        val btnDelete: ImageButton = v.findViewById(R.id.btn_history_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvName.text = "📍 ${item.name}"
        h.tvDetail.text = "%.6f, %.6f".format(item.latitude, item.longitude)
        h.btnLoad.setOnClickListener { onLoad(item) }
        h.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size

    fun refresh(newItems: List<SavedLocation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

class SavedRouteAdapter(
    private val items: MutableList<SavedRoute>,
    private val onLoad: (SavedRoute) -> Unit,
    private val onDelete: (SavedRoute) -> Unit
) : RecyclerView.Adapter<SavedRouteAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tv_history_name)
        val tvDetail: TextView = v.findViewById(R.id.tv_history_detail)
        val btnLoad: TextView = v.findViewById(R.id.btn_history_load)
        val btnDelete: ImageButton = v.findViewById(R.id.btn_history_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvName.text = "🗺 ${item.name}"
        h.tvDetail.text = "${item.points.size} 個路徑點 | ${item.speedKmh} km/h"
        h.btnLoad.setOnClickListener { onLoad(item) }
        h.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size

    fun refresh(newItems: List<SavedRoute>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
