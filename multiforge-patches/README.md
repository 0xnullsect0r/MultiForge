# multiforge-patches

Git-format patches applied to a vendored NeoForge 1.21.1 tree at build
time. NeoForge itself uses the same approach against Vanilla Minecraft
via [NeoForm](https://github.com/neoforged/NeoForm); this module layers
on top.

## Patch groups

Patches are grouped by concern so upstream rebases can be scoped. Each
group is a numbered directory of `.patch` files:

| Group              | Lands in milestone | Scope                                                                     |
|--------------------|--------------------|---------------------------------------------------------------------------|
| `01-ownership/`    | M0                 | `TickThread`-equivalent + `ensureTickThread` guards at ~150 NMS sites.    |
| `02-region-tick/`  | M2                 | `MinecraftServer.runServer` rewrite; `RegionizedServer` bootstrap.        |
| `03-world-data/`   | M2                 | `RegionizedData<T>` slots on `ServerLevel`.                               |
| `04-chunk-system/` | M3                 | Moonrise-equivalent chunk holder / ticket / task scheduler rewrite.       |
| `05-entity-migration/` | M4             | Two-phase remove+add cross-region teleport; `Entity#teleportAsync`.       |
| `06-networking/`   | M5                 | `ServerGamePacketListenerImpl` region hop; `IPayloadContext#enqueueWork`. |
| `07-persistence/`  | M6                 | Per-region autosave + WAL journal.                                        |
| `08-globals/`      | M5                 | Dedicated global-region tick for weather/time/border/dragon/wither/raids. |
| `09-events/`       | M1                 | NeoForge `IEventBus.post` dispatch-domain routing.                        |

Only groups whose milestone has landed contain patches. Empty groups keep
a `.gitkeep` so the numbering stays stable.

## Vendoring NeoForge

The patches apply against a checkout at `upstream/neoforge-1.21.1/`. The
`:setup` task in the root build (added when this module is enabled)
handles it:

```
./gradlew :setup -Pmc=true
```

Under the hood `:setup` does the equivalent of NeoForge's contributor
workflow:

1. Clone `github.com/neoforged/NeoForge` at tag `1.21.1-21.1.90` into
   `upstream/neoforge-1.21.1/`.
2. Run NeoForge's own `./gradlew :createPatchWorkspace` so a
   patched-vanilla `projects/neoforge/` tree exists.
3. Apply every `.patch` under `multiforge-patches/*/` onto that tree in
   numeric order.

The vendored tree is `.gitignore`d — MultiForge only stores its patches,
not the NeoForge source itself.

## Adding a new patch

1. Make sure `-Pmc=true` is set and `:setup` has run.
2. Edit files under `upstream/neoforge-1.21.1/projects/neoforge/`.
3. Run `./gradlew :createPatches` (delegates to NeoForge's own task,
   then splits the output between MultiForge's groups by looking at the
   patch header).
4. `git add` the new `.patch` files under the appropriate group.
5. In the PR description, name the concurrency contract the patch
   defends and the assertion sites it adds.

## Applying patches from a fresh checkout

```
git clone git@github.com:multiforge/multiforge.git
cd multiforge
./gradlew :setup -Pmc=true       # ~15 min, ~15 GB disk
./gradlew build -Pmc=true         # full patched build
```

## Upstream drift

Bumping the pinned NeoForge tag:

```
./gradlew :bumpNeoForge -PneoForgeVersion=21.1.95 -Pmc=true
```

That task:

1. Fetches the new tag into a scratch worktree.
2. Applies each MultiForge patch group in order; conflicts are surfaced
   with the group name.
3. Regenerates patches from the resolved workspace.
4. Runs deterministic-mode regression to assert no behavior drift.

If a patch group cleanly rebases, the diff is contained to that group's
directory and the M-milestone owner reviews it. Non-clean rebases must
be resolved by a human before merging.
