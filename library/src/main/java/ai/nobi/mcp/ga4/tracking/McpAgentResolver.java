package ai.nobi.mcp.ga4.tracking;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts reported MCP client names and distinguishes known applications from probes. */
public final class McpAgentResolver {
    /**
     * Resolves an application name from explicit context, MCP metadata, or observed HTTP headers.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param request The decoded JSON-RPC request object.
     * @param headers Observed request headers used only for allowlisted attribution.
     * @return The requested immutable context value.
     */
    public AgentIdentity resolve(TrackingContext context, Map<String, Object> request, Map<String, String> headers) {
        String userAgent = header(headers, "user-agent");
        String lowerAgent = userAgent.toLowerCase(Locale.ROOT);
        if (lowerAgent.contains("health") || lowerAgent.contains("probe") || lowerAgent.contains("bot") || lowerAgent.contains("crawler")) {
            Matcher token = Pattern.compile("[a-zA-Z0-9_.-]*(?:bot|crawler|probe|healthcheck)[a-zA-Z0-9_.-]*", Pattern.CASE_INSENSITIVE).matcher(userAgent);
            String name = token.find() ? token.group() : "Health check";
            return new AgentIdentity(name, lowerAgent.contains("health") ? "health_check" : "bot", "user_agent");
        }
        if (header(headers, "x-anthropic-client").equalsIgnoreCase("Cowork")) {
            return new AgentIdentity("Cowork", "ai", "provider_header");
        }
        Object explicit = context.parameters().get("agent_name");
        if (explicit instanceof String name) { return classify(name, "host"); }
        Map<?, ?> parameters = asMap(request.get("params"));
        Map<?, ?> metadata = asMap(parameters.get("_meta"));
        Object name = asMap(parameters.get("clientInfo")).get("name");
        if (name == null) { name = asMap(metadata.get("io.modelcontextprotocol/clientInfo")).get("name"); }
        if (name == null) { name = asMap(metadata.get("clientInfo")).get("name"); }
        if (name instanceof String reported && !reported.isBlank()) { return classify(reported, "client_info"); }
        return userAgent.isBlank() ? new AgentIdentity("Unknown", "unknown", "unknown") : classify(userAgent, "user_agent");
    }

    private static AgentIdentity classify(String value, String evidence) {
        String lower = value.toLowerCase(Locale.ROOT);
        String name;
        if (lower.contains("cowork")) { name = "Cowork"; }
        else if (lower.contains("claude") && (lower.contains("code") || lower.contains("cli"))) { name = "Claude Code"; }
        else if (lower.contains("claude")) { name = "Claude"; }
        else if (lower.contains("chatgpt") || lower.contains("openai-mcp")) { name = "ChatGPT"; }
        else if (lower.contains("codex")) { name = "Codex"; }
        else if (lower.contains("cursor")) { name = "Cursor"; }
        else if (lower.contains("gemini")) { name = "Gemini"; }
        else { name = evidence.equals("user_agent") ? "Unknown" : value; }
        return new AgentIdentity(name, name.equals("Unknown") ? "unknown" : "ai", evidence);
    }

    private static Map<?, ?> asMap(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue).filter(value -> value != null).findFirst().orElse("");
    }
}
