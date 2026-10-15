package com.pocketledger.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pocketledger.core.TxnKind
import com.pocketledger.databinding.ItemRowBinding

/** 流水与未识别条目共用的列表适配器。 */
class LedgerAdapter : RecyclerView.Adapter<LedgerAdapter.RowHolder>() {

    private val rows = mutableListOf<LedgerRow>()

    /**
     * @param newRows 全量替换列表内容。
     */
    fun submit(newRows: List<LedgerRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    class RowHolder(private val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: LedgerRow) {
            binding.tvRowHead.text = row.head
            binding.tvRowAmount.text = row.amount
            binding.tvRowDetail.text = row.detail
            binding.tvRowAmount.setTextColor(colorFor(row))
        }

        private fun colorFor(row: LedgerRow): Int = when {
            row.unmatched -> Color.parseColor("#F57C00")
            row.kind == TxnKind.EXPENSE -> Color.parseColor("#C62828")
            row.kind == TxnKind.INCOME || row.kind == TxnKind.REFUND -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#616161")
        }
    }
}
