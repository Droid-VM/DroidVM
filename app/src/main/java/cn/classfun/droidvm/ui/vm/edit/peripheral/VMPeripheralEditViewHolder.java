// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMPeripheralEditViewHolder extends RecyclerView.ViewHolder {
    final ImageView ivIcon;
    final TextView tvType;
    final TextView tvUnavailable;
    final TextView tvWarning;
    final ImageButton btnDelete;
    // virtio-snd
    final View groupVirtioSound;
    final LinearLayout soundOutEndpoints;
    final LinearLayout soundInEndpoints;
    final MaterialButton btnAddEndpoint;
    final PickerButtonWidget btnBuffer;
    final PickerButtonWidget btnUnderrun;
    // xHCI USB controller
    final View groupXhci;
    final TextView tvXhciId;
    final TextView tvXhciNote;
    final LinearLayout xhciZoneExact;
    final LinearLayout xhciZonePort;
    final LinearLayout xhciZoneDevice;
    final LinearLayout xhciZoneAny;
    final MaterialButton btnXhciAdd;
    final PickerButtonWidget btnUsb2Ports;
    final PickerButtonWidget btnUsb3Ports;
    final TextView tvXhciWarnMulti;
    final TextView tvXhciWarnPorts;
    // intel hda
    final View groupIntelHda;
    final View groupVirtioCamera;
    final MaterialButton btnCameraDevice;
    final MaterialButton btnHdaOut;
    final MaterialButton btnHdaIn;

    VMPeripheralEditViewHolder(@NonNull View itemView) {
        super(itemView);
        ivIcon = itemView.findViewById(R.id.iv_peripheral_icon);
        tvType = itemView.findViewById(R.id.tv_peripheral_type);
        tvUnavailable = itemView.findViewById(R.id.tv_peripheral_unavailable);
        tvWarning = itemView.findViewById(R.id.tv_peripheral_warning);
        btnDelete = itemView.findViewById(R.id.btn_peripheral_delete);
        groupVirtioSound = itemView.findViewById(R.id.group_virtio_sound);
        soundOutEndpoints = itemView.findViewById(R.id.sound_out_endpoints);
        soundInEndpoints = itemView.findViewById(R.id.sound_in_endpoints);
        btnAddEndpoint = itemView.findViewById(R.id.btn_sound_endpoint_add);
        btnBuffer = itemView.findViewById(R.id.btn_sound_buffer);
        btnUnderrun = itemView.findViewById(R.id.btn_sound_underrun);
        groupXhci = itemView.findViewById(R.id.group_xhci);
        tvXhciId = itemView.findViewById(R.id.tv_xhci_id);
        tvXhciNote = itemView.findViewById(R.id.tv_xhci_note);
        xhciZoneExact = itemView.findViewById(R.id.xhci_zone_exact);
        xhciZonePort = itemView.findViewById(R.id.xhci_zone_port);
        xhciZoneDevice = itemView.findViewById(R.id.xhci_zone_device);
        xhciZoneAny = itemView.findViewById(R.id.xhci_zone_any);
        btnXhciAdd = itemView.findViewById(R.id.btn_xhci_device_add);
        btnUsb2Ports = itemView.findViewById(R.id.btn_xhci_usb2_ports);
        btnUsb3Ports = itemView.findViewById(R.id.btn_xhci_usb3_ports);
        tvXhciWarnMulti = itemView.findViewById(R.id.tv_xhci_warn_multi);
        tvXhciWarnPorts = itemView.findViewById(R.id.tv_xhci_warn_ports);
        groupIntelHda = itemView.findViewById(R.id.group_intel_hda);
        groupVirtioCamera = itemView.findViewById(R.id.group_virtio_camera);
        btnCameraDevice = itemView.findViewById(R.id.btn_camera_device);
        btnHdaOut = itemView.findViewById(R.id.btn_hda_out);
        btnHdaIn = itemView.findViewById(R.id.btn_hda_in);
    }
}
