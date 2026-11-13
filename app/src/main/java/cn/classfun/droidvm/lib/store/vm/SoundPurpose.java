// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

/**
 * What a stream is for, as distinct from which endpoint it is on.
 *
 * <p>Android decides two things from this that the endpoint alone does not settle: which
 * microphone it picks when none was named, and what processing it applies to one that was --
 * echo cancellation and noise suppression are properties of the purpose, not of the microphone.
 * On the output side it decides which volume stream the audio belongs to and how it ducks
 * against other audio.</p>
 *
 * <p>These are AAudio's own values, named as AAudio names them. The two system presets
 * (SYSTEM_HOTWORD, SYSTEM_ECHO_REFERENCE) are deliberately absent: they exist, but need
 * privileges an ordinary application does not have, so offering them would only produce a
 * stream that fails to open.</p>
 */
public final class SoundPurpose {
    private SoundPurpose() {
    }

    /** One selectable value of one attribute. */
    public static final class Choice {
        /** As it appears in the stored key; matches AAudio's own name, lowercased. */
        public final String value;
        @StringRes
        public final int titleId;

        Choice(@NonNull String value, @StringRes int titleId) {
            this.value = value;
            this.titleId = titleId;
        }
    }

    /** Attribute name in the stored key: {@code TYPE|address#usage=media,content=music}. */
    public static final String ATTR_USAGE = "usage";
    public static final String ATTR_CONTENT = "content";
    public static final String ATTR_PRESET = "preset";
    /**
     * How the host trades latency against power for the stream (AAudio's performance mode). Left
     * unset, the backend saves power: on a phone the platform's own default puts an output on the
     * primary mixer, which wakes every few milliseconds, and a guest playing only silence kept
     * the host's audio stack at about a core that way. Low latency is for what needs the fast
     * path, a game's effects or monitoring a microphone live.
     */
    public static final String ATTR_PERFORMANCE = "performance";

    private static final Choice[] USAGE = {
        new Choice("media", R.string.edit_vm_sound_usage_media),
        new Choice("voice_communication", R.string.edit_vm_sound_usage_voice_communication),
        new Choice("voice_communication_signalling",
            R.string.edit_vm_sound_usage_voice_communication_signalling),
        new Choice("game", R.string.edit_vm_sound_usage_game),
        new Choice("alarm", R.string.edit_vm_sound_usage_alarm),
        new Choice("notification", R.string.edit_vm_sound_usage_notification),
        new Choice("notification_ringtone", R.string.edit_vm_sound_usage_notification_ringtone),
        new Choice("notification_event", R.string.edit_vm_sound_usage_notification_event),
        new Choice("assistant", R.string.edit_vm_sound_usage_assistant),
        new Choice("assistance_accessibility",
            R.string.edit_vm_sound_usage_assistance_accessibility),
        new Choice("assistance_navigation_guidance",
            R.string.edit_vm_sound_usage_assistance_navigation_guidance),
        new Choice("assistance_sonification",
            R.string.edit_vm_sound_usage_assistance_sonification),
    };

    private static final Choice[] CONTENT = {
        new Choice("speech", R.string.edit_vm_sound_content_speech),
        new Choice("music", R.string.edit_vm_sound_content_music),
        new Choice("movie", R.string.edit_vm_sound_content_movie),
        new Choice("sonification", R.string.edit_vm_sound_content_sonification),
    };

    private static final Choice[] PRESET = {
        new Choice("generic", R.string.edit_vm_sound_preset_generic),
        new Choice("camcorder", R.string.edit_vm_sound_preset_camcorder),
        new Choice("voice_recognition", R.string.edit_vm_sound_preset_voice_recognition),
        new Choice("voice_communication", R.string.edit_vm_sound_preset_voice_communication),
        new Choice("unprocessed", R.string.edit_vm_sound_preset_unprocessed),
        new Choice("voice_performance", R.string.edit_vm_sound_preset_voice_performance),
    };

    private static final Choice[] PERFORMANCE = {
        new Choice("power_saving", R.string.edit_vm_sound_performance_power_saving),
        new Choice("none", R.string.edit_vm_sound_performance_none),
        new Choice("low_latency", R.string.edit_vm_sound_performance_low_latency),
    };

    /** The attributes offered for one direction, in the order they are asked about. */
    @NonNull
    public static List<String> attributesFor(boolean input) {
        var out = new ArrayList<String>();
        if (input) {
            out.add(ATTR_PRESET);
        } else {
            out.add(ATTR_USAGE);
            out.add(ATTR_CONTENT);
        }
        out.add(ATTR_PERFORMANCE);
        return out;
    }

    @NonNull
    public static Choice[] choicesFor(@NonNull String attribute) {
        switch (attribute) {
            case ATTR_USAGE: return USAGE;
            case ATTR_CONTENT: return CONTENT;
            case ATTR_PRESET: return PRESET;
            case ATTR_PERFORMANCE: return PERFORMANCE;
            default: return new Choice[0];
        }
    }

    @StringRes
    public static int titleFor(@NonNull String attribute) {
        switch (attribute) {
            case ATTR_USAGE: return R.string.edit_vm_sound_purpose_usage;
            case ATTR_CONTENT: return R.string.edit_vm_sound_purpose_content;
            case ATTR_PERFORMANCE: return R.string.edit_vm_sound_purpose_performance;
            default: return R.string.edit_vm_sound_purpose_preset;
        }
    }

    /**
     * What leaving the attribute unset means. For most it is the platform's default; for the
     * performance mode it is the backend's, which saves power, so it is named as such rather than
     * as a default the user would take to be the platform's.
     */
    @StringRes
    public static int unsetTitleFor(@NonNull String attribute) {
        return ATTR_PERFORMANCE.equals(attribute)
            ? R.string.edit_vm_sound_performance_default
            : R.string.edit_vm_sound_purpose_unset;
    }

    /** The label for a stored value, or null when it is not one this build offers. */
    @Nullable
    public static Choice find(@NonNull String attribute, @NonNull String value) {
        for (var choice : choicesFor(attribute)) {
            if (choice.value.equals(value)) return choice;
        }
        return null;
    }
}
