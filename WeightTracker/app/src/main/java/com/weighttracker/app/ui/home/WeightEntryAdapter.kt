package com.weighttracker.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.weighttracker.app.data.model.WeightEntry
import com.weighttracker.app.databinding.ItemWeightEntryBinding

class WeightEntryAdapter(private val onLongClick: (WeightEntry) -> Unit) :
    ListAdapter<WeightEntry, WeightEntryAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemWeightEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWeightEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.binding.tvDate.text = entry.date
        holder.binding.tvWeight.text = "${entry.weight} kg"
        holder.binding.tvNote.text = entry.note
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<WeightEntry>() {
            override fun areItemsTheSame(a: WeightEntry, b: WeightEntry) = a.date == b.date
            override fun areContentsTheSame(a: WeightEntry, b: WeightEntry) = a == b
        }
    }
}
