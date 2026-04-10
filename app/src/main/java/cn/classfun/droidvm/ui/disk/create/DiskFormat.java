package cn.classfun.droidvm.ui.disk.create;

import static cn.classfun.droidvm.lib.utils.StringUtils.extensionLower;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum DiskFormat implements StringEnum {
    QCOW2("qcow2"),
    RAW("img"),
    VHDX("vhdx"),
    VDI("vdi"),
    VMDK("vmdk"),
    ISO("iso");

    private final String ext;

    DiskFormat(String ext) {
        this.ext = ext;
    }

    @NonNull
    public String getExt() {
        return ext;
    }

    public static DiskFormat fromExt(String ext) {
        for (var fmt : values())
            if (fmt.ext.equalsIgnoreCase(ext))
                return fmt;
        return RAW;
    }

    public static DiskFormat fromFilename(String filename) {
        return fromExt(extensionLower(filename));
    }
}
