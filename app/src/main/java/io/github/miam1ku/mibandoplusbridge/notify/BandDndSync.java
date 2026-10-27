// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

/**
 * The band's own 同步手机勿扰 switch, tracked for one link.
 *
 * <p>Enabling it is what subtype 15 with enabled 1 does, so the phone must know where the band
 * stands before it pushes the phone rule: the user turns the switch off on the band and the next
 * link used to turn it back on. The band stores the value, so a link answers once and the stored
 * copy covers a band that stays quiet.</p>
 *
 * <p>A silent band is not a switch-off: a firmware that ignores the query keeps whatever value
 * the last link stored.</p>
 */
public final class BandDndSync {
    /** What the service may do with the phone's DND right now. */
    public enum Next { ASK, MIRROR, HOLD }

    private boolean on = true;
    private boolean asked;
    private boolean answered;

    /** Stored switch value from the previous link. Old installs have none, which reads as on. */
    public void seed(boolean enabled) {
        on = enabled;
    }

    public boolean on() {
        return on;
    }

    /** A new link starts without an answer and asks again. */
    public void newLink() {
        asked = false;
        answered = false;
    }

    /** True while the link still has to hear from the band before it mirrors anything. */
    public boolean waiting() {
        return asked && !answered;
    }

    /** One phone-side DND opportunity: the first one in a link asks instead of pushing. */
    public Next before() {
        if (!asked) {
            asked = true;
            answered = false;
            return Next.ASK;
        }
        return on ? Next.MIRROR : Next.HOLD;
    }

    /**
     * The band reported its switch, or {@code null} when the ask went unanswered.
     *
     * @return true when the mirror should run now
     */
    public boolean answered(Boolean enabled) {
        boolean first = !answered;
        answered = true;
        if (enabled == null) return on && first;
        boolean changed = on != enabled;
        on = enabled;
        return on && (first || changed);
    }
}
