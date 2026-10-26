# Event schema

Schema version: 0.1. A successful MCP call is an event; it does not prove that a human visited, opened a result or purchased.

## Request counting

Call `request(...)` exactly once after each incoming protocol message completes, including rejected requests and tool errors. It emits `protocol_message_count=1` for valid requests and notifications. `request_count` is 1 for requests with an ID and 0 for notifications; `notification_count` is the reverse. Invalid envelopes have zero protocol counters and retain their error outcome. Therefore raw `mcp_request` Event count is a protocol activity count, not the request metric. A `tools/call` contributes both one protocol request and one tool call. Initialize, ping, tools/list, resources and prompts contribute protocol requests but no tool call. Notifications and transport-level HTTP GET/DELETE, health routes and malformed HTTP bodies are not additional JSON-RPC requests; track them separately when needed. Iterate valid batch members if your host supports JSON-RPC batches. Outbound GA requests are never MCP requests.

Keep five populations separate:

1. JSON-RPC requests.
2. `tools/call` requests.
3. AI query/topic/question calls, including page-finding calls.
4. Accepted searches actually recorded by the application.
5. Recorded messages, including application messages and qualifying ask-question calls.

Do not emit both a wrapper hook and a handler hook for the same request. A failure still counts as a request; set the outcome appropriately.

## Identity and attribution

| Input | Meaning |
|---|---|
| `clientId` | Opaque stable collection identity. Use the existing GA browser client ID when deliberately joining consented browser events. It is a top-level Measurement Protocol field, not a custom parameter. If omitted, the builder uses one process-wide fallback, which cannot establish visitor counts. |
| `userId` | Optional non-personal internal user ID. Never an email or authentication credential. |
| `sessionId` | Optional MCP transport session identifier. The builder always exports its SHA-256 hash as `mcp_session_id`, never the raw transport credential. It is not a GA session identifier or a visitor. |
| `conversationId` | Optional opaque host conversation ID. Distinct from an MCP transport session. |
| `searchId` | Optional opaque ID of the logical accepted search; carry it onto downstream events. Reuse for mirrored copies and retries of that search. |
| `browserId` | Optional first-party opaque browser correlation ID supplied by your website, not inferred from agent name or IP. |
| `identifiedVisitor` | Whether the host has a stable visitor identity. Do not mark anonymous fallback IDs as identified visitors. |
| `agentName` | Reported MCP client name, or another caller-approved hint. Older clients identify themselves in initialize; newer clients can include `_meta["io.modelcontextprotocol/clientInfo"]` with each request. Retain legacy initialize data for later requests. This is not a verified model name. |
| `source`, `medium`, `campaign`, `channel` | Explicit first-party attribution supplied by the host, such as a tagged installation link. Unknown discovery remains unknown. |

The library does not identify a person behind an agent. A shared account or client instance can represent many people. A new random client ID per request inflates user counts. Reusing one fallback ID preserves event totals but collapses unknown callers into one identity. Use filtered event counts as the authoritative basic MCP metrics.

Agent classification prioritizes recognized bots and health checks over client claims. Recognized user-agent tokens preserve individual bot/probe names with `agent_class=bot`; health checks use `agent_class=health_check`. Cowork hints, Claude Code and ordinary Claude remain distinct. Preserve raw reported client names only when approved as safe metadata. Do not send raw headers, IPs, credentials or complete request parameters. Request source precedence is explicit context, then `_meta.source` or `_meta.utm_source`, then HTTP Referer hostname, then `unknown`. `source_evidence` records `client_tag` or `referrer_host` for those fallbacks. A reported tag or referrer is evidence, not proof of discovery causality. Pass attribution in the context for search and downstream events too. Discovery source is separate from the client name and from the host that opens a returned link.

Measurement Protocol is intended to augment browser/app collection. Server-only user, session, geography and acquisition reporting can be partial. `session_id` and actual `engagement_time_msec` help session reporting; do not invent engagement duration from HTTP latency. [Protocol overview](https://developers.google.com/analytics/devguides/collection/protocol/ga4), [reference](https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference)

## Common parameters

`server_name`, `agent_name`, `source`, `medium`, `campaign`, `channel`, `entry_point`, `mcp_session_id`, `conversation_id`, `search_id`, `browser_id`, `identity_kind` accompany events when supplied and relevant. `identity_kind` distinguishes identified and anonymous collection contexts. A real numeric GA `session_id` and measured `engagement_time_msec` can be supplied explicitly through generic event details or a low-level event when deliberately joining an existing browser session. They are not automatically created by `sessionId(...)`. Missing metadata must not be guessed.

Keep high-cardinality IDs in the BigQuery export; do not register conversation, search, browser or transaction IDs as report dimensions. GA can group high-cardinality reports into an “other” row. [Custom event guidance](https://support.google.com/analytics/answer/12229021?hl=en)

## Built-in hooks

All counter values below are numeric. Booleans are encoded as 1 or 0.

| Hook / event | Event-specific parameters | Rule |
|---|---|---|
| `request(...)` / `mcp_request` | `request_count=0/1`, `notification_count=0/1`, `protocol_message_count=0/1`, `tool_call_count=1` for tools/call, `method`, optional `tool_name`, `outcome`, `duration_ms`, `agent_class`, `agent_evidence`, optional `agent_version`, `protocol_version` | One protocol activity event. Requests and notifications use separate counters. HTTP success can contain an MCP error; the host supplies the outcome. |
| `search(...)` / `mcp_search` | `ai_query_count=1`, `search_count=0/1`, `message_count=0/1`, `page_request_count=0`, `search_kind`, `query_hash` | A query/topic/question activity. Accepted-search and message flags come from actual application persistence. |
| `search(...)` / `mcp_page_request` | Same counters; `page_request_count=1` | A page-finding request can appear in AI activity while contributing zero accepted searches. |
| `linkOpened(...)` / `mcp_link_open` | `click_count=1`, `clicked_search_count=0/1`, `store`, `link_type`, `placement` | Invoke in your observed click/redirect handler. The host durably marks the first qualifying click for each accepted search. Page-topic clicks count as clicks without incrementing clicked searches. |
| `purchase(...)` / `mcp_purchase` | `order_count=1`, `transaction_id`, `value`, `currency`, `item_count` | A trusted, deduplicated order or attributed order aggregate. `value` is attributed pretax line revenue and `item_count` is attributed quantity. |

`mcp_purchase` is a custom event, not GA's recommended `purchase` event. This release accepts scalar parameters, not ecommerce `items` arrays. Use custom order/revenue charts; do not expect the standard Ecommerce purchases report to populate from this hook.

The request hook does not also emit a search, click or purchase. Invoke those hooks only when the application observes them. `search(...)` accepts kind `search`, `question` or `page`. Kind `page` requires both accepted-search and message flags to be false. Set the flags to preserve mirrored “Search” and “Send Message” semantics without double counting one logical query. Host code must preserve the same logical search ID and emit the accepted-search count once.

### Optional query text

`new McpAnalytics(client)` excludes query text. To opt in, use `new McpAnalytics(client, queryRedactor)` with a `UnaryOperator<String>` that removes sensitive data before returning approved text. It adds `query_text`, and `query_truncated=1` when shortened to the 100-character GA limit. Use `search_id` to retrieve any longer approved original from your own system. Query hashes are correlation data, not anonymization or protection against dictionary recovery.

### Application events

Use `event(context, eventName, details)` for downstream activity. These hooks need integration with the existing website, redirect, order or session pipeline; MCP traffic alone cannot supply them.

| Event | Suggested scalar details | Host responsibility |
|---|---|---|
| `mcp_message` | `message_count=1` | Accepted message; do not also count it through a search hook. |
| `mcp_cart` | `cart_count=1` | Observed attributed cart event. |
| `mcp_product_view` | `product_view_count=1`, optional `item_id`, `store` | Observed attributed product view. |
| `mcp_result_view` | `query_pageview_count=1` | Observed result/product pageview attributable to a query. |
| `mcp_page_view` | `pageview_count=1` | Observed website pageview; needed for all-visitor denominator. |
| `mcp_visit` | `visit_count=1`, `active_seconds`, `page_count`, `converted_visit_count=0/1` | One finalized identified visitor-day for engagement parity. Do not emit a fresh summary on every request. |
| `mcp_manifest_fetch` | `manifest_fetch_count=1` | Observed manifest or discovery endpoint fetch; no implication that it caused a later request. |
| `mcp_impression` | `result_count`, `store`, `position`, `item_id` | Returned result/impression, kept separate from views and clicks. |
| `mcp_browser_link` | `other_conversation_id`, `first_link=0/1` | An observed link between conversations sharing a first-party browser ID. |

For native GA calculated metrics a host can optionally send `query_visitor_count`, `purchaser_count` and `website_visitor_count` on its own summary events. They require durable host deduplication in a defined reporting window. Do not sum daily unique counts into monthly unique counts. Prefer export SQL for arbitrary date ranges.

## Limits and delivery

Event and parameter names start with a letter and contain only letters, digits and underscores, up to 40 characters. Events accept up to 25 scalar parameters. Parameter strings are limited to 100 Unicode code points. TrackingContext truncates longer display metadata without splitting a code point; longer conversation, search and browser correlation IDs become SHA-256 hashes instead of being truncated into colliding prefixes. Transport session IDs are always hashed, regardless of length. The top-level `clientId` and `userId` retain their separate 256-character validation and must already be non-personal, non-credential identifiers. Low-level `AnalyticsEvent` rejects invalid values rather than silently dropping fields. Generic event details are not automatically normalized. Keep custom maps small enough for the common context plus event-specific fields.

Events retain their original occurrence time. The sender rejects events older than 72 hours and events more than one minute in the future. It uses bounded background delivery with no retries or persistent queue. HTTP 2xx increments `delivered`, not confirmed-processed counts; `flush` can finish even when events failed. Check `stats().rejected()` and `stats().failed()` as well as the flush result.

Validate representative payloads with `/debug/mp/collect`; that endpoint does not collect events. Then send ordinary collection events and verify them inside the correct GA property. [Validation guide](https://developers.google.com/analytics/devguides/collection/protocol/ga4/validating-events)
