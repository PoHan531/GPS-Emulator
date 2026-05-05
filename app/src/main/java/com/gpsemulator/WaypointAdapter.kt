package com.gpsemulator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class WaypointAdapter(
    private val points: MutableList<RoutePoint>,
    private val onDelete: (Int) -> Unit,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<WaypointAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tv_waypoint_index)
        val tvName: TextView = view.findViewById(R.id.tv_waypoint_name)
        val tvCoords: TextView = view.findViewById(R.id.tv_waypoint_coords)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_waypoint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_waypoint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = points[position]
        holder.tvIndex.text = "${position + 1}"
        holder.tvName.text = if (point.name.isNotBlank()) point.name else "路徑點 ${position + 1}"
        holder.tvCoords.text = "%.6f, %.6f".format(point.latitude, point.longitude) +
                if (point.dwellSeconds > 0) " | 停留 ${point.dwellSeconds}s" else ""
        holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
        holder.itemView.setOnClickListener { onClick(holder.adapterPosition) }
    }

    override fun getItemCount() = points.size

    fun highlightActive(index: Int) {
        notifyDataSetChanged()
    }
}
