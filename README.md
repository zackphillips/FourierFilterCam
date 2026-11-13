# Fourier Filter Cam

Live Fourier-domain filtering of the phone camera. Switch to the spectrum,
draw a filter on it (pupil, DC stop, single-sideband half plane, annulus or
freeform), and watch the filtered image update in real time. Saved photos
are the filtered output, written as lossless PNG to `Pictures/FourierFilterCam`.

## What the filter models

- **Units.** Frequencies are tracked in cycles per frame pixel. When the
  camera reports its sensor size, the spectrum axes switch to cycles/mm at
  the sensor or cycles/degree in the field of view. The effective pixel pitch
  accounts for the stream being binned or cropped from the active array.
  The dashed circle is the incoherent diffraction cutoff 1/(λN) at 550 nm.
  It only appears when it falls inside the sampled band, which on most phones
  is only at full resolution.
- **Color, optical 4f.** A physical mask in the Fourier plane passes spatial
  frequency ν at position λfν, so each color channel sees the mask scaled by
  λ_c/λ_ref (R 600, G 530, B 465 nm; the mask is drawn at green). Red loses
  detail before blue. "Color, digital" applies the same mask to every channel.
- **Output.** "Real part" is a linear digital filter. "Magnitude" is |field|,
  which is what a coherent 4f system shows, and is the meaningful view for
  asymmetric (single-sideband) filters.
- **Implementation.** Frames are mirror-padded to a 2·3·5-smooth even size so
  the DFT is fast and ifftshift equals fftshift. Mask edges get a Gaussian
  soften (adjustable) to cut ringing. The display range is a moving average,
  so it doesn't flicker.

## Layout

- `dsp/`: plain Kotlin/JVM library with the filter geometry, rasterizer,
  units and the OpenCV engine. Unit-tested on a desktop JVM against OpenCV's
  desktop build.
- `app/`: Android app built with CameraX `ImageAnalysis` (RGBA), Compose UI,
  rendering to a `SurfaceView`, and capture through MediaStore.

## Building

Requires JDK 17+ and the Android SDK with `platforms;android-37.0` and
`build-tools;37.0.0`. OpenCV 4.14 comes from Maven Central as a Gradle
dependency, so no native binaries are checked in.

```sh
./gradlew :dsp:test               # DSP unit tests, no device needed
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
                                  # layout tests at 8 window sizes; PNGs in app/build/outputs/roborazzi
./gradlew :app:installDebug       # build and install on a connected device
./gradlew :app:bundleRelease      # Play upload artifact (app/build/outputs/bundle/release)
```

### Release signing

Release builds are signed only if `keystore.properties` exists at the repo
root. It's gitignored; never commit it or the keystore.

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

The Play listing's package name is `com.phillyberk.fourierfiltercam`, set as
`applicationId`. Uploads must be signed with the listing's upload key, or with
a new one registered through Play App Signing's key-reset flow. `versionCode`
must be higher than the last published build.

### Automated releases

`.github/workflows/release.yml` runs on pushes to `master` that touch
`app/build.gradle.kts`. If `versionName` has no matching `v<versionName>` tag,
it runs the DSP tests, builds a signed APK and AAB, verifies the signature and
16 KB alignment, and publishes a GitHub release with both plus `SHA256SUMS`.
It fails if `versionCode` isn't greater than the one at the latest `v*` tag.
To release, bump `versionCode` and `versionName` together and merge.

Signing comes from four repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 upload-keystore.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | `storePassword` |
| `RELEASE_KEY_ALIAS` | `keyAlias` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` |

With Play App Signing, this APK carries the upload key's signature, not the
key Play re-signs with, so it can't install over a Play-installed copy (and
vice versa). Fine for sideloading; uninstall first when switching.

## Play requirements met

- targetSdk 36 (required for new apps and updates since Aug 31, 2026), compileSdk 37.
- 16 KB page-size aligned native libraries; CI verifies with `zipalign -P 16`.
- Runtime camera permission. No storage permission; captures go through MediaStore.
- Edge-to-edge and freely resizable, so no orientation lock on large screens.
  Controls switch between three arrangements by available window size:
  stacked rows (narrow), a side rail (short, e.g. landscape phones), and single
  rows (tablets, foldables, desktop windows). Layout tests render the real
  screen with Robolectric on small, regular and landscape phones, an unfolded
  foldable, portrait and landscape tablets, a 220dp multi-window pane, and 2×
  font scale. They check that every control is on screen, at least 48dp, and
  not overlapping another control, and that the shutter is visible without
  scrolling.
- No network access and no data collected.
