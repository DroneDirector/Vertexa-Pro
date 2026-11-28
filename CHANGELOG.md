# Changelog

## Next

- Hidden parts can always be shown again: a bar at the bottom lists the hidden parts — tap one to bring it back, or Show all

## 1.0.16

- New Brush tab in the cut menu: paint a selection polygon by polygon or vertex by vertex, and erase it off again with the same brush (adjustable size, magnifier, X-ray to reach hidden polygons)
- An outline you've drawn turns into a brush selection when you pick the brush, so you can paint more on or erase bits off before creating the part
- Create a part straight from the brush selection — it stays in place
- Combine parts: pick "Combine…" on a part and tap the other one; if they touch, the app asks whether to connect them (removes the seal between them and welds the seam into one mesh) or only combine them
- Undo works for combine and connect too

## 1.0.15

- Cut tools now live in a menu at the top centre that only appears while cutting, grouped in tabs: Draw · Smart · Refine · View
- AI select: tap a flap, door or panel and on-device AI (MobileSAM) outlines it; add or remove areas with more taps. One-time 45 MB download, then fully offline
- Magic wand: tap inside a painted panel and the outline follows its panel lines (adjustable tolerance)
- Snap to lines: taps and dragged points jump onto painted panel lines and edges
- Fit to edges: moves every outline point onto the nearest line in one go
- Copy to other side: mirrors the current or last outline to the other wing or side of the model
- Erase brush: paint over areas the cut must leave alone (adjustable brush size, shown in red, undo/redo)
- Magnifier: a small ~3 cm window at the top shows exactly where the point under your finger is while dragging or erasing (adjustable zoom)
- "Cut lines only" view hides handles and the orange preview to check the line itself

## 1.0.14

- Edit the cut outline: tap a point to select it (and delete it), drag points to fine-tune them, tap on a line to add a point there
- Rectangles become 4 draggable corners; ovals and freehand shapes become smooth curves through a few draggable points
- Depth range for cuts: a two-handle slider limits how deep the cut reaches front-to-back, so things underneath aren't hit
- Live orange preview of exactly what the cut will take while you adjust the outline or the depth
- View presets (Top, Bottom, Front, Back, Left, Right) and a Flat view without perspective — also as quick buttons in the cut tool
- New cut mode "Through the first object" (now the default): cuts the nearest object top and bottom, never what's behind it
- Flap template: tap the trailing edge where the flap starts and ends, set the chord depth, and the outline is built for you
- Hinge: set a hinge line on any part and swing it with a slider to check the cut; the hinge becomes the part's pivot
- Gap: leave an adjustable gap (0–30 mm) between a cut part and the model, both sides sealed
- Mirror cut finds the model's mirror axis automatically
- Save as GLB: every part its own object with its hinge (or centre) as pivot, textures embedded — ready for Blender, Unreal and MSFS tools
- Right-side controls regrouped into Tools · View · Save, and they scroll on short screens
- Undo and Redo while drawing the cut outline (every point or drawn shape, before you create the part)
- Redo for a cut you've undone
- Opening the Parts panel on a model that is one single mesh splits it into its loose pieces automatically

## Cut tool, measure, animation and saved views

- Cut tool (scissors on the right): tap points on the model around an area; near the start point you're asked whether to connect the last point with the first. Then "Create separate part" — the new part stays exactly in place
- Three cut modes: Cut through (whole thickness — flaps, ailerons, rudders), One wall (outer + inner skin only, never the far side — doors, hatches) and Surface (one skin)
- Cuts follow the drawn line exactly, however dense the mesh; the open edges on the new part and on the model are sealed automatically so nothing is see-through
- Cutting never deletes anything: the cut-out piece becomes its own part, in place, next to the rest of the model
- Cut shapes: Points, smooth Curve (taps joined by a smooth curve), Freehand (draw with one finger), Rectangle and Ellipse — two fingers still move the view while drawing
- Mirror cut: cut the matching part on the other side at the same time (mirror across X, Y or Z)
- Snap to corners & edges, and Straight 45° lines while placing points
- Undo last cut (a mirror cut undoes both sides), rename parts; cut parts stay separate objects when saved as OBJ
- Convert & save now saves the edited model, and STL can be saved as one file per part
- Measure tool: tap two points for the distance plus X / Y / Z, with a choice of model unit (m, cm, mm, in, ft) and the overall model size
- Animation playback for animated FBX / glTF / DAE models: play/pause, scrub, choose clip, speed 0.25×–2×; node and skeletal animation
- Saved views: bookmark camera angles per model; the last view of every model is remembered and restored

## Parts, lighting, capture and convert & save

- Parts list (right side): every object/group in the model is a part you can show, hide, isolate or zoom to
- Tap a part in the 3D view to select and highlight it; tapping empty space deselects, dragging still orbits
- "Split into loose parts" turns every unconnected piece of an OBJ into its own part (great for single-mesh Meshy models)
- Studio lighting presets: Studio, Soft box, Dramatic, Outdoor, Rim light, Flat
- Point cloud controls: point size, original / single / height colours, round points
- New "Wireframe" mode that shows only the wireframe with hidden edges removed ("See-through" keeps every edge)
- Snapshot: save the current view as a PNG, optionally with a transparent background
- Turntable: record a 6-second, 1080×1080 MP4 of the model spinning one full turn
- Convert & save: write the model as OBJ (+ MTL + textures, parts kept as separate objects in place) or STL, optionally only visible parts
- Extract archive: save the original files from a ZIP into a folder
- The file name bar at the top of the viewer is gone; details are under Info

## ZIP archives

- Models inside ZIP archives now open directly — including Meshy downloads, which are ZIP files even when named .obj
- The archive is unpacked automatically, the best model inside is picked, and its MTL and textures are loaded with it
- ZIP files appear in the folder browser and get thumbnails too
- The load report says when a file was really an archive and what was inside

## Load report, model browser and thumbnails

- New load report (Info → Load report, or Details when a file fails): shows how the file was read — vertex/face counts, materials, textures found or missing and why, skipped faces with example lines, unrecognised line types, importer warnings — with Copy and Share
- Warning dot on the info button when something unusual was found while loading
- OBJ reading is more forgiving: UTF-16 text files, old Mac line endings, decimal commas, and faces that refer to vertices further down the file now load
- New model browser for folders and multi-file picks: sort by name, date, size or type (tap again to reverse), search, filter by type, grid or list view
- Thumbnails for every model: rendered in the background for models up to 250 MB, and captured from the viewer for every model you open
- Saved folders on the home screen, thumbnails in Recent

## First release

- Open OBJ (with MTL), FBX, glTF, GLB, STL, PLY, DAE, 3DS, 3MF, BLEND, DXF, OFF, LWO, X, AC and MS3D models
- Streaming loaders for OBJ, STL and PLY: multi-gigabyte files are read in blocks and appear on screen while loading
- View modes: Textured, Solid, Wireframe, Solid + Wire, Textured + Wire
- Textures found automatically when picked together with the model or opened from a folder; "Link folder" for missing textures
- Orbit, pinch zoom, two-finger pan, fling, double-tap / button to recentre and fit the whole model in view
- Auto-rotate, floor grid, Y-up / Z-up switch
- Dark, Gray and Light themes
- Animated splash screen
- In-app updater with warp animation
