# Set up MCP graphs in Google Analytics

For a person, or Claude/ChatGPT in a session with authorized Chrome control. A normal text chat cannot operate Chrome. This guide creates additive reports in the selected GA4 property. It does not need a hosted collector, Tag Manager server container or new Google Cloud project.

## Agent task you can copy

> Set up MCP GA4 Java reporting in my chosen GA4 property using this guide. Inspect my existing signed-in work Chrome profile and reuse its GA tab. Read the event schema first. Confirm the property and stream from the live page. Reuse matching definitions and reports, preserve all existing configuration, and create the missing MCP reports. Keep credentials out of chat, screenshots and the repository. Verify saved report URLs and actual received events. Tell me what is verified and what is waiting for GA processing. Close every tab you create immediately when its task finishes; preserve my pre-existing tabs. If browser access, a property choice or account access is missing, state the exact missing item.

## 1. Confirm the property and access

Open [Google Analytics](https://analytics.google.com/) in the user's signed-in work Chrome profile. Inspect existing tabs before creating one. Record the property name, numeric property ID, timezone, web stream name and `G-...` measurement ID. Select the property requested by the user; do not infer it from an unrelated last-opened account. If multiple candidate properties remain, resolve that choice before changing configuration.

Creating custom definitions and shared detail reports requires Editor or Administrator access. If GA's Library is absent, check access rather than creating another property. Read the existing Custom definitions and Reports Library first. Preserve all current dimensions, metrics, streams, key events, filters and report collections.

## 2. Configure server credentials

Go to **Admin > Data collection and modification > Data streams**, select the web stream, then **Measurement Protocol API secrets**. Reuse the application's existing secret where available, or create one named `Agent Analytics`.

If an agent encounters a legal or privacy acknowledgment that asserts end-user disclosures, rights or consent, stop and ask the user for confirmation at that step before accepting it. Authorization to set up reporting does not establish that those assertions are true. Record a safe restart URL and preserve the acknowledgment for the user.

Put the measurement ID and secret into the application's existing secret mechanism as `GA4_MEASUREMENT_ID` and `GA4_API_SECRET`. Never copy the secret into chat, screenshots, an issue, README or a committed environment file. If the agent cannot transfer a secret safely to the host's secret mechanism, have the user do that step. Do not regenerate or delete existing secrets merely to discover their values. [Google's event sending prerequisites](https://developers.google.com/analytics/devguides/collection/protocol/ga4/sending-events?client_type=gtag)

## 3. Register dimensions and metrics

Go to **Admin > Data display > Custom definitions**. Check existing definitions by parameter name and scope before creating anything. Reuse an exact match even if its display name differs. If an existing definition conflicts, record the conflict; do not archive or replace it. Check quota before proceeding. Scope is **Event** for every field below. Type a parameter name if the dropdown has not learned it yet.

Create these dimensions:

| Display name | Event parameter |
|---|---|
| MCP Server | `server_name` |
| MCP Tool | `tool_name` |
| MCP Method | `method` |
| MCP Agent | `agent_name` |
| MCP Agent Class | `agent_class` |
| MCP Agent Evidence | `agent_evidence` |
| MCP Source | `source` |
| MCP Medium | `medium` |
| MCP Campaign | `campaign` |
| MCP Channel | `channel` |
| MCP Entry Point | `entry_point` |
| MCP Outcome | `outcome` |
| MCP Search Kind | `search_kind` |
| MCP Store | `store` |
| MCP Link Type | `link_type` |
| MCP Placement | `placement` |

For all the following custom metrics use **Standard** units. Revenue is labeled as a raw currency amount and must be filtered or grouped by currency; it is not automatic GA currency conversion.

| Display name | Event parameter |
|---|---|
| MCP Requests | `request_count` |
| MCP Tool Calls | `tool_call_count` |
| MCP AI Query Activities | `ai_query_count` |
| MCP Searches | `search_count` |
| MCP Messages | `message_count` |
| MCP Clicks | `click_count` |
| MCP Clicked Searches | `clicked_search_count` |
| MCP Orders | `order_count` |
| MCP Revenue Amount | `value` |
| MCP Items | `item_count` |
| MCP Cart Events | `cart_count` |
| MCP Product Views | `product_view_count` |
| MCP Query Pageviews | `query_pageview_count` |

Optionally add dimension **MCP Agent Version** for `agent_version` and metric **MCP Protocol Messages** for `protocol_message_count` when those distinctions are useful. Protocol messages include notifications; MCP Requests excludes them. If commerce hooks are used, also add dimension **MCP Currency** for `currency`. Optional approved query text can use dimension **MCP Query Text** for `query_text`. Keep IDs and query hashes out of registered dimensions; they are for export joins. Register only fields you intend to use and preserve quota for the existing application. Custom fields may take 24 to 48 hours to become reportable after creation. [Custom definition behavior and limits](https://support.google.com/analytics/answer/14240153?hl=en)

## 4. Verify collection before interpreting reports

Run the [sample walkthrough](SAMPLE_SERVER.md) against a development stream or another explicitly chosen stream. Do not generate sample purchase data in an existing production property without the user's instruction. Use `server_name=mcp-ga4-example` to isolate sample events.

Validate representative payloads with Google's `/debug/mp/collect` endpoint or its Event Builder. An empty `validationMessages` list validates structure, not the stream secret or report receipt. Events sent to that validation endpoint never appear in GA. Use the normal `/mp/collect` endpoint for collection. [Validation guide](https://developers.google.com/analytics/devguides/collection/protocol/ga4/validating-events)

In **Reports > Realtime**, inspect the event names and parameter values after sending ordinary events. If needed use **Admin > Data display > DebugView** with deliberately configured debug events. Do not assume DebugView alone will establish a new server-only identity. For an existing tagged website, join the appropriate consented browser `client_id` and actual numeric `session_id` when available. The library's `sessionId(...)` exports a SHA-256 hash of the MCP transport session ID, not that numeric GA session ID.

The known sample sequence is four JSON-RPC requests, one initialized notification, three tool calls, two accepted searches, three AI query activities, three clicks, two distinct clicked searches and one custom purchase for USD 24.50. The failed/unsupported-tool checks in the optional sample section add more requests; keep them outside this receipt. Verify counts from the isolated controlled run after processing, not from an arbitrary populated property total.

An HTTP 2xx, `stats().delivered()`, or `flush(true)` is not evidence that GA processed the event. Check local rejected/failed counters, the validation response, Realtime receipt, and processed report counts separately. Server-only GA user counts can be partial. Do not rename “Total users” to human visitors.

## 5. Create shared request, tool, agent and source graphs

Go to **Reports > Library > Create new report > Create detail report > Blank**. Use the Customize report panel to add the dimensions, metrics, filter and charts below. Create only a report whose name and configuration do not already exist. On repeat runs, reopen and inspect the existing report before deciding anything is missing.

| Report name | Primary dimension | Metric | Filter |
|---|---|---|---|
| MCP Requests | Event name | MCP Requests | Event name exactly `mcp_request` |
| MCP Tools | MCP Tool | MCP Tool Calls | MCP Method exactly `tools/call` |
| MCP Agents | MCP Agent | MCP Requests | Event name exactly `mcp_request` |
| MCP Discovery Sources | MCP Source | MCP Requests | Event name exactly `mcp_request` |
| MCP Searches | MCP Agent | MCP Searches | Event name exactly `mcp_search` |
| MCP AI Query Activity | MCP Agent | MCP AI Query Activities | Event name matches `^(mcp_search|mcp_page_request)$` |
| MCP Link Opens | MCP Store | MCP Clicks | Event name exactly `mcp_link_open` |

For **exactly matches**, GA may offer only values already observed in the property. If the required value is absent, use **matches regex** with an anchored expression instead: `^mcp_request$` for the request, agent and source reports; `^tools/call$` for the tool report; `^mcp_search$` for searches; and `^mcp_link_open$` for link opens. These match the same complete values and can be saved before events arrive. Use **matches regex**, not **matches partial regex**, for the AI query activity expression in the table.

Add **MCP Server** as an available secondary dimension to every report. Enable a line chart for changes over time and a bar chart for breakdowns where supported. Save each with the name above and reopen its saved view. The total request metric excludes notifications; do not replace it with raw Event count, which includes those protocol messages. [Create a detail report](https://support.google.com/analytics/answer/13844077?hl=en)

To expose them in navigation, create an **MCP Analytics** collection in Library if absent, add a topic and the saved reports, save it, then publish that new collection. If it already exists, add only missing reports while preserving its contents. Do not unpublish another collection to make space. [Customize report navigation](https://support.google.com/analytics/answer/10460557?hl=en)

### Optional exploration with all graphs together

In **Explore**, create or reuse a free-form exploration named **MCP Analytics**. Import the same dimensions and metrics. Create a line tab for MCP Requests with Event name exactly `mcp_request`; create table/bar tabs for MCP Tools, MCP Agents and MCP Source using the corresponding metrics and filters above. Select an explicit date range. A free-form exploration is separate from the published Reports collection; record which one you configured. [Free-form exploration](https://support.google.com/analytics/answer/9327972?hl=en)

### Commerce and exact conversion metrics

Create a custom **MCP Outcomes** detail report filtered to `mcp_purchase`, with MCP Agent, MCP Source and MCP Currency dimensions plus MCP Orders, MCP Revenue Amount and MCP Items metrics. Restrict to one currency when totaling revenue. Add message/cart/view reports only when those host hooks are wired.

Do not substitute standard Ecommerce purchases, GA conversion rate or Clicks/Searches for the project's definitions. The custom purchase hook does not send ecommerce item arrays. Exact distinct-search CTR, overview visitor CVR and same-browser conversations require the joins or correctly deduplicated host counters described in [metric coverage](SHOP_NOBI_METRIC_COVERAGE.md).

## 6. Optional export for exact joins

Only when the user has requested export setup, inspect **Admin > Product links > BigQuery links**. Reuse a matching link. Creating a new link needs an explicitly selected project, appropriate access and agreement to any related charges. Do not enable billing or streaming by inference.

Run [queries.sql](queries.sql) on the standard GA export, replacing the dataset and reporting timezone. Daily data is more stable; current-day intraday data is provisional. Same-browser results need retained correlation history. Links-opened history uses a later observation horizon than the search report when matching the reference report. Export results do not install native GA graphs. [BigQuery export behavior](https://support.google.com/analytics/answer/9358801?hl=en)

## 7. Verify, record, clean up

Record this receipt in the task's own notes, without credentials or customer query text:

```text
Property name / ID:
Stream name / measurement ID:
Timezone and report date range:
Dimensions reused / created:
Metrics reused / created:
Saved report or exploration names / URLs:
MCP Analytics collection published and reopened:
Validation status and event names:
Realtime event receipt:
Controlled-run processed totals:
Still waiting for processing or blocked by:
Owned Chrome tab IDs created / closed:
Remaining owned tab and exact reason, if any:
Safe restart URL and required state for paused work:
```

Reopen saved reports to verify persistence and filters. If GA is still processing, report **configured, awaiting processed data**, not “verified complete.” Do not wait with an owned tab open when the work can restart from a safe report URL. Close owned finished tabs immediately, inspect the remaining tab inventory, and confirm cleanup. Preserve every pre-existing user tab. Never record a secret-bearing URL as a restart link.
