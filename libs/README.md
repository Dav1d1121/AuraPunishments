# libs/

Place the supplied plugin jars here **locally** (they are git-ignored and must never be committed to a public repository):

| File | Used for |
|---|---|
| `JailReloaded-1.2.2.jar` | compile-only (adapter) and tests |
| `AuraCommandFilter-1.0.0.jar` | live tests only |
| `CoreProtect-25.1.jar` | compile-only (read-only evidence integration) and live tests |
| `TownyChat-0.122.jar` | live tests only |

The plugin jar produced by the build never bundles any of these.
