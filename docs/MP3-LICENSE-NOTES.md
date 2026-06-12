# MP3 support — license check (JLayer)

Status: **APPROVED — bundle JLayer 1.0.1 via jar-in-jar** (decision rationale below).

## The library

| | |
|---|---|
| Library | JLayer (pure-Java MPEG 1/2/2.5 Layer I/II/III audio decoder) |
| Coordinates | `javazoom:jlayer:1.0.1` (Maven Central) |
| Author | JavaZoom |
| License | GNU Lesser General Public License, version 2.1 (LGPL-2.1)¹ |
| Size | ~105 KB, zero transitive dependencies, pure Java |

¹ JavaZoom distributes JLayer under the LGPL; some source headers carry the older
"GNU Library General Public License v2 *or (at your option) any later version*"
wording, which permits use under LGPL-2.1 terms either way. We treat it as
LGPL-2.1 throughout.

## Compatibility with EasyGUI's license

EasyGUI is licensed **PolyForm Noncommercial 1.0.0**. That is not a GPL-family
license, so the question is whether an LGPL library may be *combined with* a
differently-licensed work. It may, provided the LGPL terms are honored for the
library itself:

1. **The LGPL applies only to JLayer, not to EasyGUI.** The LGPL (unlike the
   GPL) explicitly allows a "work that uses the Library" to be distributed
   under the distributor's own terms (LGPL-2.1 §6), as long as the conditions
   below are met. EasyGUI's own code stays PolyForm Noncommercial; the bundled
   `jlayer-1.0.1.jar` stays LGPL-2.1.
2. **The library must remain replaceable** (LGPL-2.1 §6(b): "use a suitable
   shared library mechanism for linking"). **Jar-in-jar satisfies this
   cleanly**: JLayer ships as a *separate, unmodified, byte-identical Maven
   Central artifact* nested inside the mod jar (`META-INF/jars/` on Fabric,
   `META-INF/jarjar/` on NeoForge). Anyone can open the mod jar, swap the
   nested `jlayer-1.0.1.jar` for a modified build, and the mod will load it —
   no relinking or recompiling of EasyGUI is required. Shading/relocating the
   classes into our own jar would have muddied this; we deliberately do NOT
   shade it.
3. **No added restrictions on the library.** EasyGUI's noncommercial terms
   attach to EasyGUI's code only. Nothing restricts users from extracting,
   modifying, reverse-engineering for debugging, or redistributing the nested
   JLayer jar under its own LGPL terms.
4. **Attribution + license text must ship** (LGPL-2.1 §6: "give prominent
   notice ... that the Library is used" and supply a copy of the license).
   See the checklist below.

Conclusion: **compatible**. This is the standard pattern used across the
modding ecosystem for LGPL libraries (jar-in-jar / separate-artifact bundling).

## Compliance checklist (what every distribution must carry)

- [x] JLayer is bundled **unmodified** as a separate nested artifact
      (`include "javazoom:jlayer:1.0.1"` in `fabric/build.gradle` and
      `neoforge/build.gradle` — loom nests the original Maven Central jar).
- [x] **Prominent notice** that JLayer is used: this file, plus the
      `Mp3Decoder` class javadoc names JLayer, JavaZoom and the LGPL-2.1.
- [ ] **Full LGPL-2.1 text must accompany the distribution.** Follow-up for
      the repo owner: add the verbatim license text at
      `docs/licenses/LGPL-2.1.txt` (canonical source:
      <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt>) and reference
      JLayer in the README's credits/third-party section. The nested jar
      itself is not guaranteed to embed the text, so we must ship it.
- [x] **Replaceability**: the nested jar can be swapped without touching
      EasyGUI code (see §2 above).
- [x] **Graceful degradation**: `Mp3Decoder` soft-depends on JLayer. If
      someone strips the nested jar, the first MP3 decode attempt throws a
      clear `UnsupportedOperationException("MP3 support requires the bundled
      JLayer library")` instead of `NoClassDefFoundError` poisoning unrelated
      code; everything else in EasyGUI keeps working. This also keeps the
      "library is genuinely separable" story honest.

## Attribution

> MP3 decoding is provided by **JLayer 1.0.1** © JavaZoom
> (<http://www.javazoom.net/javalayer/javalayer.html>), used unmodified under
> the GNU Lesser General Public License v2.1. JLayer is and remains licensed
> solely under the LGPL; EasyGUI's PolyForm Noncommercial license does not
> apply to it.

## Decision

Bundle `javazoom:jlayer:1.0.1` jar-in-jar on both loaders:

- `common/build.gradle` — `implementation` only (common compiles against it;
  the common module never ships standalone).
- `fabric/build.gradle` — `implementation` (dev runtime) + `include`
  (nested into the released jar, listed in `fabric.mod.json` `"jars"`).
- `neoforge/build.gradle` — `forgeRuntimeLibrary` (visible to FML's
  classloader in dev runs) + `include` (Architectury Loom 1.7 generates
  NeoForge JarJar metadata at `META-INF/jarjar/metadata.json`).

Alternatives considered and rejected: shading (weakens LGPL §6
replaceability), making MP3 an optional runtime download (network access from
a GUI library is unacceptable), and a clean-room pure-Java decoder (far too
much work for an optional format). JLayer adds ~105 KB, no natives, no
transitive deps — well within the "tiny pure-Java decoder" budget from
TODO.md.
