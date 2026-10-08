# Third-party notices

claude-code-recorder itself is licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE)).

The package (`claude-code-recorder-<version>.tgz`) carries the recording engine as one JAR, `lib/ccrec-engine.jar`, which bundles the libraries below. Each remains under its own license; the license and notice files the libraries ship are kept inside the JAR under `META-INF/`. The Node part of the package (`bin/`, `src/`) has no dependencies.

The licenses are as each library declares them in its POM on Maven Central, read on 2026-10-08.

| Library | Version | License | Project |
|---|---|---|---|
| `aopalliance:aopalliance` | 1.0 | Public Domain | http://aopalliance.sourceforge.net/ |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.18.7 | Apache-2.0 | https://github.com/FasterXML/jackson |
| `com.fasterxml.jackson.core:jackson-core` | 2.18.7 | Apache-2.0 | https://github.com/FasterXML/jackson |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.7 | Apache-2.0 | https://github.com/FasterXML/jackson |
| `com.github.ben-manes.caffeine:caffeine` | 2.9.3 | Apache-2.0 | https://github.com/ben-manes/caffeine |
| `com.google.errorprone:error_prone_annotations` | 2.47.0 | Apache-2.0 | https://errorprone.info/ |
| `com.google.guava:failureaccess` | 1.0.3 | Apache-2.0 | https://github.com/google/guava |
| `com.google.guava:guava` | 33.6.0-jre | Apache-2.0 | https://github.com/google/guava |
| `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | https://github.com/google/guava |
| `com.google.inject:guice` | 7.0.0 | Apache-2.0 | https://github.com/google/guice |
| `com.google.j2objc:j2objc-annotations` | 3.1 | Apache-2.0 | https://github.com/google/j2objc |
| `com.google.protobuf:protobuf-java` | 4.35.1 | BSD-3-Clause | https://protobuf.dev/ |
| `com.scalar-labs:scalardb` | 3.19.1 | Apache-2.0 | https://github.com/scalar-labs/scalardb |
| `com.zaxxer:HikariCP` | 4.0.3 | Apache-2.0 | https://github.com/brettwooldridge/HikariCP |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 | https://github.com/jakartaee/inject |
| `javax.activation:javax.activation-api` | 1.2.0 | CDDL-1.1 or GPL-2.0 with Classpath Exception | https://github.com/javaee/activation |
| `javax.xml.bind:jaxb-api` | 2.3.1 | CDDL-1.1 or GPL-2.0 with Classpath Exception | https://github.com/javaee/jaxb-spec |
| `org.apache.commons:commons-lang3` | 3.20.0 | Apache-2.0 | https://commons.apache.org/ |
| `org.apache.commons:commons-text` | 1.15.0 | Apache-2.0 | https://commons.apache.org/ |
| `org.checkerframework:checker-qual` | 3.55.1 | MIT | https://checkerframework.org/ |
| `org.jooq:jooq` | 3.14.16 | Apache-2.0 | https://www.jooq.org/ |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 | https://jspecify.dev/ |
| `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause | https://jdbc.postgresql.org/ |
| `org.reactivestreams:reactive-streams` | 1.0.2 | CC0-1.0 | https://www.reactive-streams.org/ |
| `org.slf4j:slf4j-api` | 1.7.36 | MIT | https://www.slf4j.org/ |
| `org.slf4j:slf4j-nop` | 1.7.36 | MIT | https://www.slf4j.org/ |
| `org.xerial:sqlite-jdbc` | 3.53.2.1 | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |

Some of these bundle further code of their own, under the licenses stated in their files inside the JAR:

- `jackson-core` includes FastDoubleParser (MIT) and an implementation of the Schubfach algorithm.
- `postgresql` includes the SCRAM and StringPrep libraries of OnGres (BSD-2-Clause).
- `sqlite-jdbc` includes SQLite itself (public domain) as native libraries, and code derived from the Zentus SQLite JDBC driver (BSD-style).

## Not bundled

The MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`, LGPL-2.1-or-later), which ScalarDB uses for MariaDB and MySQL, is deliberately left out of the JAR. To record into MariaDB or MySQL, download the driver and put its JAR in `~/.ccrec/drivers/`.

The larger JAR made by `npm run build:full` is not distributed by this project. Whoever builds it bundles the clients of every database ScalarDB supports, each under its own license, and is responsible for meeting them.
