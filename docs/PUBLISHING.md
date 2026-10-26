# Publish to Maven Central

Release coordinates: `io.github.shanifdhanani:mcp-ga4:0.1.0`. The GitHub repository is `Locusive/mcp-ga4-java`; Maven namespace ownership is separate from the repository organization. Change the release version for every published update. Central artifacts are immutable. [Sonatype immutability policy](https://central.sonatype.org/publish/requirements/immutability/)

## Account prerequisites

The publisher needs access to the verified `io.github.shanifdhanani` namespace in [Central Publisher Portal](https://central.sonatype.com/), a PGP signing key whose public key is available for verification, and authorization to release. A future maintainer must obtain namespace access from its owner. Do not invent an available namespace from a GitHub URL. [Namespace registration](https://central.sonatype.org/register/namespace/)

For API publishing, generate a Portal user token from the signed-in account and keep both parts in the existing secret manager. For manual uploading, use the signed-in Portal. Never paste tokens or private signing keys into chat, issues, command history or the repository. [Portal tokens](https://central.sonatype.org/publish/generate-portal-token/)

## Build and stage

Confirm the reviewed feature branch and release version. Check public source, dependency licenses, POM URLs, developer metadata, Apache 2.0 license and NOTICE. Exclude private integration evidence, customer data and all credentials.

Provide the private signing key through `MAVEN_SIGNING_KEY` and its passphrase through `MAVEN_SIGNING_PASSWORD`, using the approved secret mechanism. The script can alternatively read an existing ignored local `secrets/signing-key.asc` file. That local secret directory is excluded from Git; never add it to a bundle or share it. An omitted signing passphrase defaults to an empty string, so provide one when the key requires it.

Run the repository's release preparation script:

```bash
python3 scripts/prepare-central-bundle.py
```

It runs `clean check`, publishes a signed Maven-layout repository under `build/central-repository`, checks that each JAR/POM/module artifact has a signature, creates checksum sidecars, and writes:

```text
build/mcp-ga4-central-bundle.zip
```

The script prints the bundle SHA-256 checksum and does not upload or publish anything. Record that checksum in the release receipt. Verify the generated signatures against the expected public key as well; signature presence alone is not cryptographic verification.

The bundle must contain the main JAR, sources JAR, Javadoc JAR, POM, and their ASCII-armored `.asc` signatures. Gradle module metadata may also be present with a signature. The Maven paths inside the ZIP start directly with the group path, with no extra build-directory prefix:

```text
io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.jar
io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.jar.asc
io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.pom
io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.pom.asc
...
```

Sonatype also requires artifact checksums and POM project, license, developer and SCM metadata. Do not include local Maven metadata, unrelated versions, credentials or private task notes. [Publishing requirements](https://central.sonatype.org/publish/requirements/)

Unsigned `./gradlew :library:publishToMavenLocal` remains useful for development integration tests; it is not a Central release candidate.

## Upload, validate and publish

In Portal, select **Publish Component**, upload the bundle and inspect the validation result. Resolve every validation error before publishing. Alternatively use the documented Publisher API with a Portal token and a `USER_MANAGED` deployment, inspect it until `VALIDATED`, then publish it. Do not expose authentication headers in logs. [Portal upload](https://central.sonatype.org/publish/publish-portal-upload/), [Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

Check the signed-in Portal's current terms and limits. Keep a receipt with namespace, coordinates, deployment ID and final deployment state. `VALIDATED` means eligible for publication; it does not mean available from Central. `PUBLISHED` should be followed by independent repository checks.

## Verify public availability

Verify the POM and JAR at the actual repository, not only a local cache or the Portal screen:

```bash
curl --fail --head \
  https://repo.maven.apache.org/maven2/io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.pom
curl --fail --head \
  https://repo.maven.apache.org/maven2/io/github/shanifdhanani/mcp-ga4/0.1.0/mcp-ga4-0.1.0.jar
```

Then resolve the dependency with `mavenCentral()` in a clean consumer using a new Gradle cache. Compile and execute its minimal tracking integration against a local HTTP capture collector. Verify sources/Javadocs and signatures are retrievable too. Maven Local resolution, uploading and Portal acceptance do not satisfy this check.

Only after independent resolution should the README's release status, GitHub release and task card claim Maven Central publication. Record the artifact checksum, test result and public coordinate link. Publish a new version for subsequent fixes.
