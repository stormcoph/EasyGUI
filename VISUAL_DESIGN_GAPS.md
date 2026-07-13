# EasyGUI — Visual Design Gap Analysis

Companion to [WEB_PARITY_GAPS.md](WEB_PARITY_GAPS.md). That document covers technical/architectural gaps (layout systems, virtualization, i18n). This one answers a different question: **what can you literally not create with EasyGUI's rendering primitives that you can on a modern website?**

Grounded in the actual drawing API of `Render2D` and `TrueTypeFont` as of 2026-07-13.

## Already possible — not gaps

Several signature "modern web" looks are already achievable, so the problem is not "it looks like Minecraft":

- **Frosted glass** — backdrop blur (`fillRectBlurred` / `fillRoundedRectBlurred`)
- **Soft drop shadows** — `dropShadow` with configurable size and color
- **Colored glows / neon** — `dropShadow` with a bright color
- **Circular avatars** — `texturedRoundedRect` with radius = half the size
- **Ripples** clipped to rounded silhouettes
- **Whole-tree fades** — `pushAlpha` / `popAlpha` group opacity
- **Arbitrary flat polygons, arcs, polylines**
- **Custom shaders** as an escape hatch for anything GPU-side (`shadedRect` / `shadedRoundedRect`)

## The gaps, ranked by visual impact

### 1. Typography as decoration — *the biggest one*

**Closed (2026-07-13)** — `TextStyle` (a fluent style: color/gradient, tracking, soft or hard shadow, outline/hollow, underline/strikethrough, faux-bold) drives new `TrueTypeFont.draw(..., TextStyle)` and `width(..., TextStyle)` overloads, and `StyledText` lays out per-run styled fragments on a shared baseline. `Label.setStyle` / `Label.setStyledText` wire it into the widget layer; the demo's "Type" tab shows a gradient headline, outlined/hollow text, a soft shadow, underline/strikethrough and a bold-word-in-a-sentence. All shader-free and global-alpha aware.

A modern landing page is 80% typography: huge display text with tight letter-spacing, gradient-filled headlines, soft blurred text shadows, outlined/hollow text, a bold word inside a regular sentence.

EasyGUI text was: one size, one color, optional hard 1px shadow (`TrueTypeFont.draw`). Now a hero section — which *is* styled text — is buildable.

Was missing (now covered): letter-spacing (tracking), gradient fills, soft/blurred text shadows, outline text, mixed weights/styles inline, underline/strikethrough.

### 2. Gradients beyond two stops, vertical or horizontal

`fillRoundedRectGradient` / `fillRoundedRectGradientH` are the only gradient primitives — exactly two colors, axis-aligned.

Missing:

- Diagonal / arbitrary-angle gradients
- 3+ color stops
- Radial gradients (the soft glow behind a hero element)
- Conic gradients (color wheels, fancy rings)

The whole "aurora / mesh gradient background" aesthetic that defines current web design — Stripe, Linear, Vercel — is out of reach as a primitive (only via hand-written shader).

### 3. Rotation

Nothing in the API can rotate, scale, or skew. No tilted cards, no diagonal ribbons, no "-3° polaroid photo" stacks, no rotating loader, no angled section dividers.

CSS `transform: rotate()` is one line on the web; here it doesn't exist at all.

**Likely the cheapest gap to close:** `GuiGraphics` pose transforms already exist under the hood — they just need exposing, and the feathered geometry (and scissor handling) made to respect them.

### 4. Curves and organic shapes

Available: lines, arcs, polygons — all straight edges or perfect circles. No bézier curves means:

- No blob shapes
- No wavy section dividers
- No squircles
- No smoothly-curving connector lines
- Chart lines are angular polylines instead of smooth splines

Everything drawable is geometric; nothing can be *organic*.

### 5. Masking beyond rectangles

Clipping is rect-only scissor (`pushScissor`). Classic web tricks it blocks:

- Content that fades out at the edge of a scroll area (`mask-image: linear-gradient`)
- Text or gradients clipped to a shape
- An image masked by a PNG silhouette

Rounded-rect images work, but masking *composite* content to any shape doesn't.

### 6. Decorative borders

Strokes are solid only. Missing:

- Dashed/dotted borders (upload dropzones)
- Animated dashes ("marching ants")
- Gradient borders (the glowing gradient ring around a card/avatar, currently everywhere on the web)

## Where to start for maximum payoff

**#1 and #2 together** (styled typography + rich gradients) are what make something read as "designed in 2026" vs. "a clean settings menu." **#1 is now done** (see above), so **#2 (rich gradients)** is the next-highest-payoff piece.

**#3 (rotation)** is probably the cheapest win since the pose-transform machinery already exists in `GuiGraphics`.
