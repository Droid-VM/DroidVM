// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import androidx.annotation.NonNull;

/**
 * What the three lines of a rule card say, and in which order, which is the zone's business
 * alone.
 *
 * <p>The ordering is not decoration. A rule is stored as its matcher and nothing else, so the
 * matcher is what the card has to lead with -- and which field that is differs per zone: the
 * port zone matches on a port and the device zone on an identifier, and the same string in the
 * same place would otherwise mean two different things on two cards a finger apart. The lines
 * below it describe the device the matcher resolves to right now, which is context and not the
 * rule: it changes under a replug while the rule does not, so it is never editable and never
 * bold.</p>
 *
 * <p>Free of {@code android.*} and of the strings themselves: this says which slot goes where
 * and the adapter says it in the user's language, which is what lets the ordering -- the part
 * that is easy to get subtly wrong and impossible to see in a screenshot -- be a unit test.</p>
 */
final class UsbRuleLines {
    /** How many lines a card has room for; a zone that needs fewer leaves the rest empty. */
    static final int LINES = 3;

    /**
     * What one line of a card carries. Every line opens the same dialog -- the rule is one
     * thought and is edited as one -- so a slot says what is written on the line and how, and
     * nothing about what a tap on it does.
     */
    enum Slot {
        /** The identifier the rule matches on. */
        MATCH_ID(true),
        /** The port the rule matches on. */
        MATCH_PORT(true),
        /** "every device": the catch-all zone's whole matcher. */
        MATCH_ANY(true),
        /** The identifier of whatever the matcher resolves to now. Context, not the rule. */
        INFO_ID(false),
        /** The port whatever the matcher resolves to is in now. Context, not the rule. */
        INFO_PORT(false),
        /** What that device calls itself. Never matched on: a rename is the same device. */
        NAME(false),
        /** No line here. */
        NONE(false);

        /** Whether the line is drawn in the management page's title style. */
        final boolean strong;

        Slot(boolean strong) {
            this.strong = strong;
        }
    }

    private UsbRuleLines() {
    }

    /**
     * The three slots of [layer], top to bottom.
     *
     * <p>The exact zone is the one with two matchers and so the one with no room for context:
     * both of its lines are the rule, and the third is the name. The other two zones lead with
     * their matcher and follow it with the field they do NOT match on, which is the one thing a
     * reader cannot work out from the card -- a port rule's card is about whatever is in that
     * socket, and saying which device that is now is the difference between a rule someone can
     * check and one they have to trust.</p>
     */
    @NonNull
    static Slot[] of(@NonNull UsbRuleLayer layer) {
        switch (layer) {
            case EXACT:
                return new Slot[]{Slot.MATCH_ID, Slot.MATCH_PORT, Slot.NAME};
            case PORT:
                return new Slot[]{Slot.MATCH_PORT, Slot.INFO_ID, Slot.NAME};
            case DEVICE:
                return new Slot[]{Slot.MATCH_ID, Slot.INFO_PORT, Slot.NAME};
            case ANY:
            default:
                // Nothing is matched on and nothing is resolved, so there is nothing to say
                // twice. One line, and the card is shorter than the others by design.
                return new Slot[]{Slot.MATCH_ANY, Slot.NONE, Slot.NONE};
        }
    }
}
