package cn.classfun.droidvm.ui.main.base;

import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import cn.classfun.droidvm.R;

public final class BaseViewHolder extends RecyclerView.ViewHolder {
    public final ImageView itemIcon;
    public final MaterialCardView itemCard;
    public final TextView itemName;
    public final TextView itemCenter;
    public final TextView itemInfo;
    public final TextView itemState;
    public final ImageButton itemAction;

    public BaseViewHolder(@NonNull View itemView) {
        super(itemView);
        itemIcon = itemView.findViewById(R.id.item_icon);
        itemCard = itemView.findViewById(R.id.item_card);
        itemName = itemView.findViewById(R.id.item_name);
        itemCenter = itemView.findViewById(R.id.item_center);
        itemInfo = itemView.findViewById(R.id.item_info);
        itemState = itemView.findViewById(R.id.item_state);
        itemAction = itemView.findViewById(R.id.item_action);
    }
}
