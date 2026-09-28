# multiforge-testmods

Fixture mods for MultiForge's bench scenarios. They exist to exercise the
runtime with real mod jars, loaded the way a server loads any mod; they are
not meant for real servers. Each is its own jar, because MultiForge
classifies thread-safety per jar (`docs/legacy-compat.md`).

| Mod | Jar | What it does | What it proves |
|---|---|---|---|
| `mftest_writer` | `writer/` | An entity tagged `mftest_writer`, with `NeoForgeData:{tx,ty,tz}`, sets that block to the next wool colour once a second from its own tick. `/mftest_writer stats` prints `writes` and `false_returns`. | Its listener is unannotated and the mod declares nothing, so it runs on the entity's region worker. With the target in another region, every write is a cross-region `setBlock`: rerouted to the owner, applied, and still returning Vanilla's `true`. |
| `mftest_legacy` | `legacy/` | Counts entity ticks per entity type in a plain `HashMap` and a `long`, and counts calls that arrive off the server thread. `/mftest_legacy stats` prints `ticks`, `off_server_thread`, `consistent`. | It declares `multiforge_safety = "legacy"`, so every listener runs on the serial lane. `off_server_thread` must stay 0 and `consistent` true, as on stock NeoForge. |
| `mftest_slowtick` | `slowtick/` | `/mftest_slowtick run <ticks> <ms>` makes each of the next `ticks` server ticks sleep `ms` on the server thread; `/mftest_slowtick stats` prints `remaining`, `slow_ticks`, `slept_ms`. | The watchdog: 120 ticks of 1 s must not stop the server, a single 70 s tick must. |
| `mftest_limbo` | `limbo/` | An entity tagged `mftest_limbo` forces and unforces chunk `NeoForgeData:{fx,fz}` every 7 ticks, and/or loads the next chunk of the 16x16-chunk area at `{lx,lz}` every `every` ticks (default 2), from its own tick. `/mftest_limbo stats` prints `toggles`, `loads`, `load_ms`. | On a region worker: ticket changes are deferred to the server thread, and each load is handed to the server thread, which pumps chunk promotions and demotions while other regions move their entities, the race that left entities in limbo before v1.11. |

Built by `./gradlew :multiforge-testmods:writer:jar :multiforge-testmods:legacy:jar`;
the `:multiforge-bench:scenario` task builds them itself and loads them for
scenario `x4` (`multiforge-bench/src/main/java/net/multiforge/bench/harness/ScenarioRun.java`),
which runs on stock NeoForge and on MultiForge and requires the same outcome.
`mftest_slowtick` and `mftest_limbo` are loaded by `:multiforge-bench:stress`
(`StressRun.java`), which runs on MultiForge only.
