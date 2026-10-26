package ai.nobi.mcp.ga4.tracking;

/**
 * Describes the client application and the evidence used to name it.
 *
 * @param name reported or normalized application name
 * @param classification assistant, bot, health check or unknown classification
 * @param evidence metadata field used to resolve the name
 */
public record AgentIdentity(String name, String classification, String evidence) { }
