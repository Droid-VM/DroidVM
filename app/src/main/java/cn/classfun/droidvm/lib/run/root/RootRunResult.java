// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.run.root;

import com.topjohnwu.superuser.Shell;

import cn.classfun.droidvm.lib.run.RunResult;

public final class RootRunResult extends RunResult {
    private final Shell.Result result;

    RootRunResult(Shell.Result result) {
        this.result = result;
    }

    @Override
    public int getCode() {
        return result.getCode();
    }

    @Override
    public Iterable<String> getOut() {
        return result.getOut();
    }

    @Override
    public Iterable<String> getErr() {
        return result.getErr();
    }
}
