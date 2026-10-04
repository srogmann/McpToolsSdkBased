/**
 * Operating one internal web application jointly, by user and by LLM.
 *
 * <p><b>The user's browser is the renderer.</b> This package does not build a second one: it sits as a
 * reverse proxy between Firefox and the application, rewrites URLs so that the browser renders
 * everything as usual, and injects a script ({@code /browserctl/__bridge.js}) that shows the server the
 * DOM the user sees and takes commands from it. The proxy is therefore not only the channel but also
 * the <em>injection point</em>: script access is a property of the response, not of the client - which
 * is why no browser plugin, no hosts file and no {@code about:config} is needed.</p>
 *
 * <p>Building blocks: {@link org.rogmann.mcp2sdk.browser.BrowserApps} (what may pass?),
 * {@link org.rogmann.mcp2sdk.browser.BrowserRewriter} (URL rewriting, bridge injection),
 * {@link org.rogmann.mcp2sdk.browser.BrowserProxyClient} (forwarding),
 * {@link org.rogmann.mcp2sdk.browser.BrowserSession} (frames, revisions, long-poll channel, capture),
 * {@link org.rogmann.mcp2sdk.browser.BrowserTool} (the LLM's view of a session),
 * {@link org.rogmann.mcp2sdk.browser.BrowserProxyController} (HTTP endpoints).</p>
 *
 * <p><b>As of M1 the write path is behind the switch</b> ({@code browser.write.enabled}, default off):
 * {@link org.rogmann.mcp2sdk.browser.BrowserTool} fills fields and clicks via the bridge (undo
 * journal, readback); saving runs as {@code browser.submit} through the four-eyes review
 * ({@link org.rogmann.mcp2sdk.review.ReviewEntry}) - or without it for applications listed in
 * {@code browser.apps.withoutReviews} (development bypass). The decisions, the limits and the reasons
 * behind them are in
 * <a href="../../../../../../../docs/browser.md">docs/browser.md</a>.</p>
 *
 * <p><b>M2 knows where a value comes from - and says nothing about what is allowed.</b> With
 * {@code browser.fields.mode=observe|strict} the bridge reads the writable fields before and after an
 * operation, and {@link org.rogmann.mcp2sdk.browser.BrowserFieldModel} derives per field whether the
 * application computes it, offers it, enforces a rule on it or receives it from a hand. The mode is off
 * by default: a session then runs exactly as M1 built it, spontaneous exploration included. Only
 * {@code strict} holds a write back, only for a field that was <em>seen</em> changing on its own, and
 * only until the caller asks for it by name ({@code force:true}) - the gate for what the model may do
 * stays the review (decision L11). Values never enter the model, digests do (L12).</p>
 *
 * <p><b>M2b adds the half the DOM does not show: the payload.</b> A page sends names it never displays
 * (hidden inputs are skipped in a snapshot on purpose) and displays values it never sends (a result box
 * the application fills in). {@link org.rogmann.mcp2sdk.browser.BrowserPayload} therefore reads the
 * <em>redacted</em> request bodies the capture already holds and joins them to the field rows: names
 * become fields of their own under {@code sent:}, a field no payload of the application ever carried
 * counts as computed, and the one claim that rests on absence is switched off wherever absence is not
 * provable - a body excluded by {@code sensitivePaths}, a name that sounds like a credential, a box the
 * browser only sends when it is ticked (§7a-P of the document).</p>
 *
 * <p><b>Where it runs.</b> Development and test rather than productive operation: the arrangement is
 * made in order to look at an internal application's DOM while working on the tools around it. What the
 * package itself writes is test state - {@code runs/browser/<session>/*.jsonl}, the field models under
 * {@code runs/browser/fields/} and, for the built-in target application
 * {@link org.rogmann.mcp2sdk.browser.BrowserDemoApp}, its report file - all of it anchored in the project
 * base ({@code IDE_PROJECT_DIR}), never in the working directory (decision L10). Which promises still
 * hold when it is used otherwise are answered by §2b of the document, which do not by §8.</p>
 *
 * <p><b>More than one application: give them names, not only ports.</b> RFC 6265 chooses cookies by host
 * and path and the port plays no part, while {@code Cookie} has to reach the upstream untouched for any
 * sign-in to work (L3). Every proxied application under {@code localhost} therefore shares one cookie
 * jar, and one {@code Strict-Transport-Security} response is worth as much.
 * {@code browser.host.suffix=.localhost} names them apart (decision L13) - and the name only
 * <em>offers</em> an application, it never selects an upstream: that stays the registry, which is what
 * keeps the registry the boundary between us and foreign (L2). See {@link BrowserHost}.</p>
 *
 * <p><b>Trust model.</b> Only internal applications listed in {@code browser-apps.json} are accepted;
 * everything else is answered with {@code 403}. That also accepts that the proxy sees the user's
 * credentials in flight - it does <em>not keep</em> them: cookies stay in the browser (decision L3),
 * secrets are redacted on the way into the capture
 * ({@link org.rogmann.mcp2sdk.browser.BrowserSecrets}, decision L4) and login fields are only located
 * by the bridge, never read out. What this model does <em>not</em> cover is prompt injection from the
 * page content: with internal applications we deliberately do not treat that as a threat, but we do
 * treat the way out as one - the capture ends up in the context of the model endpoint.</p>
 */
package org.rogmann.mcp2sdk.browser;
