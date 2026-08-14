# Wearable Biomedical Signals for Free-Throw Outcome Prediction

Field-deployable smartwatch–smartphone–cloud framework (VILNIUS TECH, Department of Electronic Systems).

**Anastasija Grubinskienė** (corresponding), **Andrius Katkevičius**

Manuscript for MDPI *Sensors* (draft). DOI: to be assigned on publication.

Cite this repository: [https://github.com/annastasija-dev/wearable-freethrow-biosignals](https://github.com/annastasija-dev/wearable-freethrow-biosignals)

## Contents

| Path | Description |
|------|-------------|
| `paper/` | LaTeX article (`main.tex`, bibliography, figures, MDPI class) |
| `software/android-phone/` | FT Protocol (Android, Kotlin) |
| `software/android-watch/` | FT Watch (Wear OS, Kotlin; install from the phone) |
| `software/cloud/` | FastAPI backend |

## Paper

From `paper/`:

```bash
pdflatex main && bibtex main && pdflatex main && pdflatex main
```

Overleaf: upload `paper/` as the project root.

## Software

**Collect data (no build):** on the phone open [https://ft-cloud-vgtu.fly.dev/install](https://ft-cloud-vgtu.fly.dev/install), install FT Protocol, then **⋮ → Install on watch**. Protocol: [Instrukcija (LT)](https://ft-cloud-vgtu.fly.dev/instrukcija) · [Instructions (EN)](https://ft-cloud-vgtu.fly.dev/instructions).

**Build:** open `software/android-phone` and `software/android-watch` in Android Studio. Cloud: `software/cloud` (see `fly.toml`).

## License

Manuscript and research software for academic use. MDPI template files remain under MDPI terms.
