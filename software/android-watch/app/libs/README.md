# Samsung Health Sensor SDK v1.4.1

**Status:** AAR not bundled in git (Samsung license). Copy manually before real-sensor sessions.

## Install AAR

1. Download SDK from [Samsung Developer](https://developer.samsung.com/health/sensor/overview.html)
2. From zip extract:
   ```
   1.4.1/libs/samsung-health-sensor-api-1.4.1.aar
   ```
3. Copy to this folder as:
   ```
   samsung-health-sensor-api.aar
   ```
4. Rebuild watch app:
   ```powershell
   cd android-watch
   .\gradlew.bat assembleDebug
   ```

Without AAR the watch app runs **DEMO** mode (synthetic multi-stream data for pipeline testing).

With AAR installed, watch UI shows **Mode: SAMSUNG_SDK** and streams real biosignals from Galaxy Watch Ultra.

Docs (local): `C:\Users\domain\Downloads\samsung-health-sensor-sdk-v1.4.1\1.4.1\docs\`
