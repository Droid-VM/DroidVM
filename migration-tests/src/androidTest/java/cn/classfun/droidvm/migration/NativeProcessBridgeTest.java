// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;

import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class NativeProcessBridgeTest {
    private static class Child implements Closeable {
        final int pid;
        final OutputStream stdin;
        final InputStream stdout, stderr;
        boolean reaped;
        Child(String[] argv, String[] env, String dir, int[] preserve, long[] limits) throws Exception {
            int[] result = NativeProcess.nativeForkExec(argv, env, dir, preserve, limits);
            assertNotNull(result);
            assertEquals(4, result.length);
            pid = result[0];
            assertTrue(pid > 0);
            stdin = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(result[1]));
            stdout = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(result[2]));
            stderr = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(result[3]));
        }
        int waitFor() { int result = NativeProcess.nativeWaitPid(pid); reaped = true; return result; }
        @Override public void close() {
            if (!reaped) { NativeProcess.nativeKill(pid, 9); waitFor(); }
            try { stdin.close(); } catch (Exception ignored) {}
            try { stdout.close(); } catch (Exception ignored) {}
            try { stderr.close(); } catch (Exception ignored) {}
        }
    }
    private static String text(InputStream stream) throws Exception {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    @Test public void preservesArgumentEnvironmentDirectoryLimitsAndExit() throws Exception {
        String directory = InstrumentationRegistry.getInstrumentation().getContext().getCacheDir().getCanonicalPath();
        String script = "printf '%s|%s|%s|%s|%s' \"$1\" \"$2\" \"$DROIDVM_TEST_VALUE\" \"$PWD\" \"$(ulimit -n)\"; printf error >&2; exit 23";
        try (Child child = new Child(new String[]{"/system/bin/sh", "-c", script, "fixture", "a b", "引用;$literal"},
                new String[]{"PATH=/system/bin", "DROIDVM_TEST_VALUE=literal $value"}, directory, new int[0], new long[]{7, 64, 64})) {
            child.stdin.close();
            assertEquals("a b|引用;$literal|literal $value|" + directory + "|64", text(child.stdout));
            assertEquals("error", text(child.stderr));
            assertEquals(23, child.waitFor());
        }
    }
    @Test public void pipesWaitAndSignalRemainCompatible() throws Exception {
        try (Child child = new Child(new String[]{"/system/bin/cat"}, null, null, new int[0], null)) {
            assertEquals(Integer.MIN_VALUE, NativeProcess.nativeTryWaitPid(child.pid));
            child.stdin.write("pipe 引用".getBytes(StandardCharsets.UTF_8));
            child.stdin.close();
            assertEquals("pipe 引用", text(child.stdout));
            assertEquals(0, child.waitFor());
        }
        try (Child child = new Child(new String[]{"/system/bin/cat"}, null, null, new int[0], null)) {
            NativeProcess.nativeKill(child.pid, 15);
            assertEquals(-15, child.waitFor());
        }
    }
    @Test public void preservesExplicitFdAndLegacySetupExitCodes() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor read = pipe[0]; ParcelFileDescriptor write = pipe[1]) {
            int fd = write.getFd();
            try (Child child = new Child(new String[]{"/system/bin/sh", "-c", "printf kept > /proc/self/fd/$1", "fixture", Integer.toString(fd)},
                    null, null, new int[]{fd}, null)) {
                assertEquals(0, child.waitFor());
            }
            write.close();
            assertEquals("kept", text(new ParcelFileDescriptor.AutoCloseInputStream(read)));
        }
        try (Child child = new Child(new String[]{"/droidvm-missing-executable"}, null, null, new int[0], null)) {
            assertEquals(127, child.waitFor());
        }
        try (Child child = new Child(new String[]{"/system/bin/true"}, null, "/droidvm-missing-directory", new int[0], null)) {
            assertEquals(127, child.waitFor());
        }
        try (Child child = new Child(new String[]{"/system/bin/true"}, null, null, new int[0], new long[]{7, 32, 16})) {
            assertEquals(126, child.waitFor());
        }
    }
}
