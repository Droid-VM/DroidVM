package cn.classfun.droidvm.ui.disk.lxc;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

public final class LxcImage {
    private String productId;
    private String distro;
    private String distroVersion;
    private String releaseTitle;
    private String arch;
    private String variant;
    private String aliases;
    private String buildSerial;
    private String downloadPath;
    private long size;
    private String sha256;

    @SuppressWarnings("unused")
    public String getProductId() {
        return productId;
    }

    @SuppressWarnings("unused")
    public String getDistro() {
        return distro;
    }

    @SuppressWarnings("unused")
    public String getDistroVersion() {
        return distroVersion;
    }

    @SuppressWarnings("unused")
    public String getReleaseTitle() {
        return releaseTitle;
    }

    @SuppressWarnings("unused")
    public String getArch() {
        return arch;
    }

    @SuppressWarnings("unused")
    public String getVariant() {
        return variant;
    }

    @SuppressWarnings("unused")
    public String getAliases() {
        return aliases;
    }

    @SuppressWarnings("unused")
    public String getBuildSerial() {
        return buildSerial;
    }

    @SuppressWarnings("unused")
    public String getDownloadPath() {
        return downloadPath;
    }

    @SuppressWarnings("unused")
    public long getSize() {
        return size;
    }

    @SuppressWarnings("unused")
    public String getSha256() {
        return sha256;
    }

    @SuppressWarnings("unused")
    public void setProductId(String val) {
        this.productId = val;
    }

    @SuppressWarnings("unused")
    public void setDistro(String val) {
        this.distro = val;
    }

    @SuppressWarnings("unused")
    public void setDistroVersion(String val) {
        this.distroVersion = val;
    }

    @SuppressWarnings("unused")
    public void setReleaseTitle(String val) {
        this.releaseTitle = val;
    }

    @SuppressWarnings("unused")
    public void setArch(String val) {
        this.arch = val;
    }

    @SuppressWarnings("unused")
    public void setVariant(String val) {
        this.variant = val;
    }

    @SuppressWarnings("unused")
    public void setAliases(String val) {
        this.aliases = val;
    }

    @SuppressWarnings("unused")
    public void setBuildSerial(String val) {
        this.buildSerial = val;
    }

    @SuppressWarnings("unused")
    public void setDownloadPath(String val) {
        this.downloadPath = val;
    }

    @SuppressWarnings("unused")
    public void setSize(long val) {
        this.size = val;
    }

    @SuppressWarnings("unused")
    public void setSha256(String val) {
        this.sha256 = val;
    }

    @NonNull
    public String getDisplayVersion() {
        if (releaseTitle != null && !releaseTitle.isEmpty() && !releaseTitle.equals(distroVersion))
            return fmt("%s (%s)", releaseTitle, distroVersion);
        return distroVersion;
    }

    @NonNull
    public String getDefaultFileName() {
        var name = fmt(
            "%s-%s-%s-%s-%s.qcow2", distro.toLowerCase(),
            distroVersion, variant, arch, buildSerial
        );
        name = name.replaceAll(":", "");
        return name;
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("%s %s (%s)", distro, distroVersion, variant);
    }
}
