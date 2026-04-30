# 44-pixels fork

Fork of `twilio/twilio-voice-react-native`. Consumed by Karen via `github:` install.

## Branches & tags

- `main` mirrors `upstream/main`. No patches. FF-only.
- `fork/<upstream-version>` = upstream tag + our patches. One branch per upstream version we ship.
- Tag `<upstream-version>-fork.<N>` on every patch-set change. Consumers pin tags, never branches.

New upstream version → branch off the tag, cherry-pick patches forward, tag.

## Patch rules

Keep upstream-file edits **minimal**: one-line hook, real logic in a fork-only file. If a patch can't be a one-line hook, extract more.

Wrap every upstream-file edit in sentinels:

```
// >>> FORK KAR-xxx — see <fork-only-file>
<one-line hook>
// <<< FORK
```

Fork-only files sit next to upstream files. Header:

```
// FORK — KAR-xxx
// Owns: <what>. Hooks into: <upstream files>.
// Re-check on SDK bump: <what to verify>.
```

ObjC methods introduced by the fork: `fork_` prefix. Java helpers: plain class names.

When upstream fixes a patch, drop the commit (sentinels + fork file together) and note the upstream SHA.

## Remotes

- `origin` → `44-pixels/twilio-voice-react-native`
- `upstream` → `twilio/twilio-voice-react-native`
