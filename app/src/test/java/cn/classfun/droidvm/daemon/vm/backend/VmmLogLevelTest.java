// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VmmLogLevel;

/**
 * The {@code log_level} key and the top-level {@code --log-level} it becomes (defect D60).
 *
 * <p>Three things are pinned here, and the first is the one that cost a session: <b>where</b> the
 * flag lands. {@code --log-level} is a {@code CrosvmCmdlineArgs} option, so argh only accepts it
 * before the {@code run} subcommand -- after {@code run} it fails the whole parse and the VM goes
 * straight back to stopped, which is what {@code extra_options} did to it in
 * {@code logs/vpu_wp/B11-acceptance.md} section 8. So the assertions are about position and not
 * only about presence.</p>
 *
 * <p>The second is that a VM nobody touched is byte-for-byte the VM it was: no flag at all, and
 * crosvm's own {@code info}. The third is that a value that is not a filter is refused by the
 * parser rather than passed on -- env_logger never fails a parse, so a typo forwarded to crosvm
 * is a silently ignored directive and a dig that finds nothing.</p>
 */
public final class VmmLogLevelTest {
    private static DataItem vm() {
        return VMConfig.createWithCustomizeDefaults(null).item;
    }

    /** What {@code buildCommand} would put between the crosvm binary and {@code run}. */
    private static List<String> emit(DataItem item) {
        var args = new ArrayList<String>();
        CrosvmBackendInstance.appendLogLevel(args, item);
        return args;
    }

    // --- the default -----------------------------------------------------------------------

    @Test
    public void aVmThatNeverAskedForALevelGetsNoFlag() {
        var item = vm();
        assertNull("a fresh config must not carry the key",
            item.opt(VmmLogLevel.KEY, null));
        assertEquals(VmmLogLevel.DEFAULT, VmmLogLevel.get(item));
        assertNull(VmmLogLevel.filterFor(item));
        assertEquals(List.of(), emit(item));
    }

    @Test
    public void theDefaultSpeltOutIsStillNoFlag() {
        // "unset" and "info" are the same command line, not two spellings of it: crosvm's own
        // default is info (src/crosvm/cmdline.rs), so emitting it would only add a token.
        var item = vm();
        VmmLogLevel.set(item, "info");
        assertEquals("info", VmmLogLevel.get(item));
        assertNull(VmmLogLevel.filterFor(item));
        assertEquals(List.of(), emit(item));

        var upper = vm();
        VmmLogLevel.set(upper, "INFO");
        assertEquals(List.of(), emit(upper));
    }

    @Test
    public void aBlankValueIsTheDefault() {
        var item = vm();
        item.set(VmmLogLevel.KEY, "   ");
        assertEquals(VmmLogLevel.DEFAULT, VmmLogLevel.get(item));
        assertEquals(List.of(), emit(item));
    }

    // --- a level that was asked for --------------------------------------------------------

    @Test
    public void debugEmitsTheFlagAsTwoTokens() {
        var item = vm();
        VmmLogLevel.set(item, "debug");
        assertEquals(List.of("--log-level", "debug"), emit(item));
    }

    @Test
    public void aCompoundFilterSurvivesWhole() {
        // One argument, commas and all: env_logger parses the directives, the shell never sees it
        // (the daemon execs an argv, it does not build a command string).
        var item = vm();
        VmmLogLevel.set(item, "info,devices::virtio::media=debug");
        assertEquals(List.of("--log-level", "info,devices::virtio::media=debug"), emit(item));
    }

    @Test
    public void theStoredValueIsTrimmedButNotOtherwiseRewritten() {
        var item = vm();
        item.set(VmmLogLevel.KEY, "  Debug,disk=OFF  ");
        assertEquals(List.of("--log-level", "Debug,disk=OFF"), emit(item));
    }

    @Test
    public void theFlagIsTopLevelSoItPrecedesRun() {
        // The whole point of the key: a top-level option after `run` is `arg parsing failed:
        // Unrecognized argument: --log-level` and a VM that never starts (B11-acceptance section
        // 8). appendLogLevel is called between --extended-status and "run", so a caller that
        // appends the subcommand after it gets the order argh wants.
        var args = new ArrayList<String>();
        args.add("/path/to/crosvm");
        args.add("--extended-status");
        var item = vm();
        VmmLogLevel.set(item, "debug");
        CrosvmBackendInstance.appendLogLevel(args, item);
        args.add("run");
        assertEquals(
            List.of("/path/to/crosvm", "--extended-status", "--log-level", "debug", "run"), args);
        assertTrue("--log-level must come before the subcommand",
            args.indexOf("--log-level") < args.indexOf("run"));
    }

    // --- refusals ---------------------------------------------------------------------------

    @Test
    public void aValueThatIsNotAFilterIsRefusedByTheParser() {
        // A bare word must BE a level. env_logger would read "verbose" as a module named verbose
        // and log nothing new, which is the failure this catches: the dig would come back empty
        // with no line to say why.
        for (var bad : List.of(
            "verbose",              // not a level name
            "Debgu",                // a typo of one
            "debug,",               // a trailing comma
            "info,,debug",          // a doubled one
            "=debug",               // no target
            "media=verbose",        // a target with a level that is not one
            "media=",               // a target with no level
            "media debug",          // a space, which would be two argv tokens
            "debug /x",             // ditto, on the regex side
            "debug/",               // a '/' with no regex
            "$(id)",                // shell-shaped
            "--foo"                 // flag-shaped
        )) {
            assertFalse(bad, VmmLogLevel.isValid(bad));
            var e = assertThrows(bad, IllegalArgumentException.class, () -> VmmLogLevel.parse(bad));
            assertTrue("the refusal must name the key and the value: " + e.getMessage(),
                e.getMessage().contains(VmmLogLevel.KEY) && e.getMessage().contains(bad));
        }
    }

    @Test
    public void anOverlongValueIsRefused() {
        assertFalse(VmmLogLevel.isValid("debug," + "a".repeat(300) + "=off"));
    }

    @Test
    public void theSetterRefusesRatherThanStoringSomethingUnusable() {
        var item = vm();
        assertThrows(IllegalArgumentException.class, () -> VmmLogLevel.set(item, "verbose"));
        assertNull("nothing may be stored by a refused set",
            item.opt(VmmLogLevel.KEY, null));
    }

    @Test
    public void aBadStoredValueIsDroppedWithAWarningAndNeverReachesTheCommandLine() {
        // A hand-written vms.json or an old config can hold anything. filterFor throws so the
        // daemon can say which value it dropped; appendLogLevel catches, so a bad level is a VM
        // that boots at info, not a VM that does not boot.
        var item = vm();
        item.set(VmmLogLevel.KEY, "verbose");
        assertThrows(IllegalArgumentException.class, () -> VmmLogLevel.filterFor(item));
        assertEquals(List.of(), emit(item));
    }

    // --- the filters a dig actually uses -----------------------------------------------------

    @Test
    public void everyFilterTheRigDocumentsIsAccepted() {
        for (var good : List.of(
            "off", "error", "warn", "info", "debug", "trace", "TRACE",
            "debug,disk=off",
            "info,devices::virtio::media=debug",
            "info,devices::virtio::media::pool=trace,disk=off",
            "warn,crosvm=debug",
            "info,base=debug",
            "debug/media_host"
        )) {
            assertTrue(good, VmmLogLevel.isValid(good));
            assertEquals(good, VmmLogLevel.parse(good));
        }
    }
}
