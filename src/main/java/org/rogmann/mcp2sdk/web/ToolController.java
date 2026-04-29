package org.rogmann.mcp2sdk.web;

import org.rogmann.mcp2sdk.ToolRegistry;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for the Tool Management Web UI.
 */
@Controller
@RequestMapping("/tools")
public class ToolController {

    private static final Logger LOG = LoggerFactory.getLogger(ToolController.class);

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Displays the tool table.
     */
    @GetMapping
    public String listTools(Model model) {
        Map<String, ToolSpecWithState> tools = toolRegistry.getMapTools();
        model.addAttribute("tools", tools);
        return "tools";
    }

    /**
     * Toggles the active state of a tool.
     */
    @PostMapping("/toggle")
    public String toggleTool(@RequestParam String name) {
        LOG.info("Toggling tool: {}", name);
        toolRegistry.toggle(name);
        return "redirect:/tools";
    }
}
