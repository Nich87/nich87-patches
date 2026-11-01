# Development

## Prerequisites

- JDK 21 for Gradle, matching the upstream release workflow.
- Android SDK Platform 36 and Android SDK Build Tools 36.0.0. The Morphe
  extension plugin currently selects `compileSdk = 36`.
- Git, GitHub CLI, and a GitHub account with access to GitHub Packages.
- Optional: Android Studio or IntelliJ IDEA with Kotlin support, jadx,
  apktool, and ADB for work on actual applications.
- Node.js and npm are needed for release automation, not a local `.mpp`
  build. GitHub Actions installs them in the existing workflow.

Set `ANDROID_HOME` to your SDK installation. For example, this workstation
uses `/opt/android-sdk`. Alternatively, set `sdk.dir` in ignored
`local.properties`. Do not commit a machine-specific SDK path.

## Authentication and building

Public packages on GitHub Packages also require authentication:

```sh
gh auth login --hostname github.com --scopes read:packages
# For an already authenticated account:
gh auth refresh --hostname github.com --scopes read:packages

git switch dev
./scripts/gradle.sh buildAndroid
```

`scripts/gradle.sh` forwards Gradle arguments and reads the token directly
from GitHub CLI. Existing `GITHUB_ACTOR` and `GITHUB_TOKEN` environment values
take precedence. The token is not written to a project file or passed as a
command-line property.

The default Gradle cache is `${TMPDIR:-/tmp}/nich87-patches-gradle-${UID}`.
It avoids the workstation's nearly full home filesystem and old credentials
in `~/.gradle/gradle.properties`. Temporary caches may disappear after a reboot;
Gradle will download dependencies again. Set `GRADLE_USER_HOME` to a private,
persistent directory on a filesystem with enough space if preferred. Any
`gpr.user` or `gpr.key` properties there must be current because the upstream
plugin reads those properties before environment credentials.

The underlying upstream command is still `./gradlew buildAndroid`. It works
when the Gradle user home has valid `gpr.user` / `gpr.key` properties or when
`GITHUB_ACTOR` / `GITHUB_TOKEN` are set without conflicting Gradle properties.
Use the wrapper rather than the system `gradle` installation.

The bundle is `patches/build/libs/patches-<version>.mpp`. A successful
`buildAndroid` adds `classes.dex` for Android; an ordinary JVM build alone
does not prove the Android bundle was produced.

## Loading and testing patches

Download the official Morphe Desktop release into ignored `.local/tools/`
or use an existing installation. Check its `--help` for the current listing
and patching commands. For this setup, the reference release is `v1.20.0`:

```sh
mkdir -p .local/tools
gh release download v1.20.0 --repo MorpheApp/morphe-desktop \
  --pattern 'morphe-desktop-1.20.0-all.jar' --dir .local/tools
java -jar .local/tools/morphe-desktop-1.20.0-all.jar --help
java -jar .local/tools/morphe-desktop-1.20.0-all.jar list-patches \
  --patches patches/build/libs/patches-1.0.0.mpp \
  --with-packages --with-versions
```

The example filename above is the initial local build version, not a published
release. Select the actual `.mpp` filename after subsequent releases.
Desktop `v1.20.0` and this template both use Patcher `2.1.0`. Use a compatible
Desktop/Patcher version when upgrading `gradle/libs.versions.toml`.
Load the local `.mpp` before testing it on a
target APK. Do not run `generatePatchesList` merely to validate loading:
that task replaces the release-managed `patches-list.json`.

The template's `Example Patch` uses a fictional app and fingerprints. Decide
the real app, version, APK file type and intended behavior before replacing
this example. A successful build or patch listing is not an APK or device test.
Keep all test APKs and local validation evidence out of Git.

## Release workflow

Develop on `dev` and use semantic commits. Prefer `feat:`, `fix:` and
`chore:`; only `feat:` and `fix:` among those produce a release. `dev`
publishes pre-releases, while `main` publishes stable releases. Users must
enable pre-releases in their Morphe Manager source to use development builds.

Merge `dev` into `main` without squashing or rebasing. The upstream release
automation also backmerges `main` into `dev`. Keep the long-lived branches.

The GitHub repository must allow Actions to create and approve pull requests.
The release job declares its own write permissions; the repository's default
workflow token can remain read-only. Merge commits are enabled and squash /
rebase merges are disabled to match the upstream release model.

GitHub template generation with all branches can create `main` and `dev`
with separate root commits. Check `git merge-base origin/main origin/dev`
before the first release merge. If there is no common ancestor, join those
histories once with an explicitly requested, non-squash merge; preserve both
histories rather than replacing or force-pushing a branch.

Use the existing `release.yml` and `.releaserc`. Generated metadata,
CHANGELOG, release versions and the README patch list belong to that workflow.
Do not create manual release tags or upload `.mpp` files as releases.

Release signing is optional and has not been enabled by this setup. When
requested, use the upstream `generateSigningKey` task, commit only
`morphe-signing.pub`, and put the private key in the repository's
`MORPHE_SIGNING_KEY` Actions secret. Retain a private backup.

## Agent instructions

Read `AGENTS.md` and `.agents/skills/nich87-patches-dev/SKILL.md` when working
here. Codex discovers the repository skill when this repository is the open
workspace; the parent `GitHub` folder is not itself this repository.

## Upstream references

This setup started from template commit
`f4da72f66e008c5bb021a9f19b33dcf5f0523772` on 2026-11-01. Re-check upstream
instructions when changing dependencies or release behavior.

- [Template setup and release rules](https://github.com/MorpheApp/morphe-patches-template/blob/f4da72f66e008c5bb021a9f19b33dcf5f0523772/README.md)
- [Naming restrictions](https://github.com/MorpheApp/morphe-patches-template/blob/f4da72f66e008c5bb021a9f19b33dcf5f0523772/NOTICE)
- [Patcher setup and Packages authentication](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md)
- [Patches and fingerprints](https://github.com/MorpheApp/morphe-patcher/tree/main/docs)
- [Morphe development environment](https://github.com/MorpheApp/morphe-documentation/tree/main/docs/morphe-development)
- [Codex repository skill discovery](https://developers.openai.com/codex/skills/)
