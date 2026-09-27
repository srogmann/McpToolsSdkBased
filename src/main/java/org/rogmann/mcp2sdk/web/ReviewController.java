package org.rogmann.mcp2sdk.web;

import org.rogmann.mcp2sdk.review.ReviewDecision;
import org.rogmann.mcp2sdk.review.ReviewEntry;
import org.rogmann.mcp2sdk.review.ReviewRequests;
import org.rogmann.mcp2sdk.review.ReviewStatus;
import org.rogmann.mcp2sdk.review.ReviewStore;
import org.rogmann.mcp2sdk.review.ReviewView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller of the shared four-eyes review UI: the requests of <b>every</b> tool that asks for an
 * approval, in one list, decided on one page.
 * <p>
 * The controller holds no tool knowledge and no review state: it reads the entries of
 * {@link ReviewStore} (rendered by {@link ReviewView}, so every tool is shown in the same frame)
 * and hands a decision to the request's own {@link org.rogmann.mcp2sdk.review.DecideConsumer} -
 * the way back into whichever tool asked. A tool that was never called contributes nothing to the
 * list; there is no registration and no catalogue.
 * </p>
 * <p>
 * The decision path follows the interface contract: it does not throw, and a decision that arrives
 * too late is reported as a status. The reviewer gets the same non-destructive flash message the
 * Python workflow produced ("not pending anymore"), never a stack trace.
 * </p>
 */
@Controller
@RequestMapping("/review")
public class ReviewController {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewStore store;

    /**
     * Constructor.
     *
     * @param store the shared request list (provided as a bean by {@code McpConfig})
     */
    public ReviewController(ReviewStore store) {
        this.store = store;
    }

    /**
     * Displays the list of requests: every open one, plus the newest finished ones per tool
     * (the cap is {@link ReviewView#MAX_HISTORY_PER_TOOL} - nothing is deleted, only not shown).
     */
    @GetMapping
    public String list(Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReviewEntry entry : ReviewView.visibleEntries(store.entries())) {
            rows.add(ReviewView.row(entry));
        }
        boolean hasPending = rows.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("pending")));
        model.addAttribute("entries", rows);
        model.addAttribute("hasPending", hasPending);
        return "review";
    }

    /**
     * Displays the detail page of one request: the facts the tool declared, and - once it reported
     * back - what became of the approval.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model,
            RedirectAttributes redirectAttributes) {
        ReviewEntry entry = store.get(id);
        if (entry == null) {
            redirectAttributes.addFlashAttribute("error", unknownRequest(id));
            return "redirect:/review";
        }
        model.addAttribute("entry", ReviewView.detail(entry));
        return "review-detail";
    }

    /**
     * Records the decision (approve or deny) and redirects to the detail page.
     * <p>
     * The decision goes to the request's consumer; the returned status is what the request actually
     * has now. If that is not the requested outcome, someone else decided first (or the request
     * expired) - the reviewer is told so instead of receiving an error page.
     * </p>
     */
    @PostMapping("/{id}/decide")
    public String decide(@PathVariable String id,
            @RequestParam String action,
            @RequestParam(required = false) String comment,
            RedirectAttributes redirectAttributes) {

        boolean approved = "approve".equals(action);
        if (!approved && !"deny".equals(action)) {
            redirectAttributes.addFlashAttribute("error", "Unknown action: " + action);
            return "redirect:/review/" + id;
        }
        ReviewEntry entry = store.get(id);
        if (entry == null) {
            redirectAttributes.addFlashAttribute("error", unknownRequest(id));
            return "redirect:/review";
        }
        if (!entry.pending()) {
            redirectAttributes.addFlashAttribute("error", notPending(id, entry.status()));
            return "redirect:/review/" + id;
        }
        ReviewStatus requested = approved ? ReviewStatus.APPROVED : ReviewStatus.DENIED;
        ReviewStatus actual = entry.decideConsumer().onDecide(
                new ReviewDecision(id, approved, comment, ReviewRequests.now()));
        if (actual != requested) {
            LOG.warn("Decision for review request {} did not take effect: requested {}, now {}",
                    id, requested.display(), actual.display());
            redirectAttributes.addFlashAttribute("error", notPending(id, actual));
        } else {
            LOG.info("Review request {}: {}", id, actual.display());
        }
        return "redirect:/review/" + id;
    }

    private static String notPending(String id, ReviewStatus status) {
        return "Request " + id + " is not pending anymore (status: " + status.display() + ").";
    }

    private static String unknownRequest(String id) {
        return "Unknown review request: " + id
                + " (requests live in memory and are cleared by a server restart).";
    }
}
