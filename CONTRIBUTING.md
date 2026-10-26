# Contributing

Use Java 17 and the checked-in Gradle wrapper. Read [AGENTS.md](AGENTS.md), [the schema](docs/EVENT_SCHEMA.md) and [counting definitions](docs/SHOP_NOBI_METRIC_COVERAGE.md) before changing behavior.

```bash
./gradlew check
./gradlew :library:assemble :library:generatePomFileForMavenJavaPublication
```

Keep integrations framework independent and methods small and documented. Add tests that exercise observable events and real local HTTP delivery. Do not skip tests or substitute mocked transport for delivery tests. Test Google endpoint behavior with a local capture server; ordinary tests must not need credentials or send production analytics.

Include the user-visible change, counting impact and checks run in each pull request. Update the schema and setup guide when an event or parameter changes. Preserve privacy defaults and stable counting semantics. Do not include customer data, private Nobi code, credentials, raw queries or browser screenshots containing account data in public contributions.

Contributions are provided under the repository's Apache 2.0 license. Maintainers review and release changes from feature branches. Follow [the publishing procedure](docs/PUBLISHING.md) for a release; an uploaded artifact is not complete until it resolves from Maven Central.
