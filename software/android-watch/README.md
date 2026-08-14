# Wear OS watch app (Galaxy Watch)

## Funkcija

- Skaito **accelerometer** per Samsung Health Sensor SDK (~25 Hz)
- Siunčia raw IMU į telefoną per Wearable Message API (`/basketball/imu`)
- Telefonas uploadina į cloud `raw/`
- Valdoma iš telefono app: **Start session** / **Finish session**

## Reikalavimai

- Galaxy Watch 4 ar naujesnis (Wear OS powered by Samsung)
- Samsung telefonas suporuotas per Galaxy Wearable
- **Samsung SDK v1.4.1** jau įdiegtas: `app/libs/samsung-health-sensor-api.aar`

## Build ir įdiegimas

Eksperimentui: **FT Watch atskirai nesiunčiama**. Telefone FT Protocol → ⋮ → Įdiegti į laikrodį.

Kūrėjams (Android Studio):
1. Open → `android-watch/`
2. Gradle Sync
3. Physical Watch (emuliatorius su SDK **neveikia**)
4. Run ▶ ant laikrodžio — arba rebuild, tada phone app įdeda APK į `res/raw`

## DEMO režimas

Jei AAR pašalintas, app veikia **DEMO** režimu. Dabar turite tikrą SDK — po build turėtumėte matyti **Mode: Samsung SDK**.

## Eiga su telefonu

```
Telefonas: START SESSION
    → watch gauna /basketball/start
    → pradeda IMU stream
Telefonas: PATAIKĖ / NEPATAIKĖ ×10
Telefonas: FINISH SESSION
    → watch gauna /basketball/stop
    → sustabdo IMU
```

## Watch ekranas

Rodo:
- Mode: Samsung SDK arba DEMO
- Session ID
- Samples sent (kiek išsiųsta į telefoną)
- Demo Start/Stop (rankiniam testui be telefono)

## Troubleshooting

| Problema | Sprendimas |
|----------|------------|
| Samples sent = 0 | Patikrinkite Bluetooth/Wearable ryšį telefonas↔laikrodis |
| Samsung SDK neveikia | Įdiekite/atnaujinkite Health Platform laikrodyje |
| Permission denied | Suteikite BODY_SENSORS ir Samsung health permission |
| Build error be AAR | Normalu — naudokite DEMO arba įdėkite AAR |

## Architektūra

```
Galaxy Watch (Ši app)
  SamsungHealthImuTracker / DemoImuTracker
       ↓ Wearable MessageClient
Samsung Phone (FT Protocol app)
  WearableImuBridge → CloudApiClient → cloud/raw/
```
