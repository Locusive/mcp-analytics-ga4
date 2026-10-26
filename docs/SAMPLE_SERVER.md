# Run the sample MCP server

The sample uses Java's HTTP server on `127.0.0.1:8099`. It demonstrates JSON-RPC initialize, ping, tools/list and tools/call over stateless HTTP, with MCP protocol version `2025-11-25`. It is a small integration example, not a production authentication or payment service. Remote hosted agents cannot reach your loopback address without your own secure access arrangement.

## Start

Use Java 17+, then run:

```bash
./gradlew check
```

Set `GA4_MEASUREMENT_ID` and `GA4_API_SECRET` through your local secret mechanism. Choose a development GA stream so synthetic searches, clicks and orders do not affect real reporting. Then:

```bash
./gradlew :sample-server:run
```

Optional environment variables are `PORT` and `GA4_ENDPOINT`. The endpoint defaults to Google's normal HTTPS collection URL. For offline local capture use a loopback HTTP collector, a syntactically valid fake measurement ID and a fake secret. The automated tests already exercise real local HTTP delivery without Google credentials; an offline test is not a verified GA receipt.

The `/mcp` endpoint validates local Host/Origin values and accepts native clients without an Origin header. Accepted notifications return HTTP 202 with no body.

The tools are `search(query)` and `find_page(topic)`. Both return `structuredContent.search_id` and a sample link. Only `search` records an accepted search. Opening a `find_page` link counts a click but does not contribute a clicked search or inflate distinct-search CTR. No raw query text is sent to GA.

## Controlled receipt

Run this once in a clean sample process and an isolated measurement scope. It requires curl and Python 3. All content and identifiers below are synthetic.

```bash
curl --fail-with-body -sS http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"Claude Code","version":"demo"}}}'

curl --fail-with-body -sS http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

first_response=$(curl --fail-with-body -sS http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search","arguments":{"query":"blue example mug"},"_meta":{"clientInfo":{"name":"ChatGPT"}}}}')
first_search_id=$(printf '%s' "$first_response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["structuredContent"]["search_id"])')

second_response=$(curl --fail-with-body -sS http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search","arguments":{"query":"green example plate"},"_meta":{"clientInfo":{"name":"Claude Code"}}}}')
second_search_id=$(printf '%s' "$second_response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["structuredContent"]["search_id"])')

curl --fail-with-body -sS http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_page","arguments":{"topic":"example kitchen guide"},"_meta":{"clientInfo":{"name":"Claude Code"}}}}'

curl --fail-with-body -sS "http://127.0.0.1:8099/click?search_id=$first_search_id" \
  -H 'Cookie: sample_browser_id=demo-browser-one'
curl --fail-with-body -sS "http://127.0.0.1:8099/click?search_id=$first_search_id" \
  -H 'Cookie: sample_browser_id=demo-browser-one'
curl --fail-with-body -sS "http://127.0.0.1:8099/click?search_id=$second_search_id" \
  -H 'Cookie: sample_browser_id=demo-browser-one'

curl --fail-with-body -sS -X POST \
  "http://127.0.0.1:8099/demo/outcomes?search_id=$first_search_id&transaction_id=demo-order-1"

# Replaying the same demo transaction should report duplicate=true and emit no new outcomes.
curl --fail-with-body -sS -X POST \
  "http://127.0.0.1:8099/demo/outcomes?search_id=$first_search_id&transaction_id=demo-order-1"
```

Expected metric sums for `server_name=mcp-ga4-example`, after collection/processing:

| Metric | Expected |
|---|---:|
| Protocol messages | 5 |
| Requests excluding the notification | 4 |
| Tool calls | 3 |
| `search` / `find_page` tool calls | 2 / 1 |
| AI query activities | 3 |
| Accepted searches | 2 |
| Recorded messages | 0 |
| Clicks / clicked searches | 3 / 2 |
| Distinct-search CTR | 100% |
| Orders / revenue / items | 1 / USD 24.50 / 2 |
| Cart events / product views / query pageviews | 1 / 1 / 1 |

Claude Code contributes three requests and ChatGPT one; the notification contributes zero requests. The two clicked search conversations share a synthetic browser ID, so each has one other linked conversation in the export query. This does not create an identified shopper. The source is the sample's explicit `example` tag. A supplied `Mcp-Session-Id` is exported only as a SHA-256 correlation hash.

The sample is stateless and does not retain initialize client metadata for later requests; the example supplies `_meta.clientInfo` with each tool call. In a real integration retain trusted initialize metadata in your own MCP session state and pass `agentName(...)` on later calls.

The `/demo/outcomes` endpoint generates synthetic cart, view and purchase events. It does not charge money. Deduplication is in memory and resets on restart. Search history is capped; a sample process can discard older search IDs. Production integration needs durable deduplication and a trusted purchase source.

## Optional failure checks

After the controlled receipt, send an unknown tool, a blank query, malformed JSON or an unsupported HTTP method. Inspect JSON-RPC protocol errors separately from `isError` tool results. Additional request metrics from those checks must not be compared to the clean-run totals above.

Stop the process gracefully to let the sender close and drain. A forced termination can lose queued events. Follow [the Chrome guide](GA4_CHROME_SETUP.md) for actual GA verification; local HTTP acceptance alone is insufficient.
