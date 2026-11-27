# Changelog

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
