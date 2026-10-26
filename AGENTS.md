# Project instructions

Read README.md and docs/EVENT_SCHEMA.md before changing code. Keep Java 17 support. Write small documented classes and methods. Add functional tests for every behavior change. Never skip tests or commit secrets. Run ./gradlew check before declaring a release ready. Keep public changes generic; never include private Nobi code, customer data or credentials.

For user-authorized GA4 setup, follow docs/GA4_CHROME_SETUP.md. Reuse the user's signed-in work Chrome profile, inspect the current page before acting, preserve existing configuration, and verify saved reports and received events. Close agent-created tabs immediately after use. Record a safe restart URL before closing a paused setup page. Never close pre-existing user tabs.
