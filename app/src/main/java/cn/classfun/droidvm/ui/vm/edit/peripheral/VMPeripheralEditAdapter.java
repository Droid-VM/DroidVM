// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Menu;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.data.HostCameraDevices;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.SoundBuffer;
import cn.classfun.droidvm.lib.store.vm.SoundMode;
import cn.classfun.droidvm.lib.store.vm.SoundPurpose;
import cn.classfun.droidvm.lib.store.vm.SoundUnderrun;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The peripheral list's rows. The + button asks which device first, because the device decides
 * what the rest of the row means -- a virtio-snd card carries a list of endpoints grouped by
 * direction, an Intel HDA codec carries exactly one of each -- so the row shows one field group
 * or the other rather than a lowest common denominator.
 *
 * <p>Host endpoints come from what the phone reports right now ({@link HostAudioDevices}); the
 * row stores the stable descriptor, not the live id, and says so when the stored device is not
 * currently connected.</p>
 */
public final class VMPeripheralEditAdapter extends CardItemAdapter<VMPeripheralEditViewHolder> {
    /**
     * Endpoints one card may carry in one direction. The guest driver registers this many
     * subdevices per direction and ignores the rest, so offering more would offer endpoints that
     * never appear in the guest.
     */
    private static final int MAX_PER_DIRECTION = 4;

    /** Asks for RECORD_AUDIO before a capture-capable device is added; set by the tab. */
    private Consumer<Runnable> micPermissionGate;
    /** Asks for CAMERA before a camera device is added, and drops the add if refused. */
    private Consumer<Runnable> cameraPermissionGate;
    // What the phone reported last time we looked, so binding a row is not a binder call each
    // time it scrolls past. Dropped by refreshHostDevices().
    private List<HostAudioDevices.Entry> outputCache;
    private List<HostAudioDevices.Entry> inputCache;

    public VMPeripheralEditAdapter(@NonNull Context context) {
        super(context);
    }

    public void setMicPermissionGate(@Nullable Consumer<Runnable> gate) {
        this.micPermissionGate = gate;
    }

    public void setCameraPermissionGate(@Nullable Consumer<Runnable> gate) {
        this.cameraPermissionGate = gate;
    }

    @NonNull
    @Override
    protected VMPeripheralEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMPeripheralEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_peripheral_edit;
    }

    @Override
    public void onAddRequested(@NonNull View anchor) {
        // Built from the enum rather than a menu resource, so a new device kind shows up here
        // the moment it is added to PeripheralType.
        var menu = new PopupMenu(context, null).getMenu();
        for (var type : PeripheralType.values()) {
            var item = menu.add(Menu.NONE, type.ordinal(), type.ordinal(),
                type.getDisplayString(context));
            item.setIcon(type.getIconId());
        }
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_peripheral_add_title)
            .setMenu(menu)
            .setListener(item -> {
                addPeripheral(PeripheralType.values()[item.getItemId()]);
                return true;
            })
            .show();
    }

    private void addPeripheral(@NonNull PeripheralType type) {
        Runnable add = () -> {
            var item = DataItem.newObject();
            var cfg = new VMPeripheralConfig(item);
            cfg.setType(type);
            if (type == PeripheralType.VIRTIO_SOUND) {
                // A sound card with no endpoints is a card that does nothing, and what almost
                // everyone wants first is a speaker and a microphone following the phone. Both
                // are removable, and a card that genuinely wants one direction is one tap away.
                var speaker = cfg.addEndpoint();
                speaker.setMode(SoundMode.SPEAKER);
                speaker.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
                var microphone = cfg.addEndpoint();
                microphone.setMode(SoundMode.MICROPHONE);
                microphone.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
            }
            appendItem(item);
        };
        // A camera is gated on consent rather than merely asking for it: without the grant the
        // device could be listed but never opened, so adding one anyway would write a config that
        // is guaranteed to disappoint.
        if (type == PeripheralType.VIRTIO_CAMERA) {
            if (cameraPermissionGate != null) cameraPermissionGate.accept(add);
            else add.run();
            return;
        }
        // Anything that can capture needs the host mic permission asked for, and both kinds now
        // start with a microphone on them.
        if (micPermissionGate != null) {
            micPermissionGate.accept(add);
            return;
        }
        add.run();
    }

    @Override
    public void onBindViewHolder(@NonNull VMPeripheralEditViewHolder holder, int position) {
        var peripheral = new VMPeripheralConfig(items.get(position));
        var type = peripheral.getType();
        holder.ivIcon.setImageResource(type.getIconId());
        holder.tvType.setText(type.getDisplayString(context));
        holder.tvUnavailable.setVisibility(type.isAvailable() ? GONE : VISIBLE);

        holder.groupVirtioSound.setVisibility(type == PeripheralType.VIRTIO_SOUND ? VISIBLE : GONE);
        holder.groupIntelHda.setVisibility(type == PeripheralType.INTEL_HDA ? VISIBLE : GONE);
        holder.groupVirtioCamera.setVisibility(
            type == PeripheralType.VIRTIO_CAMERA ? VISIBLE : GONE);

        switch (type) {
            case VIRTIO_SOUND:
                bindVirtioSound(holder, peripheral);
                break;
            case INTEL_HDA:
                bindIntelHda(holder, peripheral);
                break;
            case VIRTIO_CAMERA:
                bindVirtioCamera(holder, peripheral);
                break;
        }

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
    }

    private void bindVirtioSound(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull VMPeripheralConfig peripheral
    ) {
        // Shared by every endpoint on the card: they describe the device's queues rather than any
        // one endpoint, so there is one of each per card and not one per endpoint.
        holder.btnBuffer.configure(SoundBuffer.class, peripheral.getBuffer());
        holder.btnBuffer.setOnValueChangedListener((oldVal, newVal) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                new VMPeripheralConfig(items.get(pos)).setBuffer((SoundBuffer) newVal);
        });

        // Underruns are a playback failure: the guest was late with a period and the endpoint has
        // nothing to play, so something has to be played instead. Capture fails the other way
        // round -- the host recorded audio and the guest left nowhere to put it -- and there is no
        // equivalent choice to offer, because audio the microphone did not hear cannot be
        // invented. A card with no playback endpoint has nothing to decide.
        boolean anyOutput = false;
        for (var endpoint : peripheral.getEndpoints()) {
            if (!endpoint.getMode().isInput()) anyOutput = true;
        }
        holder.btnUnderrun.setVisibility(anyOutput ? VISIBLE : GONE);
        holder.btnUnderrun.configure(SoundUnderrun.class, peripheral.getUnderrun());
        holder.btnUnderrun.setOnValueChangedListener((oldVal, newVal) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                new VMPeripheralConfig(items.get(pos)).setUnderrun((SoundUnderrun) newVal);
        });

        // Playback above the rule, capture below. A card with neither is legal and starts a card
        // with no streams, which is why both groups go down to nothing.
        bindEndpointGroup(holder, peripheral, SoundMode.SPEAKER, holder.soundOutEndpoints);
        bindEndpointGroup(holder, peripheral, SoundMode.MICROPHONE, holder.soundInEndpoints);

        // One button for both directions, which then asks which one. A row's direction cannot be
        // changed afterwards, so this is the only place the question is asked.
        boolean anyRoom = false;
        for (var mode : SoundMode.values())
            if (countOf(peripheral, mode) < MAX_PER_DIRECTION) anyRoom = true;
        holder.btnAddEndpoint.setEnabled(anyRoom);
        holder.btnAddEndpoint.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            askDirectionAndAdd(new VMPeripheralConfig(items.get(pos)), pos);
        });
    }

    /**
     * Asks which direction to add, then adds it. A direction that is already full is left out of
     * the list rather than shown and refused.
     */
    private void askDirectionAndAdd(@NonNull VMPeripheralConfig peripheral, int pos) {
        var modes = new ArrayList<SoundMode>();
        var labels = new ArrayList<String>();
        for (var mode : SoundMode.values()) {
            if (countOf(peripheral, mode) >= MAX_PER_DIRECTION) continue;
            modes.add(mode);
            labels.add(context.getString(mode.getStringId()));
        }
        if (modes.isEmpty()) return;
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_vm_sound_endpoint_add)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                var mode = modes.get(which);
                Runnable append = () -> {
                    var endpoint = peripheral.addEndpoint();
                    endpoint.setMode(mode);
                    endpoint.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
                    notifyItemChangedSafe(pos);
                };
                if (mode.isInput() && micPermissionGate != null) micPermissionGate.accept(append);
                else append.run();
            })
            .show();
    }

    /**
     * Rebuilds one direction's rows. There are a handful at most, so they are laid out directly.
     *
     * <p>Endpoints are stored as one list for the whole card, so a row's position in its group is
     * not its position in the config; the stored index is what the row's buttons carry.</p>
     */
    private void bindEndpointGroup(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull VMPeripheralConfig peripheral,
        @NonNull SoundMode mode, @NonNull LinearLayout container
    ) {
        container.removeAllViews();
        var inflater = LayoutInflater.from(container.getContext());
        var endpoints = peripheral.getEndpoints();
        boolean input = mode.isInput();

        for (int i = 0; i < endpoints.size(); i++) {
            var endpoint = endpoints.get(i);
            if (endpoint.getMode() != mode) continue;
            final int index = i;
            var row = inflater.inflate(R.layout.item_sound_endpoint, container, false);
            TextView tvMode = row.findViewById(R.id.tv_endpoint_mode);
            MaterialButton btnHost = row.findViewById(R.id.btn_peripheral_host);
            MaterialButton btnRemove = row.findViewById(R.id.btn_endpoint_remove);

            tvMode.setText(mode.getStringId());
            bindHostButton(holder, btnHost, endpoint.getHostDevice(), endpoint.getHostLabel(),
                input);
            btnHost.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                var cfg = new VMPeripheralConfig(items.get(pos));
                if (index >= cfg.getEndpoints().size()) return;
                var target = cfg.getEndpoints().get(index);
                showHostPicker(input, target.getHostDevice(), target.getHostLabel(),
                    (key, label) -> {
                        target.setHostDevice(key, label);
                        notifyItemChangedSafe(pos);
                    });
            });

            btnRemove.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                new VMPeripheralConfig(items.get(pos)).removeEndpoint(index);
                notifyItemChangedSafe(pos);
            });

            container.addView(row);
        }
    }

    private static int countOf(@NonNull VMPeripheralConfig peripheral, @NonNull SoundMode mode) {
        int n = 0;
        for (var endpoint : peripheral.getEndpoints())
            if (endpoint.getMode() == mode) n++;
        return n;
    }

    private void bindIntelHda(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull VMPeripheralConfig peripheral
    ) {
        bindHostButton(holder, holder.btnHdaOut, peripheral.getHostOutDevice(),
            peripheral.getHostOutLabel(), false);
        bindHostButton(holder, holder.btnHdaIn, peripheral.getHostInDevice(),
            peripheral.getHostInLabel(), true);
        holder.btnHdaOut.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var cfg = new VMPeripheralConfig(items.get(pos));
            showHostPicker(false, cfg.getHostOutDevice(), cfg.getHostOutLabel(), (key, label) -> {
                cfg.setHostOutDevice(key, label);
                notifyItemChangedSafe(pos);
            });
        });
        holder.btnHdaIn.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var cfg = new VMPeripheralConfig(items.get(pos));
            showHostPicker(true, cfg.getHostInDevice(), cfg.getHostInLabel(), (key, label) -> {
                cfg.setHostInDevice(key, label);
                notifyItemChangedSafe(pos);
            });
        });
    }

    private void bindVirtioCamera(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull VMPeripheralConfig peripheral
    ) {
        var key = peripheral.getHostDevice();
        holder.btnCameraDevice.setText(
            HostCameraDevices.labelOf(context, key, peripheral.getHostLabel()));
        // Same warning as an audio endpoint that is unplugged: a config carried over from another
        // phone can name a camera this one does not have.
        boolean missing = !key.isEmpty() && !hasCamera(key);
        holder.tvWarning.setVisibility(missing ? VISIBLE : GONE);
        if (missing) holder.tvWarning.setText(R.string.edit_vm_peripheral_camera_missing);
        holder.btnCameraDevice.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var cfg = new VMPeripheralConfig(items.get(pos));
            showCameraPicker(cfg.getHostDevice(), cfg.getHostLabel(), (chosen, label) -> {
                cfg.setHostDevice(chosen, label);
                notifyItemChangedSafe(pos);
            });
        });
    }

    private boolean hasCamera(@NonNull String key) {
        for (var entry : HostCameraDevices.list(context)) {
            if (entry.key.equals(key)) return true;
        }
        return false;
    }

    /**
     * One camera out of the host's list, plus "host default".
     *
     * <p>No second question after it, unlike the audio picker: a camera has no equivalent of an
     * audio stream's purpose -- what it is used for does not change which sensor is meant.</p>
     */
    private void showCameraPicker(
        @NonNull String current, @NonNull String storedLabel, @NonNull HostPicked onPicked
    ) {
        var keys = new ArrayList<String>();
        var labels = new ArrayList<String>();
        keys.add(HostCameraDevices.DEFAULT_KEY);
        labels.add(context.getString(R.string.host_camera_default));
        for (var entry : HostCameraDevices.list(context)) {
            keys.add(entry.key);
            labels.add(entry.label);
        }
        if (!current.isEmpty() && !keys.contains(current)) {
            keys.add(current);
            labels.add(context.getString(R.string.edit_vm_peripheral_host_missing_item,
                storedLabel.isEmpty() ? current : storedLabel));
        }
        int checked = Math.max(0, keys.indexOf(current));
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_vm_peripheral_host_camera)
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                dialog.dismiss();
                onPicked.onPicked(keys.get(which), which == 0 ? "" : labels.get(which));
            })
            .show();
    }

    /**
     * Names the chosen endpoint on the button, and says so when the config points at something
     * that is not plugged in or paired right now -- the VM still starts, on the host's own
     * routing.
     */
    private void bindHostButton(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull MaterialButton button,
        @NonNull String key, @NonNull String storedLabel, boolean input
    ) {
        var device = HostAudioDevices.deviceOf(key);
        if (device.isEmpty() || HostAudioDevices.SYSTEM_DEFAULT_KEY.equals(device)) {
            button.setText(withPurpose(context.getString(R.string.edit_vm_peripheral_host_default),
                key, input));
            holder.tvWarning.setVisibility(GONE);
            return;
        }
        var live = findLive(input, device);
        var label = live != null ? live.label : storedLabel;
        button.setText(withPurpose(label.isEmpty() ? device : label, key, input));
        if (live == null) {
            holder.tvWarning.setText(R.string.edit_vm_peripheral_host_missing);
            holder.tvWarning.setVisibility(VISIBLE);
        }
    }

    /** Called with (stableKey, label) once the user picks. */
    private interface HostPicked {
        void onPicked(@NonNull String key, @NonNull String label);
    }

    /**
     * The endpoint's name with what it is used for after it, as {@code speaker (Media, Music)}.
     *
     * <p>The stored key is a machine-readable thing -- {@code BUILTIN_MIC|bottom#preset=voice_
     * communication} -- and showing it would be both long and in the wrong language. Every
     * attribute is an enumeration, so each value has a name worth reading; anything left at the
     * platform's default contributes nothing rather than the word "default", because a list of
     * defaults is noise.</p>
     */
    @NonNull
    private String withPurpose(@NonNull String name, @NonNull String key, boolean input) {
        var parts = new ArrayList<String>();
        for (var attribute : SoundPurpose.attributesFor(input)) {
            var value = HostAudioDevices.attrOf(key, attribute);
            if (value.isEmpty()) continue;
            var choice = SoundPurpose.find(attribute, value);
            // An unknown value is shown as it was stored: it came from somewhere, and hiding it
            // would make a setting that does nothing look like one that was never made.
            parts.add(choice != null ? context.getString(choice.titleId) : value);
        }
        return parts.isEmpty() ? name : fmt("%s (%s)", name, String.join(", ", parts));
    }

    private void showHostPicker(
        boolean input, @NonNull String current, @NonNull String storedLabel,
        @NonNull HostPicked onPicked
    ) {
        var keys = new ArrayList<String>();
        var labels = new ArrayList<String>();
        // The platform's own routing: what to pick when the VM should follow whatever the phone
        // is doing. Named rather than blank, so what gets stored is a device like any other.
        keys.add(HostAudioDevices.SYSTEM_DEFAULT_KEY);
        labels.add(context.getString(R.string.edit_vm_peripheral_host_default));
        for (var entry : liveDevices(input)) {
            keys.add(entry.key);
            labels.add(entry.label);
        }
        // Matched on the endpoint alone: what the stream is used for does not change which
        // device is meant, and comparing the whole key would make every configured purpose look
        // like a device that had gone missing.
        var currentDevice = HostAudioDevices.deviceOf(current);
        // A configured-but-absent device stays selectable so opening the picker cannot silently
        // drop it.
        if (!currentDevice.isEmpty() && !keys.contains(currentDevice)) {
            keys.add(currentDevice);
            labels.add(context.getString(R.string.edit_vm_peripheral_host_missing_item,
                storedLabel.isEmpty() ? currentDevice : storedLabel));
        }
        int checked = Math.max(0, keys.indexOf(currentDevice));
        DialogInterface.OnClickListener onClick = (dialog, which) -> {
            dialog.dismiss();
            // Then what it is for. Asked separately because it is a separate question: the same
            // microphone recorded for a call and for transcription is the same microphone, with
            // different processing.
            showPurposePicker(input, keys.get(which), which == 0 ? "" : labels.get(which),
                current, onPicked);
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(input
                ? R.string.edit_vm_peripheral_pick_input
                : R.string.edit_vm_peripheral_pick_output)
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, onClick)
            .show();
    }

    /**
     * Asks what the stream is for, one attribute at a time, and hands back the whole key.
     *
     * <p>Only the values an ordinary application may ask for are offered. The rest exist in the
     * platform, and a stream that names one simply fails to open, so a picker that listed them
     * would be offering choices it knows cannot be honoured.</p>
     */
    private void showPurposePicker(
        boolean input, @NonNull String device, @NonNull String deviceLabel,
        @NonNull String previous, @NonNull HostPicked onPicked
    ) {
        var attributes = SoundPurpose.attributesFor(input);
        var values = new ArrayList<String>();
        for (int i = 0; i < attributes.size(); i++) values.add("");
        askPurpose(new PurposeWalk(device, deviceLabel, previous, attributes, values, onPicked), 0);
    }

    /**
     * One walk through one endpoint's attributes. Everything but the position is settled before
     * the first dialog opens, so the position is the only thing each dialog passes to the next.
     */
    private static final class PurposeWalk {
        /** The device the key is being built for, and the label to hand back with it. */
        final String device;
        final String deviceLabel;
        /** The key configured before this walk, which each question defaults to. */
        final String previous;
        /** The attributes to ask about, in order, and the answers so far -- one slot each. */
        final List<String> attributes;
        final ArrayList<String> values;
        final HostPicked onPicked;

        PurposeWalk(
            @NonNull String device, @NonNull String deviceLabel, @NonNull String previous,
            @NonNull List<String> attributes, @NonNull ArrayList<String> values,
            @NonNull HostPicked onPicked
        ) {
            this.device = device;
            this.deviceLabel = deviceLabel;
            this.previous = previous;
            this.attributes = attributes;
            this.values = values;
            this.onPicked = onPicked;
        }
    }

    private void askPurpose(@NonNull PurposeWalk walk, int at) {
        if (at >= walk.attributes.size()) {
            walk.onPicked.onPicked(
                HostAudioDevices.withAttrs(walk.device, walk.attributes, walk.values),
                walk.deviceLabel);
            return;
        }
        var attribute = walk.attributes.get(at);
        var choices = SoundPurpose.choicesFor(attribute);
        var labels = new ArrayList<String>();
        var stored = new ArrayList<String>();
        // Leaving it to the platform is a real answer, and the first one: most endpoints want
        // exactly that.
        labels.add(context.getString(R.string.edit_vm_sound_purpose_unset));
        stored.add("");
        for (var choice : choices) {
            labels.add(context.getString(choice.titleId));
            stored.add(choice.value);
        }
        // Carried over from what was configured before, so re-picking a device does not quietly
        // discard how it was set up.
        int checked = Math.max(0,
            stored.indexOf(HostAudioDevices.attrOf(walk.previous, attribute)));
        new MaterialAlertDialogBuilder(context)
            .setTitle(SoundPurpose.titleFor(attribute))
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                dialog.dismiss();
                walk.values.set(at, stored.get(which));
                askPurpose(walk, at + 1);
            })
            .show();
    }

    @Nullable
    private HostAudioDevices.Entry findLive(boolean input, @NonNull String key) {
        for (var entry : liveDevices(input))
            if (entry.key.equals(key)) return entry;
        return null;
    }

    @NonNull
    private List<HostAudioDevices.Entry> liveDevices(boolean input) {
        if (input) {
            if (inputCache == null) inputCache = HostAudioDevices.list(context, true);
            return inputCache;
        }
        if (outputCache == null) outputCache = HostAudioDevices.list(context, false);
        return outputCache;
    }

    private void notifyItemChangedSafe(int position) {
        try {
            notifyItemChanged(position);
        } catch (Exception ignored) {
            // RecyclerView refuses changes while it is computing a layout; the next bind picks
            // the value up from the model anyway.
        }
    }

    /** Re-reads the live device list, e.g. after the user plugged something in. */
    @SuppressLint("NotifyDataSetChanged")
    public void refreshHostDevices() {
        outputCache = null;
        inputCache = null;
        notifyDataSetChanged();
    }
}
