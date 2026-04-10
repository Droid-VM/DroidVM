package cn.classfun.droidvm.ui.disk.images;

import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

import cn.classfun.droidvm.lib.data.Images;

public final class FlatImage {
    private static final DateFormat df = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM, DateFormat.SHORT
    );
    public final Images.ImageRepo repo;
    public final Images.ImageRepo.Image image;
    public final String searchPath;
    public final String displayFilename;
    public final String displayRepo;
    public final String displaySize;
    public final String displayDate;

    private FlatImage(
        @NonNull Images.ImageRepo repo,
        @NonNull Images.ImageRepo.Image image,
        @NonNull DateFormat dateFormat
    ) {
        this.repo = repo;
        this.image = image;
        var path = image.getPath();
        this.searchPath = path.toLowerCase(Locale.ROOT);
        var lastSlash = path.lastIndexOf('/');
        this.displayFilename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        this.displayRepo = repo.getName();
        this.displaySize = formatSize(image.getSize());
        this.displayDate = dateFormat.format(image.getModifiedDate());
    }

    public boolean matchesQuery(@NonNull String lowerQuery) {
        return searchPath.contains(lowerQuery);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static FlatImages load(Images images) {
        var f = new FlatImages();
        f.load(images);
        f.sort();
        return f;
    }

    public static class FlatImages extends ArrayList<FlatImage> {
        public FlatImages() {
        }

        void load(@NonNull Images images) {
            for (var repo : images.getRepos())
                load(repo.getImages());
        }

        void load(@NonNull List<Images.ImageRepo.Image> images) {
            for (var img : images)
                loadOne(img);
        }

        void loadOne(@NonNull Images.ImageRepo.Image image) {
            if (!image.isCompatible()) return;
            add(new FlatImage(image.getRepo(), image, df));
        }

        void sort() {
            ToLongFunction<FlatImage> cmp = fi -> fi.image.getModifiedTimestamp();
            sort(Comparator.comparingLong(cmp).reversed());
        }
    }
}
