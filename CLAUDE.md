# 44-pixels fork

Fork of `twilio/twilio-voice-react-native`. Consumed by Karen via `github:` install.

## Branches & tags

- `main` mirrors `upstream/main`. No patches. FF-only.
- `fork/<upstream-version>` = upstream tag + our patches. One branch per upstream version we ship.
- Tag `<upstream-version>-fork.<N>` on every patch-set change. Consumers pin tags, never branches.

New upstream version → branch off the tag, cherry-pick patches forward, tag:

```bash
git fetch upstream --tags
git checkout -b fork/<new-version> <new-version>
git cherry-pick <prev-version>..fork/<prev-version>
# resolve conflicts — sentinels mark every hook site
git tag <new-version>-fork.1
git push -u origin fork/<new-version> --tags
```

For each conflict: if upstream merged our fix, drop the commit + delete the fork-only file (note upstream SHA in message). Otherwise re-attach the hook in the new shape, and check each fork-only file's `Re-check on SDK bump` header.

Old `fork/<version>` branches stay frozen — never rebase or delete.

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
