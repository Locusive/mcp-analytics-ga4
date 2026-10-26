# Metric coverage

This inventory preserves the counting rules behind the reference Shop Nobi reports using generic events. It contains no customer activity or private implementation. “Covered” means the schema can represent the required inputs; downstream hooks still need to be connected in each host. An MCP library alone cannot observe website visitors, clicks, carts or orders.

Use the same date range, timezone, entry-point selection, spam exclusions and attribution model on both sides of a comparison. Default to **All traffic**. The comparison label **Without AI & bots** excludes known automation; it does not prove that the remainder are humans. Keep health checks and bots separate from named AI clients.

## Core MCP and discovery

| Metric | Definition / input | GA presentation |
|---|---|---|
| Total MCP requests | Completed incoming JSON-RPC requests, including failures. `SUM(request_count)` on `mcp_request`. | Custom MCP Requests metric; timeline and total. Raw event count also includes notifications. |
| Tool calls | `mcp_request` where method is `tools/call`; group by `tool_name`. | Filtered event count or `SUM(tool_call_count)`. |
| Agent / AI name | Reported client normalized with classification evidence; individual recognized bot/probe names and a distinct health-check class; unknown remains unknown. | `agent_name`, with `agent_class` for AI, bot and health-check grouping. |
| Discovery source | Explicit source/medium/campaign/channel; optional approved origin/referrer hostname evidence. | Custom event-scoped dimensions, not assumed GA default acquisition attribution. |
| Method, version, outcomes, latency | Request metadata, optional reported agent version, plus host-observed result and duration. | Dimension breakdowns; latency aggregates require custom metrics/export SQL. Add a host-approved transport parameter when needed. |
| Manifest/discovery fetches | Observed fetch events, separately counted. | `mcp_manifest_fetch` event count. Not proof of a discovery-to-call causal path. |
| Returned results / impressions | Returned result count or individual item/store/rank observations. | `mcp_impression` and its custom metrics/dimensions. Never substitute them for observed clicks. |

## Search, conversation and commerce reports

| Reference metric | Exact counting rule | Inputs / presentation |
|---|---|---|
| Searches | Accepted, recorded logical queries after spam exclusion. Deduplicate mirrored Search/Send Message records by logical query ID. | `SUM(search_count)` with one emission per accepted `search_id`; export distinct IDs checks parity. |
| Messages | Recorded Send Message activity plus qualifying MCP ask-question messages; do not double count mirrored records. | `SUM(message_count)`. It differs from tool calls and searches. |
| AI assistant and bot activity chart | Any tools/call with a nonblank query, topic or question, including page-finding calls. | `SUM(ai_query_count)` by agent; includes `mcp_search` and `mcp_page_request`. |
| Source search breakdown | Accepted recorded search-producing calls only. A page request alone does not contribute. | `SUM(search_count)` by source/agent; not the AI activity chart total. |
| Search text and timestamps | Original search time, reported assistant, approved query text or external query lookup. | Optional redacted `query_text`; full transcript remains outside GA. Export for row-level history. |
| Clicks | All qualifying downstream click events. Repeated clicks remain clicks. | `SUM(click_count)`, `mcp_link_open`. |
| CTR | **Distinct searches with at least one qualifying click / searches.** | Export distinct `search_id` join, or durable first-click `clicked_search_count / search_count` in a consistently attributed scope. Never `click_count / search_count`. |
| Query / source / channel CVR | **Attributed orders / searches.** | `SUM(order_count) / SUM(search_count)` in the same attribution scope. It is not purchaser conversion. |
| Overview CVR | **Distinct identified purchasers / distinct identified query visitors.** | Export stable-identity join or correctly deduplicated host counters. GA's default user conversion metric is not equivalent. |
| Orders | Deduplicated attributed order aggregates. Define whether split-store attribution yields one order per store; keep that policy fixed. | `mcp_purchase` with stable `transaction_id`; host dedup, export reconciliation. |
| Revenue | Attributed pretax line revenue, excluding un-attributed order lines. | `SUM(value)` on `mcp_purchase`, by currency. Do not add currencies without conversion. |
| AOV | **Attributed revenue / attributed orders.** | Custom metrics or export; denominator must match order attribution. |
| Items per order | **Attributed item quantity / attributed orders.** | `SUM(item_count) / SUM(order_count)`. |
| Cart rate | **Attributed cart event count / total messages.** | `SUM(cart_count) / SUM(message_count)`, not carts per user or per search. |
| Product views per query | **Attributed product view events / total messages.** | `SUM(product_view_count) / SUM(message_count)`; preserve this denominator even if the display label says query. |
| Query pageviews | Result/product pageviews attributable to a query. | `SUM(query_pageview_count)` on `mcp_result_view`; distinct from all website pageviews. |
| Revenue per query visitor | **Attributed revenue / distinct identified query visitors.** | Export identity aggregation; currency policy as above. |
| Query visitor share | **Distinct identified query visitors / all unique identified website page viewers.** | Requires website hooks and identity joins; counting MCP clients alone is insufficient. |
| Entry-point breakdown | The same chosen subset of entry points on all counts and denominators. | `entry_point` dimension; do not silently remove bot/MCP entry points. |
| Top queries / channels / sources | Searches, clicks, distinct-search CTR, orders, query CVR and revenue grouped by that dimension. | Native counts where possible; exact joined metrics use export SQL. |
| Per-store clicks | Observed clicks grouped by store, link type and placement. | `store`; `link_type=affiliate/plain/unknown`; `placement=affiliate/paid_merchant/organic/unknown`. |

Zero denominators yield unavailable/null rates in SQL. A presentation may choose 0%, but must state that policy. Never average row percentages to obtain a total: aggregate the numerators and denominators first.

## Engagement and browsing

| Metric | Exact counting rule | Required data |
|---|---|---|
| Engagement visits | One identified **visitor-day**, in the reporting timezone. | Stable first-party visitor identity plus finalized daily activity. Not MCP transport sessions or GA default Sessions. |
| Average active time | Active seconds across those visitor-days / engagement visits. | Measured foreground/activity time from the website, not server request duration. |
| Average pages | Page counts across those visitor-days / engagement visits. | Website pageviews and visitor-day aggregation. |
| Engagement visit CVR | Converted identified visitor-days / engagement visits. | Daily conversion flag linked to the same visitor-day. |
| Source browsing sessions | The host's browsing-session definition, separately from engagement visitor-days. | Consistent session ID, source and website activity. Do not relabel daily visitors as sessions. |
| New / returning identified visitors | First-seen date versus selected range using persistent host history. | A lookback limited to the report range cannot establish “new.” |

## Conversation history in the reference screenshots

**Links opened** counts all downstream click events associated with a conversation from the search through the current reporting day, even if the search was outside the selected click date range. Use `conversation_id` and an explicitly longer click horizon. This can differ from the clicks shown in a date-limited performance table. A dash means not applicable or unavailable, not an observed zero.

**Same browser** counts distinct **other conversations** linked by the same first-party browser ID. It is neither visitor count nor repeated calls in the same conversation. Unknown browser identity stays unavailable. A later browser link can connect an old AI conversation to newer website conversations. Exact parity needs the host correlation pipeline, a `mcp_browser_link` hook, or the export join in [queries.sql](queries.sql). It is not a native GA graph dimension.

## Native GA versus export

The Chrome guide creates request, tool, agent, source, accepted-search, message, click and custom commerce graphs inside GA. Additional metrics need custom calculated metrics with the correct host-provided counters or BigQuery export. Raw GA event counts do not implement distinct searches, distinct visitors, conversation joins, visitor-day sessions or multi-day downstream attribution.

[queries.sql](queries.sql) provides concrete export templates for those joins. It uses the standard repeated `event_params` export structure. Replace the dataset and dates; preserve search and click horizon differences. The queries produce BigQuery results, not automatically installed GA graphs. They can back a separate BI report if desired. [Google's export schema](https://support.google.com/analytics/answer/7029846?hl=en), [UI/export differences](https://developers.google.com/analytics/blog/2023/bigquery-vs-ui)
