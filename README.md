# SmoothCam

A small camera app for Pixel phones. Video mode targets the things that make iPhone video feel smooth:

| Problem on typical Android camera apps | What SmoothCam does |
|---|---|
| Preview runs at 30 fps, or the frame rate drifts in dim light | Requests a fixed 60 fps sensor range (e.g. [60, 60]) for both preview and recording |
| Viewfinder stutters or blacks out when you press record | One capture session with a persistent encoder surface; recording just adds an output |
| 60 fps content on a 90 Hz screen gives uneven frame times | Asks for a 120 Hz display mode and tags the preview as fixed-rate 60 fps content |
| Very short shutter speeds in daylight look choppy | **180° lock** button: re-meters and holds exposure at 1/120 s (natural motion blur) |
| Preview looks different from the stabilized recording | Uses preview stabilization when the camera supports it |
| Flat, dim-looking video next to iPhone's default HDR | Records 10-bit HLG HDR (HEVC Main10, BT.2020) by default, with the HDR preview using the screen's extra brightness |
| Fixed shutter means frozen exposure | On Android 16, uses shutter-priority auto-exposure: shutter stays at 1/120 s while ISO keeps adapting |

The status bar shows the **measured** frame rate (from sensor timestamps), shutter speed, shutter angle and ISO, so you can see what's happening.

## Photo mode

Photo mode aims for the iPhone *look* (bright, open shadows, clean detail, slightly warm color) but saves a **plain SDR JPEG with no HDR layer**. So the photo looks the same in the gallery as the moment you took it; it never "brightens up" with blown-out highlights a second later.

- **Zero shutter lag:** the camera keeps the last few full-resolution frames in memory, so the photo is the moment you tapped.
- **Multi-frame merge:** the 4 newest frames are aligned and averaged for cleaner shadows. Pixels that moved come only from the newest frame, so edges stay sharp.
- **iPhone-style tone:** shadows are lifted locally and midtones brightened slightly. Highlights roll off softly instead of clipping.
- **Color and detail:** slightly warm white balance, gentle vibrance that leaves skin tones natural, and fine-detail sharpening that skips noise.

Photos are about 12 MP and saved to `Pictures/SmoothCam`.

**Full resolution (50 MP):** if the camera lets apps use its full-resolution sensor mode, a **50 MP** button (showing the actual megapixels) appears at the top left in photo mode. Many phone makers keep this mode for their own camera app, so **if the button doesn't appear, your Pixel doesn't offer it to other apps**. In 50 MP mode each photo is a single frame (no zero-lag buffer or multi-frame merge), the viewfinder pauses briefly when you shoot, and processing takes a few seconds. It's best in good light; in dim light the 12 MP merged photos will look cleaner. Processing takes about a second; the next shot is available once "Processing…" disappears. All look settings are constants at the top of `PhotoProcessor.kt` if you want to tune them.

## Using it

- **PHOTO / VIDEO** (above the shutter): switch modes.
- **Shutter button** or **volume keys**: take a photo, or start/stop recording. Clips are saved to `Movies/SmoothCam` (visible in Google Photos).
- **Left pill**: cycle 4K 60 → 1080p 60 → 4K 30 → 1080p 30.
- **Right pill**: toggle the **180° shutter**. If it reads **180° auto ISO**, brightness keeps adapting on its own (Android 16 shutter priority). If it reads **180° locked**, your camera doesn't offer that mode: exposure is metered when you tap and then frozen (tap twice to re-meter).
- **HDR / SDR pill** (above the record button): switch between 10-bit HLG HDR and standard video. If a resolution can't do HDR, the app drops to SDR automatically and tells you.
- Rotating the phone sideways records landscape video; the UI stays in portrait, like the iPhone camera.

## Known limits of v0.4

- Main back camera only (no lens switching or zoom).
- Photo mode has no flash, tap-to-focus, Night mode or Portrait mode yet, and in very low light it won't match Pixel's own Night Sight.
- In very bright light, a true 180° shutter needs a physical ND filter; without one, the app shortens the shutter as little as possible.
- Exposure is frozen while 180° lock is on, but only on cameras without shutter-priority support.
- HDR clips look best in Google Photos or YouTube; some apps show HLG video washed out.
