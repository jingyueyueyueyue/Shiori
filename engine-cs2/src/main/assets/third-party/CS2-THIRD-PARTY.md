# Third-party reference boundaries

## KatzeSystem2

KatzeSystem2 is a research reference at
`../resources/reference/KatzeSystem2`. It is not a linked runtime dependency.
Its source is not copied into the independent CS2 crates.

- Upstream: https://github.com/Alphaly2K/KatzeSystem2
- Commit: `3184165d03ad9eac0bb6eba3792c4f2f49e07166`
- License: **LGPL-2.1-only**. This reference is not covered by the MIT license
  of the independent CS2 crates.
- Original license: `../resources/reference/KatzeSystem2/LICENSE`
- Dependency notices: `../resources/reference/KatzeSystem2/THIRD_PARTY.md` and
  its `licenses/` directory.

The upstream source, copyright notices and license files remain intact in the
reference checkout. Runtime implementation uses documented formats, public
interface contracts and independently recovered behavior. Do not copy or link
LGPL reference code into the simulator. A future change to that boundary needs
an explicit project decision and updated distribution materials.

Reference tools and original game EXEs, DLLs, installers and authoring tools
are not runtime dependencies or simulator distribution contents.

## Portable JavaScript interpreter

The independently implemented type55 host uses the Boa JavaScript interpreter
as a Rust dependency, pinned to `boa_engine` 0.21.0. The integration selects
Boa's MIT licensing option. Upstream source commit is
`ad8739f5e0b51d20faf7a2cce98afa5c40121438`, and the engine crate checksum is
`e637ec52ea66d76b0ca86180c259d6c7bb6e6a6e14b2f36b85099306d8b00cc3`.
Its retained notice is `crates/runtime/src/javascript_LICENSE-MIT.txt`.
Companion package versions and checksums are fixed in `Cargo.lock`.

The deferred Android packaging flow includes this notice in application
assets alongside the media notices. The host bridge, Cs* object adapters and
game API implementation are independently authored; no original game V8
binary or host DLL is loaded. Dependency locking has occurred without
compiling or executing the interpreter.

## OpenType font parser

The renderer uses ttf-parser 0.21.1 under the MIT license. The vendored
source comes from the crate published from commit
`423f97dc246189467c3d8d1326bd92416fef4a85`, with registry checksum
`2c591d83f69777866b9126b24c6dd9a18351f177e49d625920d19f989fd31cf8`.
The local change closes the final CFF2 contour once a glyph's charstring
finishes. The original license texts are in `vendor/ttf-parser-0.21.1/`.
Android packages carry the MIT notice.

## Font rasterizer

The renderer uses fontdue 0.9.3 under the MIT license. The local vendor
directory retains the published crate source from commit
`1f1f6ddbe3c9f918952df707763e60d0bb2ce197` with registry checksum
`2e57e16b3fe8ff4364c0661fdaac543fb38b29ea9bc9c2f45612d90adf931d2b`.
The local change adds opt-in delayed outline compilation using the existing
glyph geometry and rasterization. The original notices are in
`vendor/fontdue-0.9.3/`. Android packages carry the MIT notice.

## Lewton

The Vorbis decoder uses Lewton 0.10.2 under the MIT or Apache-2.0 license.
The local copy checks the packet mode index in the sample-count estimator.
The original license is in `vendor/lewton-0.10.2/LICENSE` and is included
in Android packages.

## wgpu hardware abstraction layer

The Vulkan and DX12 backends use wgpu-hal 27.0.4 under MIT or Apache-2.0.
The local copy comes from commit `af91efa93da2af9366b252ed7b9a089175f4874e`
with registry checksum
`5b21cb61c57ee198bc4aff71aeadff4cbb80b927beb912506af9c780d64313ce`.
On Android Adreno, the encoder recycles its Rust state and retires each Vulkan
command pool once its buffers are idle.
Both original licenses are retained under `vendor/wgpu-hal-27.0.4/` and
included in Android packages.

## Other fixed research references

The commit, origin and hash of retained license files for all eight snapshots
are recorded in `../resources/reference/manifest.json`. TriggersTools.CatSystem2
is MIT licensed (`License.md`, Copyright 2019 Robert Jordan); catsystem-py is
MIT licensed (`LICENSE`). Their code has not been adopted by this increment.
FelineSystem2, CatSceneEditor, FuckGalEngine, chinesize and the simple translation
tools are used as structure/behavior references where a code license has not
been established. They are not linked, copied into the runtime, or distributed
with the player.

## Android FFmpeg media backend

Android movie decoding dynamically loads the independent MIT adapter
`android/app/src/main/cpp/cs2_media.c` and FFmpeg shared libraries. The fixed
source is FFmpeg 8.0.2, SHA-256
`5d16962332603c427b3d0887fc12b9166d6ee2cb1108b1865dd2d5eb06a09505`.
The build disables GPL, nonfree and version-3 components; the selected FFmpeg
configuration is LGPL-2.1-or-later and is separate from the simulator's MIT code.
No FFmpeg implementation is copied into the Rust crates or adapter.

`scripts/build/prepare_android_ffmpeg.py` retains the exact source archive,
license, release, public adapter and complete configure/link commands.
The later `android_workspace.py build` packages the original shared libraries
and license notices and emits a matching media-source ZIP beside the APK.
The source package and rebuild instructions accompany distribution so the
libraries can be modified and replaced. Game data and original game code do
not belong in either artifact. Neither these libraries nor the APK has been
compiled during the implementation phase.

FreeMote is additionally pinned for PSB/PSZ format research. Its top-level
license is CC-BY-NC-SA-4.0; a file comment mentioning an MIT predecessor does
not relicense the entire repository. No FreeMote code, SDK or native runtime
is included. Independent E-mote code must retain this reference boundary.
