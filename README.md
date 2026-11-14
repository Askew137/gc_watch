# GC Watch 🧭

Offline geocaching navigátor pro **OnePlus Watch 2** (Wear OS 4) s companion aplikací pro Android.

## Funkce
- 🗺️ Offline vektorové mapy (Mapsforge `.map` soubory)
- 📍 Přímý přístup k L1+L5 GPS hardware hodinek (bypass Bluetooth tellhering)
- 🧭 Duální rozhraní: **Mapa** ↔ **Kompas/Radar**
- 🎯 Cíl z odkazu `geo:` (c:geo apod.): na mapě značka a vzdušná čára; jedním tlačítkem přepínáš vzdušná čára → trasa → jen cíl (trasu pěšky spočítá telefon z dat BRouteru a pošle ji hodinkám automaticky po příchodu cíle)
- 🌙 Night Red mód pro zachování tmavého vidění
- 🔄 Synchronizace nastavení mezi hodinkami a telefonem
- ⬆️ Aktualizace jedním tlačítkem: telefon při otevření zjistí nový GitHub Release, stáhne obě APK, pošle hodinkovou na hodinky a nainstaluje se; potvrzuje se na hodinkách i telefonu (viz „Vydání nové verze“)

## Požadavky

| | Verze |
|---|---|
| Android Studio | Ladybug nebo novější |
| JDK | 17 (Temurin doporučen) |
| Android SDK | API 35 |
| Wear OS | 3+ (API 30+) |
| Kotlin | 2.0.21 |

## Rychlý start

```bash
# 1. Klonuj repozitář
git clone https://github.com/Askew137/gc_watch.git
cd gc_watch

# 2. Otevři v Android Studio (automaticky nastaví Gradle wrapper)
# NEBO inicializuj wrapper ručně (vyžaduje Gradle 8.9+ na PATH):
gradle wrapper --gradle-version=8.9

# 3. Build
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

## Stažení map

Aplikace na telefonu obsahuje průvodce. Potřebuješ soubor s mapou (a pro trasy navigační data):

| Soubor | Použití | Zdroj |
|--------|---------|-------|
| `*.map` (Mapsforge) | Offline mapa na hodinkách | [OpenAndroMaps.org](https://www.openandromaps.org) nebo [BBBike.org](https://extract.bbbike.org) |
| `*.rd5` (BRouter) | Navigační data pro trasy, jen v telefonu (po dlaždicích 5°×5°, ~100 MB) | stáhne aplikace sama z [brouter.de](https://brouter.de/brouter/segments4/) |

## Struktura projektu

```
├── core/
│   ├── model/          – Sdílené datové třídy (Waypoint, AppConfig, ...)
│   └── communication/  – Protobuf serializace, MessagePaths konstanty
├── mobile/             – Android companion app (geo: intent, mapy, routing)
└── wear/               – Standalone Wear OS aplikace
```

## Licence
MIT

## Vydání nové verze

1. V `gradle.properties` zvyšte `gcwatch.versionName` a `gcwatch.versionCode` (platí pro telefon i hodinky).
2. Commit, push, počkejte na zelené CI.
3. GitHub Release s tagem `v<versionName>` (např. `v1.0.1`): CI přiloží podepsané `mobile-release.apk` a `wear-release.apk` (secrets `GCWATCH_KEYSTORE_*`).

Aplikace na telefonu pak nabídne **Update phone + watch**. Podmínky:
- jednorázově povolit GC Watch „Instalovat neznámé aplikace“ na telefonu; na hodinkách přes `adb shell appops set com.askew137.gcwatch REQUEST_INSTALL_PACKAGES allow`,
- hodinky musí být připojené; instalaci potvrzujete na obou zařízeních (Android to vyžaduje; Play Protect může nabídnout sken – vyberte „Install without scanning“),
- aktualizace funguje jen s APK podepsanými stejným klíčem.

**Soulad verzí:** telefon se při otevření zeptá hodinek na verzi. Liší-li se (nebo hodinky neodpoví), ukáže upozornění s tlačítkem „Bring both to the same version“, které stáhne release té novější verze a aktualizuje jen ten zařízení, které je pozadu. Zprávy mezi telefonem a hodinkami se totiž při změně formátu u jiné verze nemusí dát přečíst.

**Pojistka proti špatné verzi:** release nejdřív vydejte jako **pre-release** (volba „Set as a pre-release“). Updater používá `releases/latest`, který pre-release neodvrací, takže o něm aplikace neví. Nainstalujte ho ručně na svá zařízení, vyzkoušejte, a teprve potom ho v GitHubu přepněte na „latest“. Android nedovoluje vrátit starší verzi bez odinstalace, takže opravou špatné verze je vždy vyšší verze.

Test bez GitHubu: `-Pgcwatch.updateUrl=http://10.0.2.2:8000/latest.json` (debug build, JSON ve tvaru GitHub API `releases/latest`).
