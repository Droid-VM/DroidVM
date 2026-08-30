import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
    private CardItemListView listSerialPorts;
        listSerialPorts = view.findViewById(R.id.list_serial_ports);
        adapter.setMicPermissionGate(parent::ensureRecordAudioThen);
        listSerialPorts.setAdapter(VMSerialEditAdapter.class);
        if (!parent.editMode) {
            var peripherals = DataItem.newArray();
            peripherals.append(VMPeripheralConfig.createDefaultVirtioSound().item);
            listPeripherals.setItems(peripherals);
        }
        // A brand-new VM never goes through loadConfig, but its serial list is not empty:
        // the fixed COM quartet exists either way, so show it (COM1 as the app console).
        var scratch = DataItem.newObject();
        VMSerialConfig.ensureDefaults(scratch);
        listSerialPorts.setItems(scratch.opt(VMSerialConfig.KEY, DataItem.newArray()));
    /** Whether the unsaved peripheral rows give the guest a host microphone. */
    public boolean hasMicrophone() {
        for (var peripheral : VMPeripheralConfig.listOf(wrap()))
            for (var endpoint : peripheral.getEndpoints())
                if (endpoint.getMode().isInput()) return true;
        return false;
    }

        // VMConfig's constructor already materialized the fixed quartet for configs from
        // before "serial_ports"; ensureDefaults here only covers configs built by hand.
        VMSerialConfig.ensureDefaults(config.item);
        listSerialPorts.setItems(config.item.opt(VMSerialConfig.KEY, DataItem.newArray()));
        // A path-based serial backend without a path has nowhere to put the bytes. PTY is the
        // exception: its path is an optional convenience symlink. Two USB ACM ports on one
        // slot would be a guaranteed busy-refusal at boot, so it fails here instead.
        var usbSlots = new ArrayList<Integer>();
        for (var iter : requireNonNull(listSerialPorts.getItems())) {
            var port = new VMSerialConfig(iter.getValue());
            var backend = port.getBackend();
            if (backend.usesPath() && backend != SerialBackend.PTY && port.getPath().isEmpty())
                return showValidateFailed(R.string.edit_vm_serial_path_required);
            if (backend == SerialBackend.USB_ACM) {
                if (usbSlots.contains(port.getUsbSlot()))
                    return showValidateFailed(R.string.edit_vm_serial_slot_duplicate);
                usbSlots.add(port.getUsbSlot());
            }
        }
        config.item.set(VMSerialConfig.KEY, requireNonNull(listSerialPorts.getItems()));
