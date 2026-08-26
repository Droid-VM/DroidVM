package cn.classfun.droidvm.ui.vm.edit.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.List;

import cn.classfun.droidvm.ui.vm.edit.graphics.ScreenResolutionOptions.Option;

/**
 * The resolution dropdown's list: what it offers on a given panel, and in what order.
 *
 * <p>The rules are all here rather than in the row because they are the half of that dropdown that
 * does not need a device -- and because the two that depend on the panel land in a different place
 * on every device, which is the sort of thing a test says once and a screenshot never settles.</p>
 */
public class ScreenResolutionOptionsTest {
    @Test
    public void ordersEverythingByArea() {
        // 1080x2400 portrait panel: half of it (1200x540) is smaller than either fixed size, and
        // the panel itself is larger than both -- so the two device sizes bracket the fixed ones.
        assertSizes(ScreenResolutionOptions.build(1080, 2400),
            new int[][]{{1200, 540}, {1280, 720}, {1920, 1080}, {2400, 1080}});
    }

    @Test
    public void everySizeIsLandscape() {
        for (var panel : new int[][]{{1080, 2400}, {2400, 1080}, {1440, 3200}, {800, 1280}})
            for (var opt : ScreenResolutionOptions.build(panel[0], panel[1]))
                assertTrue(opt.width + "x" + opt.height, opt.width >= opt.height);
    }

    @Test
    public void readsThePanelTheSameInEitherRotation() {
        assertSizes(ScreenResolutionOptions.build(2400, 1080),
            new int[][]{{1200, 540}, {1280, 720}, {1920, 1080}, {2400, 1080}});
    }

    @Test
    public void withoutAPanelOnlyTheFixedSizesAreOffered() {
        assertSizes(ScreenResolutionOptions.build(0, 0), new int[][]{{1280, 720}, {1920, 1080}});
        assertSizes(ScreenResolutionOptions.build(-1, 2400), new int[][]{{1280, 720}, {1920, 1080}});
    }

    @Test
    public void aSizeTwoRulesProduceIsListedOnce() {
        // A 1080p panel: "1920x1080" and "the phone's own" are the same size.
        assertSizes(ScreenResolutionOptions.build(1920, 1080),
            new int[][]{{960, 540}, {1280, 720}, {1920, 1080}});

        // A 1440p panel halves to exactly 1280x720, which the fixed list already had.
        assertSizes(ScreenResolutionOptions.build(1440, 2560),
            new int[][]{{1280, 720}, {1920, 1080}, {2560, 1440}});
    }

    @Test
    public void halvingAnOddPanelRoundsDownToEven() {
        var opts = ScreenResolutionOptions.build(1179, 2556);
        assertEquals(1278, opts.get(0).width);
        assertEquals(588, opts.get(0).height);
    }

    @Test
    public void aHalfBelowTheValidatorsFloorIsNotOffered() {
        // 854x480 halves to 427x240, which the editor would refuse to save. The panel itself still
        // stands: it is over the floor.
        assertSizes(ScreenResolutionOptions.build(480, 854),
            new int[][]{{854, 480}, {1280, 720}, {1920, 1080}});
    }

    private static void assertSizes(@NonNull List<Option> opts, @NonNull int[][] expected) {
        var sb = new StringBuilder();
        for (var opt : opts) sb.append(opt.width).append('x').append(opt.height).append(' ');
        var want = new StringBuilder();
        for (var e : expected) want.append(e[0]).append('x').append(e[1]).append(' ');
        assertEquals(want.toString(), sb.toString());
    }
}
