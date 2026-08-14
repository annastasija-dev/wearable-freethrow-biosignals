# Wearable Biomedical Signals for Event-Labelled Motor-Skill Assessment

Field-deployable smartwatch–smartphone–cloud framework (VILNIUS TECH, Department of Electronic Systems).

**Anastasija Grubinskienė** (corresponding), **Andrius Katkevičius**

Manuscript for MDPI *Sensors* (draft). Basketball free-throw shooting is the primary use case; the same labelled-event schema extends to other discrete motor skills.

Cite this repository: [https://github.com/annastasija-dev/wearable-freethrow-biosignals](https://github.com/annastasija-dev/wearable-freethrow-biosignals)

Overleaf: [project](https://www.overleaf.com/project/6a50d0b6825a6295ba220c83)

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
