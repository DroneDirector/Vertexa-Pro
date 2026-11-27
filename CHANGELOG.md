# Changelog

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
