The local English voice-input wording changes in the upstream translations
submodule are preserved in translations-system-voice-input.patch.
To restore them after initializing submodules, run from the repository root:

```sh
git -C translations apply --unidiff-zero ../patches/translations-system-voice-input.patch
```

These English submodule resources are excluded by translationsWithoutEngValues
in build.gradle; this patch preserves the local edits without requiring an
unpublished upstream submodule commit.
