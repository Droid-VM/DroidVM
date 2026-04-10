package cn.classfun.droidvm.lib.store.disk;

import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

public final class DiskConfig extends DataConfig {

    public DiskConfig() {
        setId(UUID.randomUUID());
    }

    public DiskConfig(@NonNull JSONObject jo) throws JSONException {
        item.set(jo);
    }

    @NonNull
    public String getFullPath() {
        return pathJoin(item.optString("folder", ""), getName());
    }

    @NonNull
    public DiskFormat getFormat() {
        return DiskFormat.fromFilename(getName());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsPreallocate(@NonNull DiskFormat fmt) {
        switch (fmt) {
            case RAW:
            case QCOW2:
            case VHDX:
            case VDI:
            case VMDK:
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsExtraOperations(@NonNull DiskFormat fmt) {
        switch (fmt) {
            case RAW:
            case QCOW2:
            case VHDX:
            case VDI:
            case VMDK:
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsCreate(@NonNull DiskFormat fmt) {
        switch (fmt) {
            case RAW:
            case QCOW2:
            case VHDX:
            case VDI:
            case VMDK:
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsCompress(@NonNull DiskFormat fmt) {
        return fmt == DiskFormat.QCOW2;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsSnapshot(@NonNull DiskFormat fmt) {
        return fmt == DiskFormat.QCOW2;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsBackingImage(@NonNull DiskFormat fmt) {
        return fmt == DiskFormat.QCOW2;
    }
}
