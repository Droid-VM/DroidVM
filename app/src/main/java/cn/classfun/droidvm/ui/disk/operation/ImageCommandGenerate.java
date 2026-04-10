package cn.classfun.droidvm.ui.disk.operation;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.ImageUtils.getImageInfo;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

public final class ImageCommandGenerate {
    private boolean useTempPath;
    private JSONObject task;
    private StringBuilder sb;
    private String diskPath;
    private String eDiskPath;
    private String realPath;
    private String tmpPath;
    private String outputPath;
    private final DiskStore diskStore;

    public ImageCommandGenerate(@NonNull DiskStore diskStore) {
        this.diskStore = diskStore;
    }

    public String getOutputPath() {
        return outputPath;
    }

    @NonNull
    public String buildCommand(@NonNull JSONObject task, String diskPath) throws JSONException {
        this.task = task;
        this.diskPath = diskPath;
        useTempPath = false;
        var qemuImg = getPrebuiltBinaryPath("qemu-img");
        sb = new StringBuilder();
        eDiskPath = escapedString(diskPath);
        tmpPath = null;
        realPath = null;
        outputPath = diskPath;
        var action = task.getString("action");
        sb.append("set -x; ");
        sb.append(fmt("mkdir -pv %s; ", escapedString(dirname(diskPath))));
        sb.append("if ");
        if (!task.getString("action").equals("clone"))
            sb.append(escapedString(qemuImg));
        appendAction();
        sb.append("; then ");
        if (useTempPath) {
            sb.append(fmt("rm -vf %s; ", realPath));
            sb.append(fmt("mv -v %s %s; ", tmpPath, realPath));
        } else if (!diskPath.equals(outputPath) && !action.equals("clone")) {
            sb.append(fmt("rm -vf %s; ", eDiskPath));
        }
        sb.append("echo done; sync; exit 0; else ");
        if (useTempPath)
            sb.append(fmt("rm -vf %s; ", tmpPath));
        else if (!diskPath.equals(outputPath))
            sb.append(fmt("rm -vf %s; ", realPath));
        sb.append("echo failed; sync; exit 1; fi");
        return sb.toString();
    }

    private void appendAction() throws JSONException {
        var action = task.getString("action");
        switch (action) {
            case "clone":
                appendClone();
                break;
            case "create":
                appendCreate();
                break;
            case "resize":
                appendResize();
                break;
            case "convert":
                appendConvert();
                break;
            default:
                throw new RuntimeException(fmt("Unknown action: %s", action));
        }
    }

    private void appendClone() throws JSONException {
        var pv = getPrebuiltBinaryPath("pv");
        sb.append(escapedString(pv));
        sb.append(" --sparse ");
        sb.append(eDiskPath);
        if (!task.has("output"))
            throw new IllegalArgumentException("Output path must be specified for clone action");
        outputPath = task.getString("output");
        sb.append(" --output ").append(escapedString(outputPath));
    }

    private void appendResize() throws JSONException {
        sb.append(" resize ");
        if (task.optBoolean("shrink", false))
            sb.append("--shrink ");
        sb.append(eDiskPath).append(" ").append(task.getString("size"));
    }

    private void appendConvert() throws JSONException {
        var info = getImageInfo(diskPath);
        sb.append(" convert --progress");
        String format;
        if (task.has("format")) {
            format = task.getString("format");
        } else if (info.has("format")) {
            format = info.getString("format");
            task.put("format", format);
        } else throw new RuntimeException("No format specified in task or image info");
        sb.append(" --target-format ").append(format);
        sb.append(" ").append(eDiskPath).append(" ");
        if (task.has("output")) {
            outputPath = task.getString("output");
        } else {
            useTempPath = true;
        }
        appendBacking();
        var opts = appendOptions();
        if (!opts.isEmpty())
            sb.append(" --target-format-options ").append(opts);
        if (!task.optString("compress", "none").equals("none"))
            sb.append(" --compress");
        sb.append(" ");
        realPath = escapedString(outputPath);
        tmpPath = escapedString(fmt("%s.tmp", diskPath));
        sb.append(useTempPath ? tmpPath : realPath);
    }

    @NonNull
    private String appendOptions() {
        var compress = task.optString("compress", "none");
        var opts = new StringBuilder();
        if (task.optBoolean("preallocate", false))
            opts.append("preallocation=falloc");
        if (!compress.equals("none")) {
            if (opts.length() > 0) opts.append(",");
            opts.append("compression_type=");
            switch (compress) {
                case "deflate":
                    opts.append("zlib");
                    break;
                case "zstd":
                    opts.append("zstd");
                    break;
                default:
                    opts.append(compress);
                    break;
            }
        }
        return opts.toString();
    }

    private void appendCreate() throws JSONException {
        sb.append(" create");
        sb.append(" --format ").append(task.getString("format"));
        appendBacking();
        var opts = appendOptions();
        if (!opts.isEmpty())
            sb.append(" --options ").append(opts);
        sb.append(" ").append(escapedString(diskPath));
        sb.append(" ").append(task.getString("size"));
    }

    private void appendBacking() throws JSONException {
        String backingPath = null;
        if (task.has("backing_id")) {
            var backingId = task.getString("backing_id");
            var backingDisk = diskStore.findById(UUID.fromString(backingId));
            if (backingDisk == null)
                throw new RuntimeException(fmt("Backing disk not found: %s", backingId));
            backingPath = backingDisk.getFullPath();
        } else if (task.has("backing_path"))
            backingPath = task.getString("backing_path");
        if (backingPath == null) return;
        if (!backingPath.startsWith("/"))
            throw new RuntimeException("Backing path must be absolute");
        if (!task.getString("format").equalsIgnoreCase("qcow2"))
            throw new RuntimeException("Only qcow2 format supports backing files");
        var backingFormat = DiskFormat.fromFilename(backingPath).name().toLowerCase();
        var backingInfo = getImageInfo(backingPath);
        if (!backingInfo.has("format"))
            backingFormat = backingInfo.getString("format");
        sb.append(" --backing ").append(escapedString(backingPath));
        sb.append(" --backing-format ").append(backingFormat);
    }
}
