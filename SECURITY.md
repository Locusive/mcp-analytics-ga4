# Security and privacy

The Measurement Protocol API secret authenticates collection; keep it on the server in a secret manager or environment variable. Never paste it into chat, screenshots, issues or source control. Configure logging so outgoing collection URLs are redacted, because GA requires the secret as a query parameter. Do not expose the collection endpoint through a public forwarding route.

Only send data your application is authorized to collect. Do not send emails, names, raw IP addresses, access tokens, session credentials, prompts, tool arguments or results. A hash does not automatically make personal data acceptable for Google Analytics. Use randomly assigned first-party identifiers or non-personal internal IDs. Implement consent and opt-out behavior in the host application before invoking tracking hooks. See [Google's policy on personal information](https://support.google.com/analytics/answer/6366371?hl=en).

The context builder always hashes MCP transport session IDs before export. Other conversation/search/browser correlation IDs longer than 100 Unicode code points are hashed to preserve joins within GA limits. Display metadata is truncated to that limit. These transformations are not anonymization, and host-provided top-level client/user IDs must never contain credentials or personal data. Generic event detail maps remain the host's responsibility.

Raw query text is off by default. An optional query redactor must return only approved non-personal text. Review it against real sensitive examples before enabling it. A truncated query can still contain personal information.

The queue is bounded, in memory and best effort. Monitor rejected and failed event counts. A successful collection HTTP status does not prove GA processed an event. Verify schema with Google's validation endpoint and receipt in the selected GA property. Do not automatically replay failures or issue ambiguous retries that could duplicate counts.

For a vulnerability, use the repository's private GitHub security reporting option when enabled. If it is unavailable, open a minimal issue requesting a private reporting channel without disclosing exploit details or sensitive data. Do not send secrets to maintainers.
