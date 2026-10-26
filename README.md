# MCP GA4 Java

Send your MCP server's requests, tool usage, reported AI clients and outcomes directly to Google Analytics 4. Java 17+, Gradle or Maven, no hosted collector.

This repository includes the library, a runnable sample MCP server, and [Chrome setup instructions for humans and browser-capable Claude or ChatGPT](docs/GA4_CHROME_SETUP.md).

## Install

Coordinates for the first release are `io.github.shanifdhanani:mcp-ga4:0.1.0`. Confirm its availability on [Maven Central](https://central.sonatype.com/artifact/io.github.shanifdhanani/mcp-ga4) before using a released dependency. Until publication, use `./gradlew :library:publishToMavenLocal` and `mavenLocal()` for development.

```groovy
repositories { mavenCentral() }
dependencies { implementation 'io.github.shanifdhanani:mcp-ga4:0.1.0' }
```

```xml
<dependency>
  <groupId>io.github.shanifdhanani</groupId>
  <artifactId>mcp-ga4</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Add it to your server

Create one sender for the application, then call the tracking hook after each JSON-RPC request completes. The integration is framework independent: it works with an existing handler rather than requiring a different MCP server.

```java
import ai.nobi.mcp.ga4.Ga4Client;
import ai.nobi.mcp.ga4.Ga4Config;
import ai.nobi.mcp.ga4.tracking.McpAnalytics;
import ai.nobi.mcp.ga4.tracking.TrackingContext;

Ga4Client ga4 = new Ga4Client(Ga4Config.builder()
        .measurementId(System.getenv("GA4_MEASUREMENT_ID"))
        .apiSecret(System.getenv("GA4_API_SECRET"))
        .build());
McpAnalytics analytics = new McpAnalytics(ga4);

TrackingContext context = TrackingContext.builder()
        .serverName("catalog-server")
        .clientId("stable-anonymous-client-id")
        .conversationId("opaque-conversation-id")
        .build();

// After your existing handler completes. parsedRequest and headers come from your server.
analytics.request(context, parsedRequest, headers, "success", elapsedMillis);
```

`clientId` must be an opaque identifier stable for the caller you can actually identify. Do not use an IP address, email, access token, the agent name, or a new random value per request. If omitted, `clientId` defaults to a shared per-process fallback that preserves request totals but cannot establish unique visitors. See [identity and counting rules](docs/EVENT_SCHEMA.md).

For legacy MCP clients, retain the reported initialize client name for subsequent requests and supply `agentName(...)`. The request hook also recognizes newer per-request `_meta["io.modelcontextprotocol/clientInfo"]` metadata. Supply the same resolved agent in the context for search and downstream outcome hooks. Headers can provide a fallback, with bots and health checks classified separately. Identity is reported metadata, not proof of which model or person made the call.

Searches and outcomes need explicit application hooks. Record a search only after your application accepts it. Connect returned links to your website or redirect handler to observe clicks. Purchases require a trusted order source. The library cannot infer downstream actions from a successful tool response. [Metric coverage](docs/SHOP_NOBI_METRIC_COVERAGE.md) describes all hooks and formulas.

## Run the sample

```bash
./gradlew check
./gradlew :sample-server:run
```

Set `GA4_MEASUREMENT_ID` and `GA4_API_SECRET` through your local secret mechanism to send real GA events. Never commit the secret. The sample's own configuration and endpoint instructions are in [the sample guide](docs/SAMPLE_SERVER.md).

## Set up Google Analytics

Follow [GA4 Chrome setup](docs/GA4_CHROME_SETUP.md) to create the custom fields and request, tool, agent and source graphs. You can give that file to a browser-capable agent after authorizing it to configure your chosen property. No browser control is assumed for a normal chat session.

Google describes Measurement Protocol as an addition to browser/app tracking; server-only reporting is partial. Request and tool event counts are useful directly, but GA users, engagement and acquisition reports do not automatically identify people or reconstruct MCP discovery. Use explicit source tags and first-party identifiers where available. [Google documentation](https://developers.google.com/analytics/devguides/collection/protocol/ga4)

## Delivery and data

Events enter a bounded in-memory queue; MCP handling does not wait for HTTP. `track(...)` returns local acceptance, `flush(Duration)` waits for terminal delivery outcomes, and `stats()` reports accepted, delivered, rejected and failed counts. A delivered event means an HTTP success, not confirmed GA processing. No retries or durable storage are provided. Close the sender during graceful shutdown and use a host-managed durable outbox if lossless reporting is required.

Queries and prompts are excluded by default. Optional redacted query text is capped at GA's string limit. `sessionId(...)` always exports a SHA-256 correlation hash; raw MCP transport session credentials are not sent. Only scalar metadata is accepted, with no automatic collection of IP addresses, authorization headers, tool arguments or results. Sending personal information to GA is prohibited. See [security guidance](SECURITY.md).

- [Event schema and application hooks](docs/EVENT_SCHEMA.md)
- [Metric coverage and exact formulas](docs/SHOP_NOBI_METRIC_COVERAGE.md)
- [GA BigQuery export queries](docs/queries.sql)
- [Maven Central release procedure](docs/PUBLISHING.md)
- [Contributing](CONTRIBUTING.md)

## License

[Apache License 2.0](LICENSE). Includes a patent grant and permits commercial use, modification and redistribution subject to its terms. Google Analytics, MCP, Claude and ChatGPT are third-party products; this project is not endorsed by their owners.
