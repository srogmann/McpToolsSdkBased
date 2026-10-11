package org.rogmann.mcp2sdk.pdf.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fourth sink: which font drew how often — the count behind {@code pdf.fonts()}'s
 * {@code pages} and {@code showTextOps}, and behind the difference between "this dictionary sits in
 * the resources" and "this dictionary is why the page looks the way it does".
 *
 * <p>
 * Before D4 this was {@code PdfFont.textUsage()}'s own loop over the tokens, keeping the last
 * {@code Tf}'s name in a variable. The rule it implements did not change and is not a matter of
 * opinion: {@code Tf} "should be specified explicitly by using Tf before any text is shown"
 * (Table 5.2, S. 398 — there is no initial font), so a showing operator counts for the font the walk
 * carries at that operator and for no font at all when none was set.
 * </p>
 *
 * <p>
 * What the walk additionally knows is why this is a sink now and not a second opinion: a {@code Q}
 * takes a {@code Tf} back (the text state is part of the graphics state, Table 4.2, S. 211), a
 * {@code Tf} whose operands do not fit its registry row sets nothing, and a {@code BT} does not
 * forget the font. The loop could not know any of that — it only ever looked at the tokens to the
 * left of the operator.
 * </p>
 *
 * <p>
 * Counting is per resource name, in the first-seen order of the stream, because that is the shape
 * {@code PdfFont.listing} merges pages and totals from.
 * </p>
 */
public final class UseSink implements PdfContentSink {

    private final Map<String, Long> usage = new LinkedHashMap<>();
    private long shownWithoutFont;

    @Override
    public void onOperation(PdfContentInterpreter.Operation operation) {
        if (!operation.showsText()) {
            return;
        }
        String font = operation.fontResourceName();
        if (font == null) {
            // A show before any Tf: nothing to credit, and worth counting because a page that does
            // this has text no font listing will ever mention.
            shownWithoutFont++;
            return;
        }
        usage.merge(font, 1L, Long::sum);
    }

    /** Resource name (with slash) → showing operators, in the order the stream first used each font. */
    public Map<String, Long> usage() {
        return Collections.unmodifiableMap(usage);
    }

    /** Showing operators that ran while no font was in effect. */
    public long shownWithoutFont() {
        return shownWithoutFont;
    }

    /** How many fonts drew at all. */
    public int fontsUsed() {
        return usage.size();
    }

    @Override
    public String toString() {
        return usage.size() + " font(s) used, " + usage.values().stream().mapToLong(Long::longValue)
                .sum() + " showing operator(s)";
    }
}
