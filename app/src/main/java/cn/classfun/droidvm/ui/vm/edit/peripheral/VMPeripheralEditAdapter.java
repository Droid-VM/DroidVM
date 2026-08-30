                // Keep manually added cards identical to the one a new VM starts with.
                appendItem(VMPeripheralConfig.createDefaultVirtioSound().item);
                return;
            var config = new VMPeripheralConfig(DataItem.newObject());
            config.setType(type);
            appendItem(config.item);
