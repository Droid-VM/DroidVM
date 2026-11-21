// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.pkg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

import cn.classfun.droidvm.lib.pkg.PackageConstants;

/**
 * An imported disk is written sparsely: what lands on the phone is byte for byte the packed
 * image, its length included when it ends in zeros, but the runs of zeros are skipped rather
 * than written. Asserted on contents and length; whether the skipped runs become holes is the
 * filesystem's business.
 */
public final class SparseImportTest {
    /** Hands out at most a few bytes per read, as a decompressor's stream may. */
    private static InputStream trickle(byte[] data) {
        return new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1000));
            }
        };
    }

    private static void roundTrip(byte[] image) throws IOException {
        var out = File.createTempFile("sparse", ".img");
        try {
            long[] last = {-1};
            VMImportTask.copySparse(trickle(image), out, image.length, done -> last[0] = done);
            assertEquals(image.length, out.length());
            assertArrayEquals(image, Files.readAllBytes(out.toPath()));
            assertEquals(image.length, last[0]);
        } finally {
            assertTrue(out.delete());
        }
    }

    @Test
    public void dataStoreShapedImage() throws IOException {
        // A label in block 0, then nothing: the shape of a fresh Data store.
        var image = new byte[PackageConstants.BUFFER * 5 + 123];
        System.arraycopy("DroidVMDataStore".getBytes(), 0, image, 0, 16);
        image[16] = 1;
        roundTrip(image);
    }

    @Test
    public void dataBetweenHoles() throws IOException {
        var image = new byte[PackageConstants.BUFFER * 7];
        Arrays.fill(image, PackageConstants.BUFFER * 2 + 5, PackageConstants.BUFFER * 3 + 9, (byte) 0x5a);
        image[image.length - 1] = 7;
        roundTrip(image);
    }

    @Test
    public void allZeros() throws IOException {
        roundTrip(new byte[PackageConstants.BUFFER * 3 + 1]);
    }

    @Test(expected = IOException.class)
    public void shortEntryIsRefused() throws IOException {
        var out = File.createTempFile("sparse", ".img");
        try {
            VMImportTask.copySparse(new ByteArrayInputStream(new byte[10]), out, 20, done -> { });
        } finally {
            out.delete();
        }
    }
}
