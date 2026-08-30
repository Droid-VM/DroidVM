import cn.classfun.droidvm.lib.data.HostAudioDevices;
    /**
     * Creates the sound card offered by default for a new VM: one playback and one capture
     * endpoint, both left to Android's current system routing.
     */
    @NonNull
    public static VMPeripheralConfig createDefaultVirtioSound() {
        var config = new VMPeripheralConfig(DataItem.newObject());
        config.setType(PeripheralType.VIRTIO_SOUND);
        var speaker = config.addEndpoint();
        speaker.setMode(SoundMode.SPEAKER);
        speaker.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
        var microphone = config.addEndpoint();
        microphone.setMode(SoundMode.MICROPHONE);
        microphone.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
        return config;
    }

