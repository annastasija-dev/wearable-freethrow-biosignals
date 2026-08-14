# Android telefono app (Samsung)

## Atidaryti projektą

1. Įdiekite **Android Studio** (Ladybug ar naujesnė)
2. **File → Open** → pasirinkite:
   `C:\Users\domain\Downloads\basketball-cloud-study\android-phone`
3. Palaukite **Gradle Sync** (Android Studio pats atsisiųs Gradle)

## Prieš build

1. Paleiskite cloud kompiuteryje:
   ```powershell
   cd C:\Users\domain\Downloads\basketball-cloud-study
   .\start_cloud.ps1
   ```
2. Telefone ir PC turi būti **tas pats Wi‑Fi**

## Įdiegti į Samsung telefoną

1. Telefone: **Developer options → USB debugging** ON
2. Prijunkite USB
3. Android Studio: **Run ▶** (pasirinkite telefoną)

Arba sugeneruokite APK:
```powershell
cd android-phone
.\gradlew.bat assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk`

## Pirmas paleidimas

1. App → **⋮ Nustatymai**
2. Cloud URL: `http://192.168.1.89:8080` (pakeiskite į savo PC IP)
3. API key: `dev-change-me`
4. **Testuoti cloud** → turi rodyti OK
5. Įrašykite `P001` → **PRADĖTI SESIJĄ**
6. Po kiekvieno metimo: **PATAIKĖ** / **NEPATAIKĖ**
7. **BAIGTI SESIJĄ**

## Kas siunčiama į cloud

| Tipas | Kelias |
|-------|--------|
| Protokolas | `cloud/data/protocol/{session_id}/` |
| Raw IMU | `cloud/data/raw/{session_id}/` (kai bus watch app) |

## Samsung Health pastaba

Ši telefono app renka **protokolą** (hit/miss + laikas).
Raw IMU ateina per **Wear OS watch app** (Samsung Health Sensor SDK) → `WearableImuBridge`.

## Troubleshooting

| Problema | Sprendimas |
|----------|------------|
| Cloud klaida | Patikrinkite IP, firewall, ar `start_cloud.ps1` veikia |
| Gradle sync fail | Atidarykite per Android Studio, ne rankiniu gradlew be wrapper jar |
| App neprisijungia | Nustatymuose naudokite `http://`, ne `https://` lokaliai |
