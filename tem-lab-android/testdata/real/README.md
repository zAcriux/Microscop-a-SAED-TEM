# Real instrument files (optional validation)

Place real `.dm3`, `.dm4`, `.tif` or `.mrc` files here (they are git-ignored) and, for each, an optional
sidecar `<file>.expected` with reference values read in DigitalMicrograph / ImageJ:

```
width=2048
height=2048
pixel=0.0245
unit=nm
mean=1534.2
```

Then run `./gradlew :core:test --tests '*FormatValidationTest.realFilesIfAvailable*'`.
Each file is opened and its dimensions, data type, calibration and intensities are compared with the sidecar.
