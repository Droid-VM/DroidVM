package cn.classfun.droidvm.ui.main.disk;

final class ImageInfo {
    final long virtualSize;
    final long actualSize;

    ImageInfo(long virtualSize, long actualSize) {
        this.virtualSize = virtualSize;
        this.actualSize = actualSize;
    }
}
