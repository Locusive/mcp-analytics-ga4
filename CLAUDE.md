# Agent instructions

Read AGENTS.md, README.md and docs/EVENT_SCHEMA.md. Java 17, Gradle wrapper, functional tests, no skipped tests, no secrets or private customer data. Keep event counting definitions consistent with docs/SHOP_NOBI_METRIC_COVERAGE.md.

For a user-authorized GA configuration task, follow docs/GA4_CHROME_SETUP.md. Only a browser-capable session may operate Chrome. Confirm the selected property from the live UI, reuse the signed-in work profile, preserve existing reports and custom definitions, and verify saved configuration. Never claim a successful GA setup from local tests or an HTTP status alone.

Track owned browser tabs. Close each created tab as soon as its work finishes. Preserve user tabs. For a paused reproducible workflow, record a safe restart URL and required state, then close its owned tab. Before finishing inspect owned tabs and report cleanup or a concrete browser-control blocker.

Publish a release only when the user has authorized publication and the required namespace, signing key and Portal access exist. Follow docs/PUBLISHING.md and verify resolution from Maven Central. Never invent credentials or claim that Maven Local is Maven Central.
