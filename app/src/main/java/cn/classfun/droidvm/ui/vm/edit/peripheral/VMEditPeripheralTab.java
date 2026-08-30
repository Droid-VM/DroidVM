        adapter.setMicPermissionGate(parent::ensureRecordAudioThen);
        if (!parent.editMode) {
            var peripherals = DataItem.newArray();
            peripherals.append(VMPeripheralConfig.createDefaultVirtioSound().item);
            listPeripherals.setItems(peripherals);
        }
    /** Whether the unsaved peripheral rows give the guest a host microphone. */
    public boolean hasMicrophone() {
        for (var peripheral : VMPeripheralConfig.listOf(wrap()))
            for (var endpoint : peripheral.getEndpoints())
                if (endpoint.getMode().isInput()) return true;
        return false;
    }

