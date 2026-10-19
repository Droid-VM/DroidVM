// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.base;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rescue payloads shared with the daemon; credentials are sent through the console only. */
public final class AgentScripts {
    public static final String PREAMBLE = read("preamble.sh");
    public static final String CHPASSWD = read("chpasswd.sh");
    public static final String AUTOGROW = read("autogrow.sh");
    public static final String EPILOGUE = read("epilogue.sh");

    private AgentScripts() {}

    private static String read(String name) {
        var path = "/droidvm-agent/" + name;
        try (var input = AgentScripts.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing rescue script: " + path);
            var output = new ByteArrayOutputStream();
            var buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read rescue script: " + path, error);
        }
    }
}
