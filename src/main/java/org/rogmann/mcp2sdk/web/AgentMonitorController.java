package org.rogmann.mcp2sdk.web;

import org.rogmann.mcp2sdk.agent.AgentMonitor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Controller of the sub-agent monitor: one page, one polling endpoint. Same shape as
 * {@link ToolController} - server-rendered Thymeleaf page, no frontend build step.
 *
 * <p>The page itself is static once loaded; the browser polls
 * {@code GET /agents/api/agents} once a second and re-renders client-side. A snapshot never
 * contains more than bounded excerpts of what the children do, and {@link AgentMonitor} keeps it
 * in memory only - the page observes the server, the children do not see the page.</p>
 */
@Controller
@RequestMapping("/agents")
public class AgentMonitorController {

    /**
     * Shows the monitor page.
     */
    @GetMapping
    public String page() {
        return "agents";
    }

    /**
     * Snapshot of all known children (running and recently finished) for the polling renderer.
     */
    @GetMapping("/api/agents")
    @ResponseBody
    public List<AgentMonitor.AgentView> agents() {
        return AgentMonitor.getInstance().snapshot();
    }
}
