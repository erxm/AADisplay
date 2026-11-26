package io.github.nitsuya.aa.display.ui.window

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.model.RecentTaskInfo

/**
 * 多任务列表适配器，两种条目形态：
 * - 快照模式（[iconMode] = false，车机端 AaRecentTaskFragment）：图标 + 名称 + 任务快照；
 * - 图标模式（[iconMode] = true，手机端镜像小窗）：只有图标 + 名称。小窗是
 *   wrap_content 的浮窗，9:16 的快照会把条目撑得比车机画面还高，导致面板溢出卡片、
 *   看起来像“错位”；而且小窗缩放后快照根本看不清，所以这里直接不加载快照。
 *
 * 两套布局都提供 iv_icon / tv_name，快照模式额外有 cl_item / iv_snapshot。
 */
class DisplayRecyclerViewAdapter(
      private val recyclerView: RecyclerView
    , private val iconMode: Boolean = false
    , private val onExit: (() -> Unit)
) : RecyclerView.Adapter<DisplayRecyclerViewAdapter.ViewHolder>(){

    companion object {
        private const val TAG = "AADisplay_DisplayRecyclerViewAdapter"
    }

    private val items: MutableList<RecentTaskInfo> = ArrayList()
    lateinit var otherAdapter: DisplayRecyclerViewAdapter

    init {
        ItemTouchHelper(ItemTouchHelperCallback()).attachToRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (iconMode) R.layout.recent_task_icon else R.layout.recent_task
        return ViewHolder(LayoutInflater.from(parent.context).inflate(layoutId, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ivIcon.setImageBitmap(item.logo)
        holder.tvName.text = if (iconMode) item.label else "${item.label} [${item.taskId}]"

        if (!iconMode) {
            if(recyclerView.id == R.id.rv_recent_task_left){
                holder.clItem?.let { clItem ->
                    ConstraintSet().apply {
                        clone(clItem)
                        constrainPercentWidth(R.id.iv_snapshot,0.8f)
                        setDimensionRatio(R.id.iv_snapshot,"W,9:16")
                        applyTo(clItem)
                    }
                }
            }
            holder.ivSnapshot?.setImageBitmap(item.snapshot)
        }
        holder.itemView.setOnClickListener {
            onExit()
        }
        listOfNotNull(holder.tvName, holder.ivIcon, holder.ivSnapshot).forEach {
            it.setOnClickListener {
                CoreApi.moveTaskToFront(item.taskId)
                onExit()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun clearItem(){
        setItems(emptyList())
    }

    fun removeItem(item: RecentTaskInfo){
        val index = items.indexOf(item)
        // 面板隐藏/重载与 ItemTouchHelper 的 settle 动画交错时可能已经不在列表里。
        if (index < 0 || index >= items.size) return
        this.items.removeAt(index)
        this.notifyItemRemoved(index)
    }

    fun addItem(item: RecentTaskInfo){
        this.items.add(0, item)
        this.notifyItemInserted(0)
        this.recyclerView.scrollToPosition(0)
    }

    fun setItems(items: List<RecentTaskInfo>){
        this.items.clear()
        if(items.isNotEmpty()){
            this.items.addAll(items)
        }
        this.notifyDataSetChanged()
        if(this.items.isNotEmpty()){
            this.recyclerView.scrollToPosition(0)
        }
    }

    inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val ivSnapshot: ImageView? = itemView.findViewById(R.id.iv_snapshot)
        val clItem: ConstraintLayout? = itemView.findViewById(R.id.cl_item)
    }

    inner class ItemTouchHelperCallback: ItemTouchHelper.Callback(){
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = true

        override fun isLongPressDragEnabled(): Boolean = true

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.absoluteAdapterPosition
            if (position == RecyclerView.NO_POSITION || position !in items.indices) return
            val item = items[position]
            removeItem(item)
            if(recyclerView.id == R.id.rv_recent_task_left){
                if (direction == ItemTouchHelper.LEFT) {
                    CoreApi.removeTask(item.taskId)
                } else if (direction == ItemTouchHelper.RIGHT) {
                    otherAdapter.addItem(item)
                    CoreApi.moveTaskId(item.taskId, false)
                }
            } else if(recyclerView.id == R.id.rv_recent_task_right){
                if (direction == ItemTouchHelper.RIGHT) {
                    CoreApi.removeTask(item.taskId)
                } else if (direction == ItemTouchHelper.LEFT) {
                    otherAdapter.addItem(item)
                    CoreApi.moveTaskId(item.taskId, true)
                }
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                viewHolder?.itemView?.apply {
                    ViewCompat.animate(this)
                        .setDuration(200)
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .start()
                }
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.apply {
                ViewCompat.animate(this)
                    .setDuration(200)
                    .scaleX(1f)
                    .scaleY(1f)
                    .start()
            }
        }

    }

}
