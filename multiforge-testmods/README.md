# multiforge-testmods

Fixture mods for MultiForge's bench scenarios. They exist to exercise the
runtime with real mod jars, loaded the way a server loads any mod; they are
not meant for real servers. Each is its own jar, because MultiForge
classifies thread-safety per jar (`docs/legacy-compat.md`).

| Mod | Jar | What it does | What it proves |
|---|---|---|---|
| `mftest_writer` | `writer/` | An entity tagged `mftest_writer`, with `NeoForgeData:{tx,ty,tz}`, sets that block to the next wool colour once a second from its own tick. `/mftest_writer stats` prints `writes` and `false_returns`. | Its listener is unannotated and the mod declares nothing, so it runs on the entity's region worker. With the target in another region, every write is a cross-region `setBlock`: rerouted to the owner, applied, and still returning Vanilla's `true`. |
| `mftest_legacy` | `legacy/` | Counts entity ticks per entity type in a plain `HashMap` and a `long`, and counts calls that arrive off the server thread. `/mftest_legacy stats` prints `ticks`, `off_server_thread`, `consistent`. | It declares `multiforge_safety = "legacy"`, so every listener runs on the serial lane. `off_server_thread` must stay 0 and `consistent` true, as on stock NeoForge. |

Built by `./gradlew :multiforge-testmods:writer:jar :multiforge-testmods:legacy:jar`;
the `:multiforge-bench:scenario` task builds them itself and loads them for
scenario `x4` (`multiforge-bench/src/main/java/net/multiforge/bench/harness/ScenarioRun.java`),
which runs on stock NeoForge and on MultiForge and requires the same outcome.
