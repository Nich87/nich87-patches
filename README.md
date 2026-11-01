# Nich87 Patches

Custom patches by Nich87 for use with [Morphe](https://morphe.software).
This is an independent project based on the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).

The repository currently contains the template's development example. It does
not yet provide patches for a real app.

## Using the patches

After the first release, add this source in Morphe Manager:
<https://github.com/Nich87/nich87-patches>.

<a href="https://morphe.software/add-source?github=Nich87/nich87-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Enable pre-releases for development builds from `dev`.

## Patches

<!-- PATCHES_START EXPANDED -->

The patch list will appear here after the first release.

<!-- PATCHES_END -->

## Development

Use JDK 21 and Android SDK Platform 36. Authenticate GitHub CLI with the
`read:packages` scope, then build on `dev`:

```sh
gh auth refresh --hostname github.com --scopes read:packages
./scripts/gradle.sh buildAndroid
```

The Android bundle is written to `patches/build/libs/patches-*.mpp`.
The local launcher reads credentials from GitHub CLI and uses a separate
temporary Gradle cache. It does not store the token in this repository.

See [DEVELOPMENT.md](DEVELOPMENT.md) for setup, validation and release rules,
and [CONTRIBUTING.md](CONTRIBUTING.md) for contributions. Coding agents must
also read [AGENTS.md](AGENTS.md) and the repository's development skill.

## License

Licensed under [GPLv3](LICENSE), with the name restrictions in [NOTICE](NOTICE).
