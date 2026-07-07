package cn.classfun.droidvm.ui.vm.edit.boot;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD_DEBUG;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_KERNEL;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFilePath;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellAsyncCheckExists;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.regex.Pattern;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.CopyableField;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.vm.boot.BootEntries;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.basic.VMEditBasicTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditTab;
import cn.classfun.droidvm.ui.vm.edit.storage.VMEditStorageTab;
import cn.classfun.droidvm.ui.widgets.container.CollapsibleContainer;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMEditBootTab extends VMEditBaseTab {
    /** Captures a root=/resume= param: group 1 = key, group 2 = value. */
    private static final Pattern ROOT_PARAM =
        Pattern.compile("(?:^|\\s)(root|resume)=(\\S+)");

    private SwitchRowWidget swAutoUp;
    private DropdownRowWidget ddProtocol;
    private CollapsibleContainer containerUefi;
    private TextInputRowWidget inputUefiFirmware;
    private CollapsibleContainer containerKernel;
    private DropdownRowWidget ddSource;
    private View layoutImage;
    private View layoutManual;
    private DropdownRowWidget ddBootDisk;
    private DropdownRowWidget ddBootEntry;
    private ImageButton btnRescan;
    private EditText etDetectKernel;
    private EditText etDetectInitrd;
    private EditText etDetectCmdline;
    private EditText etDetectSource;
    private TextView tvKernelDmaWarn;
    private TextView tvDetectWarn;
    private MaterialSwitch swVdafix;
    private EditText etCmdlineOverride;
    private TextInputRowWidget inputBootWait;
    private TextInputRowWidget inputKernel;
    private TextInputRowWidget inputInitrd;
    private TextInputRowWidget inputCmdline;

    /** The config being edited; the source of the disks list for scans. */
    private VMConfig config;
    private BootConfig.Protocol protocol = BootConfig.Protocol.UEFI;
    private BootConfig.LinuxSource source = BootConfig.LinuxSource.MANUAL;
    private int bootDiskIndex = 0;
    @Nullable
    private BootConfig.ImageEntry pinnedEntry;
    @Nullable
    private BootEntries scanned;
    @Nullable
    private String lastScannedImage;

    public VMEditBootTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        swAutoUp = view.findViewById(R.id.sw_auto_up);
        ddProtocol = view.findViewById(R.id.dd_boot_protocol);
        containerUefi = view.findViewById(R.id.container_uefi);
        inputUefiFirmware = view.findViewById(R.id.input_uefi_firmware);
        containerKernel = view.findViewById(R.id.container_kernel);
        ddSource = view.findViewById(R.id.dd_kernel_source);
        layoutImage = view.findViewById(R.id.layout_kernel_image);
        layoutManual = view.findViewById(R.id.layout_kernel_manual);
        ddBootDisk = view.findViewById(R.id.dd_boot_disk);
        ddBootEntry = view.findViewById(R.id.dd_boot_entry);
        btnRescan = view.findViewById(R.id.btn_boot_rescan);
        etDetectKernel = view.findViewById(R.id.et_detect_kernel);
        etDetectInitrd = view.findViewById(R.id.et_detect_initrd);
        etDetectCmdline = view.findViewById(R.id.et_detect_cmdline);
        etDetectSource = view.findViewById(R.id.et_detect_source);
        tvKernelDmaWarn = view.findViewById(R.id.tv_boot_kernel_dma_warn);
        tvDetectWarn = view.findViewById(R.id.tv_boot_detect_warn);
        swVdafix = view.findViewById(R.id.sw_vdafix);
        etCmdlineOverride = view.findViewById(R.id.et_cmdline_override);
        inputBootWait = view.findViewById(R.id.input_boot_wait);
        inputKernel = view.findViewById(R.id.input_kernel);
        inputInitrd = view.findViewById(R.id.input_initrd);
        inputCmdline = view.findViewById(R.id.input_cmdline);
        // Detection fields are read-only: selectable (long-press Copy) + a copy
        // end-icon, never editable. Tapping the cmdline (intent to edit it)
        // seeds the override field below and jumps the caret there.
        CopyableField.setupReadOnly(etDetectKernel, parent.getString(R.string.edit_vm_boot_detect_kernel));
        CopyableField.setupReadOnly(etDetectInitrd, parent.getString(R.string.edit_vm_boot_detect_initrd));
        CopyableField.setupReadOnly(etDetectSource, parent.getString(R.string.edit_vm_boot_detect_source));
        CopyableField.setupReadOnly(etDetectCmdline, parent.getString(R.string.edit_vm_boot_detect_cmdline));
        etDetectCmdline.setOnClickListener(
            v -> redirectCmdlineEditToOverride(etDetectCmdline.getSelectionStart()));
    }

    @Override
    public void initValue() {
        ddProtocol.setAdapter(IconItemAdapter.create(parent, new String[]{
            BootConfig.Protocol.UEFI.getDisplayString(parent),
            BootConfig.Protocol.LINUX.getDisplayString(parent),
        }, new int[]{R.drawable.ic_uefi_nocolor, R.drawable.ic_linux}));
        ddProtocol.setOnItemClickListener((p, v, pos, id) -> {
            protocol = pos == 0
                ? BootConfig.Protocol.UEFI : BootConfig.Protocol.LINUX;
            applyVisibility();
        });
        ddSource.setAdapter(IconItemAdapter.create(parent, new String[]{
            BootConfig.LinuxSource.IMAGE.getDisplayString(parent),
            BootConfig.LinuxSource.MANUAL.getDisplayString(parent),
        }, R.drawable.ic_linux));
        ddSource.setOnItemClickListener((p, v, pos, id) -> {
            source = pos == 0
                ? BootConfig.LinuxSource.IMAGE : BootConfig.LinuxSource.MANUAL;
            applyVisibility();
            if (isImageMode() && scanned == null) rescan();
        });
        inputUefiFirmware.setIconButtonOnClickListener(this::showFirmwareBrowseDialog);
        inputKernel.setIconButtonOnClickListener(this::showKernelBrowseDialog);
        inputInitrd.setIconButtonOnClickListener(this::showInitrdBrowseDialog);
        btnRescan.setOnClickListener(v -> rescan());
        swVdafix.setOnCheckedChangeListener((btn, checked) -> updateDetectionCard());
        // create mode never calls loadConfig, so seed the defaults here:
        // UEFI protocol; manual source prefilled with the builtin kernel
        ddProtocol.setText(protocol.getDisplayString(parent));
        ddSource.setText(source.getDisplayString(parent));
        inputKernel.setTextAndMoveCursor(PATH_BUILTIN_KERNEL);
        inputInitrd.setTextAndMoveCursor(PATH_BUILTIN_INITRD);
        inputCmdline.setTextAndMoveCursor(BootConfig.DEFAULT_MANUAL_CMDLINE);
        inputBootWait.setTextAndMoveCursor(String.valueOf(BootConfig.DEFAULT_BOOT_WAIT));
        applyVisibility();
    }

    @Override
    public void onTabShown() {
        if (!isImageMode()) return;
        updateDiskDropdown();
        var image = scanImagePath();
        // disks added/changed in the storage tab since the last scan
        if (image == null || !image.equals(lastScannedImage)) {
            rescan();
        } else if (scanned != null) {
            // same image, but the protection mode may have changed in the
            // basic tab -- refresh the detection card so its warning tracks it
            updateDetectionCard();
        }
    }

    private boolean isImageMode() {
        return protocol == BootConfig.Protocol.LINUX
            && source == BootConfig.LinuxSource.IMAGE;
    }

    private void applyVisibility() {
        boolean uefi = protocol == BootConfig.Protocol.UEFI;
        containerUefi.setVisibility(uefi ? VISIBLE : GONE);
        containerKernel.setVisibility(uefi ? GONE : VISIBLE);
        boolean image = source == BootConfig.LinuxSource.IMAGE;
        layoutImage.setVisibility(image ? VISIBLE : GONE);
        layoutManual.setVisibility(image ? GONE : VISIBLE);
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        this.config = config;
        var boot = BootConfig.of(config);
        protocol = boot.getProtocol();
        source = boot.getLinuxSource();
        ddProtocol.setText(protocol.getDisplayString(parent));
        ddSource.setText(source.getDisplayString(parent));
        inputUefiFirmware.setTextAndMoveCursor(boot.getUefiFirmware());
        inputKernel.setTextAndMoveCursor(boot.getKernel());
        inputInitrd.setTextAndMoveCursor(boot.getInitrd());
        bootDiskIndex = boot.getImageDisk();
        pinnedEntry = boot.getImageEntry();
        swVdafix.setChecked(boot.isVdafix());
        inputBootWait.setTextAndMoveCursor(String.valueOf(boot.getBootWait()));
        inputCmdline.setTextAndMoveCursor(boot.getCmdline());
        etCmdlineOverride.setText(boot.getImageCmdline());
        swAutoUp.setChecked(config.item.optBoolean("auto_up", false));
        updateDiskDropdown();
        updateEntryDropdown();
        applyVisibility();
        if (isImageMode()) rescan();
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (protocol == BootConfig.Protocol.UEFI) {
            var firmware = inputUefiFirmware.getText();
            inputUefiFirmware.setError(null);
            if (!firmware.isEmpty() && !checkFilePath(firmware, true)) {
                inputUefiFirmware.setError(parent.getString(R.string.create_vm_error_path_invalid));
                return false;
            }
            return true;
        }
        if (source == BootConfig.LinuxSource.IMAGE) {
            inputBootWait.setError(null);
            if (scanImagePath() == null) {
                showDetectError(parent.getString(R.string.edit_vm_boot_detect_no_disk));
                return false;
            }
            try {
                var wait = inputBootWait.getText().trim();
                if (!wait.isEmpty()) Long.parseLong(wait);
            } catch (NumberFormatException e) {
                inputBootWait.setError(parent.getString(R.string.edit_vm_boot_wait_invalid));
                return false;
            }
            return true;
        }
        var kernel = inputKernel.getText();
        var initrd = inputInitrd.getText();
        inputKernel.setError(null);
        inputInitrd.setError(null);
        if (!checkFilePath(kernel, true)) {
            inputKernel.setError(parent.getString(R.string.create_vm_error_path_invalid));
            return false;
        }
        if (!initrd.isEmpty() && !checkFilePath(initrd, true)) {
            inputInitrd.setError(parent.getString(R.string.create_vm_error_path_invalid));
            return false;
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var boot = BootConfig.of(config);
        boot.setProtocol(protocol);
        boot.setUefiFirmware(inputUefiFirmware.getText());
        boot.setLinuxSource(source);
        boot.setKernel(inputKernel.getText());
        boot.setInitrd(inputInitrd.getText());
        // separate fields: switching source must not clobber the other's
        boot.setCmdline(inputCmdline.getText());
        boot.setImageCmdline(etCmdlineOverride.getText().toString());
        boot.setImageDisk(bootDiskIndex);
        boot.setImageEntry(pinnedEntry);
        boot.setVdafix(swVdafix.isChecked());
        var wait = inputBootWait.getText().trim();
        try {
            boot.setBootWait(wait.isEmpty()
                ? BootConfig.DEFAULT_BOOT_WAIT : Long.parseLong(wait));
        } catch (NumberFormatException ignored) {
        }
        config.item.set("auto_up", swAutoUp.isChecked());
    }

    // --- image source: disk/entry dropdowns + detection card ---

    /**
     * Disk paths as currently edited in the storage tab (a disk added
     * there must be visible here before the config is saved), falling
     * back to the loaded config.
     */
    @NonNull
    private ArrayList<String> diskPaths() {
        var out = new ArrayList<String>();
        DataItem disks = null;
        try {
            var storage = (VMEditStorageTab) parent.getTab(VMEditTab.TAB_STORAGE);
            disks = storage.getCurrentDisks();
        } catch (Exception ignored) {
        }
        if (disks == null && config != null)
            disks = config.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return out;
        for (var disk : disks.asArray())
            out.add(disk.optString("path", ""));
        return out;
    }

    @Nullable
    private String diskPath(int index) {
        var paths = diskPaths();
        if (index < 0 || index >= paths.size()) return null;
        var p = paths.get(index);
        return p.isEmpty() ? null : p;
    }

    @Nullable
    private String firstDiskPath() {
        var paths = diskPaths();
        for (int i = 0; i < paths.size(); i++)
            if (!paths.get(i).isEmpty()) return paths.get(i);
        return null;
    }

    @Nullable
    private String scanImagePath() {
        var p = diskPath(bootDiskIndex);
        return p != null ? p : firstDiskPath();
    }

    @NonNull
    private static String basename(@NonNull String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void updateDiskDropdown() {
        var paths = diskPaths();
        var labels = new ArrayList<String>();
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).isEmpty()) continue;
            labels.add(basename(paths.get(i)));
            indexes.add(i);
        }
        ddBootDisk.setAdapter(IconItemAdapter.create(
            parent, labels, R.drawable.ic_nav_disk));
        ddBootDisk.setOnItemClickListener((p, v, pos, id) -> {
            bootDiskIndex = indexes.get(pos);
            rescan();
        });
        var current = scanImagePath();
        ddBootDisk.setText(current == null
            ? parent.getString(R.string.edit_vm_boot_detect_no_disk)
            : basename(current));
    }

    private void updateEntryDropdown() {
        var labels = new ArrayList<String>();
        labels.add(parent.getString(R.string.edit_vm_boot_entry_auto));
        var entries = scanned;
        if (entries != null)
            for (var e : entries.entries)
                labels.add(e.displayLabel(parent));
        ddBootEntry.setAdapter(IconItemAdapter.create(
            parent, labels, R.drawable.ic_linux));
        ddBootEntry.setOnItemClickListener((p, v, pos, id) -> {
            pinnedEntry = pos == 0 || entries == null
                ? null : entries.entries.get(pos - 1).toImageEntry();
            updateDetectionCard();
        });
        // the selected label: pinned entry when it still exists, else auto
        var label = parent.getString(R.string.edit_vm_boot_entry_auto);
        if (pinnedEntry != null) {
            if (entries != null && !entries.isFallback(pinnedEntry)) {
                var resolved = entries.resolve(pinnedEntry);
                if (resolved != null) label = resolved.displayLabel(parent);
            } else if (pinnedEntry.title != null) {
                label = pinnedEntry.title;
            } else if (pinnedEntry.id != null) {
                label = pinnedEntry.id;
            }
        }
        ddBootEntry.setText(label);
    }

    private void rescan() {
        scanned = null;
        var image = scanImagePath();
        lastScannedImage = image;
        updateDiskDropdown();
        updateEntryDropdown();
        if (image == null) {
            showDetectError(parent.getString(R.string.edit_vm_boot_detect_no_disk));
            return;
        }
        setDetectFields("", "", null, "");
        etDetectKernel.setText(R.string.edit_vm_boot_detect_scanning);
        tvKernelDmaWarn.setVisibility(GONE);
        tvDetectWarn.setVisibility(GONE);
        BootEntries.scan(image, (result, error) -> parent.runOnUiThread(() -> {
            if (parent.isFinishing()) return;
            scanned = result;
            if (result == null) {
                showDetectError(parent.getString(
                    R.string.edit_vm_boot_detect_failed, error));
            } else {
                updateEntryDropdown();
                updateDetectionCard();
            }
        }));
    }

    private void showDetectError(@NonNull String message) {
        setDetectFields("", "", null, "");
        tvKernelDmaWarn.setVisibility(GONE);
        tvDetectWarn.setText(message);
        tvDetectWarn.setVisibility(VISIBLE);
    }

    private void setDetectFields(
        @NonNull String kernel,
        @NonNull String initrd,
        @Nullable CharSequence cmdline,
        @NonNull String source
    ) {
        etDetectKernel.setText(kernel);
        etDetectInitrd.setText(initrd);
        etDetectCmdline.setText(cmdline);
        etDetectSource.setText(source);
    }

    /**
     * The detected cmdline is read-only; tapping it (intent to edit) seeds the
     * override field with the same text and moves the caret to the tapped
     * offset, so customizing continues seamlessly in the editable override.
     * Long-press still selects/copies (it doesn't fire the click).
     */
    private void redirectCmdlineEditToOverride(int caret) {
        var t = etDetectCmdline.getText();
        var detected = t == null ? "" : t.toString();
        // Seed the override from the preview only when it has no edits yet, so
        // re-tapping the preview never clobbers an existing customization.
        var ov = etCmdlineOverride.getText();
        if (ov == null || ov.length() == 0)
            etCmdlineOverride.setText(detected);
        etCmdlineOverride.requestFocus();
        etCmdlineOverride.setSelection(Math.max(0, Math.min(caret, etCmdlineOverride.length())));
        var imm = (InputMethodManager) parent.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.showSoftInput(etCmdlineOverride, 0);
    }

    private void updateDetectionCard() {
        var entries = scanned;
        if (entries == null) return;
        var entry = entries.resolve(pinnedEntry);
        if (entry == null) {
            showDetectError(parent.getString(
                R.string.edit_vm_boot_detect_failed, "no entries"));
            return;
        }
        setDetectFields(
            entry.kernel,
            String.join(", ", entry.initrd),
            highlightRootParams(entry, swVdafix.isChecked()),
            entry.source
        );
        // protected-VM DMA warning sits directly under the kernel field
        if (isProtectedVm() && entry.lacksRestrictedDmaPool()) {
            tvKernelDmaWarn.setText(R.string.edit_vm_boot_protected_dma_warn);
            tvKernelDmaWarn.setVisibility(VISIBLE);
        } else {
            tvKernelDmaWarn.setVisibility(GONE);
        }
        if (entries.isFallback(pinnedEntry)) {
            tvDetectWarn.setText(parent.getString(
                R.string.edit_vm_boot_menu_fallback, pinnedLabel()));
            tvDetectWarn.setVisibility(VISIBLE);
        } else {
            tvDetectWarn.setVisibility(GONE);
        }
    }

    /**
     * Whether the VM would boot as a gunyah protected VM (protected, or
     * protected-without-firmware) -- read live from the basic tab so a
     * just-changed selection is reflected, then the stored config, then
     * the backend default. In such a VM a guest kernel without
     * CONFIG_DMA_RESTRICTED_POOL cannot drive virtio.
     */
    private boolean isProtectedVm() {
        ProtectedVM pvm = null;
        try {
            var basic = (VMEditBasicTab) parent.getTab(VMEditTab.TAB_BASIC);
            pvm = basic.getCurrentProtectedVm();
        } catch (Exception ignored) {
        }
        if (pvm == null && config != null)
            pvm = optEnum(config.item, "protected_vm", PROTECTED_WITHOUT_FIRMWARE);
        if (pvm == null) pvm = PROTECTED_WITHOUT_FIRMWARE;
        return pvm == ProtectedVM.PROTECTED_PROTECTED
            || pvm == ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
    }

    /**
     * Colors a root=/resume= value red to flag the vdafix rewrite. With vdafix
     * off we show the raw cmdline and mark the /dev/sdX device that is about to
     * be replaced (a PARTUUID root is fine and stays unmarked). With vdafix on
     * we show the fixed cmdline and mark only the values the fix actually
     * changed, so an already-PARTUUID root (and any unrelated param) is left
     * uncolored.
     */
    @NonNull
    private CharSequence highlightRootParams(@NonNull BootEntries.Entry entry, boolean vdafix) {
        var shown = entry.effectiveCmdline(vdafix);
        if (shown.isEmpty()) return shown;
        var span = new SpannableString(shown);
        var color = MaterialColors.getColor(
            etDetectCmdline, androidx.appcompat.R.attr.colorError);
        var m = ROOT_PARAM.matcher(shown);
        while (m.find()) {
            var key = m.group(1);
            var value = m.group(2);
            if (key == null || value == null) continue;
            boolean mark;
            if (!vdafix) {
                // off: the device path that the fix would replace
                mark = value.startsWith("/dev/");
            } else {
                // on: only what the fix changed for this key
                var original = paramValue(entry.cmdline, key);
                mark = original != null && !original.equals(value);
            }
            if (mark) span.setSpan(
                new ForegroundColorSpan(color),
                m.start(2), m.end(2),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return span;
    }

    /** The value of {@code key}=... in {@code cmdline}, or null if absent. */
    @Nullable
    private static String paramValue(@NonNull String cmdline, @NonNull String key) {
        var m = ROOT_PARAM.matcher(cmdline);
        while (m.find())
            if (key.equals(m.group(1))) return m.group(2);
        return null;
    }

    @NonNull
    private String pinnedLabel() {
        if (pinnedEntry == null) return "";
        if (pinnedEntry.title != null) return pinnedEntry.title;
        return pinnedEntry.id != null ? pinnedEntry.id : "";
    }

    // --- browse menus ---

    private void showFirmwareBrowseDialog() {
        MenuItem.OnMenuItemClickListener listener = item -> {
            var id = item.getItemId();
            if (id == R.id.menu_firmware_builtin) {
                inputUefiFirmware.setText("");
            } else if (id == R.id.menu_firmware_external) {
                parent.currentPicker = uri -> {
                    if (uri != null)
                        inputUefiFirmware.setTextAndMoveCursor(resolveUriPath(parent, uri));
                    parent.currentPicker = null;
                };
                parent.filePickerLauncher.launch(new String[]{"*/*"});
            }
            return true;
        };
        MenuDialogBuilder.showSimple(
            parent,
            R.string.edit_vm_uefi_firmware_browse_title,
            R.menu.menu_vm_firmware_browse,
            listener
        );
    }

    private void showKernelBrowseDialog() {
        MenuItem.OnMenuItemClickListener listener = item -> {
            onKernelBrowseClick(item);
            return true;
        };
        MenuDialogBuilder.showSimple(
            parent,
            R.string.edit_vm_kernel_browse_title,
            R.menu.menu_vm_kernel_browse,
            listener
        );
    }

    private void showInitrdBrowseDialog() {
        MenuItem.OnMenuItemClickListener listener = item -> {
            onInitrdBrowseClick(item);
            return true;
        };
        MenuDialogBuilder.showSimple(
            parent,
            R.string.edit_vm_initrd_browse_title,
            R.menu.menu_vm_initrd_browse,
            listener
        );
    }

    private void onKernelBrowseClick(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_kernel_clear) {
            inputKernel.setText("");
            return;
        }
        if (id == R.id.menu_kernel_external) {
            parent.currentPicker = uri -> {
                if (uri != null)
                    inputKernel.setTextAndMoveCursor(resolveUriPath(parent, uri));
                parent.currentPicker = null;
            };
            parent.filePickerLauncher.launch(new String[]{"*/*"});
            return;
        }
        String path;
        if (id == R.id.menu_kernel_builtin) {
            path = PATH_BUILTIN_KERNEL;
        } else if (id == R.id.menu_kernel_microdroid) {
            path = PATH_MICRODROID_KERNEL;
        } else {
            return;
        }
        shellAsyncCheckExists(parent, path, exists -> {
            if (exists) {
                inputKernel.setTextAndMoveCursor(path);
            } else {
                new MaterialAlertDialogBuilder(parent)
                    .setTitle(R.string.edit_vm_kernel_browse_title)
                    .setMessage(parent.getString(R.string.edit_vm_kernel_not_found, path))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
    }

    private void onInitrdBrowseClick(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_initrd_clear) {
            inputInitrd.setText("");
            return;
        }
        if (id == R.id.menu_initrd_external) {
            parent.currentPicker = uri -> {
                if (uri != null)
                    inputInitrd.setTextAndMoveCursor(resolveUriPath(parent, uri));
                parent.currentPicker = null;
            };
            parent.filePickerLauncher.launch(new String[]{"*/*"});
            return;
        }
        String path;
        if (id == R.id.menu_initrd_builtin) {
            path = PATH_BUILTIN_INITRD;
        } else if (id == R.id.menu_initrd_microdroid) {
            path = PATH_MICRODROID_INITRD;
        } else if (id == R.id.menu_initrd_microdroid_debug) {
            path = PATH_MICRODROID_INITRD_DEBUG;
        } else {
            return;
        }
        shellAsyncCheckExists(parent, path, exists -> {
            if (exists) {
                inputInitrd.setTextAndMoveCursor(path);
            } else {
                new MaterialAlertDialogBuilder(parent)
                    .setTitle(R.string.edit_vm_initrd_browse_title)
                    .setMessage(parent.getString(R.string.edit_vm_initrd_not_found, path))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
    }
}
