<p align="center"><img src="screenshots/icon.png" width="120" alt="App icon"></p>

<h1 align="center">Weathered</h1>
<p align="center"><b>A weather app for people who work outside. It tells you what's coming, and it swears at you about it.</b></p>

<p align="center">
  <img src="screenshots/today.jpg" width="19%" alt="Today">
  <img src="screenshots/hourly.jpg" width="19%" alt="Hourly">
  <img src="screenshots/16-day.jpg" width="19%" alt="16-day forecast">
  <img src="screenshots/work.jpg" width="19%" alt="Work conditions">
  <img src="screenshots/radar.jpg" width="19%" alt="Radar">
</p>

## What it does

- **Today**: current conditions, an animated sky that matches the weather (drifting clouds, rain, snow, sleet, hail, lightning, stars, wind), next precipitation down to the 15 minutes, and a running commentary that does not hold back.
- **Hourly**: a 48-hour chart (temperature, precipitation, wind, UV) and an hour-by-hour list. Tap any hour for everything about it, including whether it's good for concrete, paint, roofing or lifts.
- **16 days**: daily highs, lows, rain chance and conditions. Tap a day for its hours.
- **Radar**: the last two hours of live radar, then up to 12 hours of forecast precipitation, with smooth transitions. Switches its colours automatically for rain, snow or storms.
- **Work**: a go / caution / stop verdict for the next six hours, with checks for heat, cold, ice, wind, lightning, rain, visibility, UV and air quality, plus good working windows for common trades.
- **Alerts**: active National Weather Service watches and warnings (US).
- **Widgets**: small, hourly and hourly + 5-day home screen widgets that refresh about every 30 minutes.
- **Potty mouth**: on by default. Turn it off in Settings for clean wording.

## Install

Grab the latest `Weathered.apk` from [Releases](../../releases) and open it on your Android phone (Android 8.0 or newer). You may need to allow installs from your browser or file manager.

## Build

Every push to `main` builds a debug APK with GitHub Actions and publishes it as a release. To build locally:

```sh
./gradlew :app:assembleDebug
```

The map library ([Leaflet](https://leafletjs.com/)) is downloaded during the CI build into `app/src/main/assets/leaflet/`. For a local build, put `leaflet.js` and `leaflet.css` from Leaflet 1.9.4 there yourself.

## How it's put together

The app is a single web page (`app/src/main/assets/index.html`) inside an Android WebView, with a small Kotlin bridge for location, links, status bar colours and the home screen widgets (`MainActivity.kt`, `Widgets.kt`). No API keys, no accounts, no tracking.

## Data sources

- Forecast, air quality and geocoding: [Open-Meteo](https://open-meteo.com/)
- Weather alerts: [National Weather Service](https://www.weather.gov/) (US only)
- Radar: [RainViewer](https://www.rainviewer.com/)
- Base map: Esri, HERE, Garmin, © OpenStreetMap contributors

Forecasts are models, not promises. Use your own judgement and your site's safety rules.

## License

[GPL-3.0](LICENSE)
