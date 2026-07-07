JuiceSSH Plugin: Performance Monitor
====================================

A [JuiceSSH](https://juicessh.com) plugin for monitoring Linux servers, built on the JuiceSSH
Plugin SDK. Pick one of your saved JuiceSSH SSH connections and it opens a background session,
runs lightweight commands on an interval, and shows live server metrics as a dashboard of tiles.

This is a **modernized continuation** of the original
[Sonelli/juicessh-performancemonitor](https://github.com/Sonelli/juicessh-performancemonitor),
brought up to date for current Android and given a Material 3 UI/UX revamp.

Metrics
-------

- **CPU usage** (aggregate + per-core in the detail view)
- **Free RAM** (with total / used / buffers / cached / swap breakdown)
- **Temperature** (reads `/sys/class/thermal`, so it works on real servers — not just Raspberry Pi)
- **Load average** (1 / 5 / 15 min + process counts)
- **Network throughput** (total + per-interface, bytes or bits)
- **Disk usage** (root filesystem + per-mount)

What's new in this version (2.0)
--------------------------------

- **Runs on modern Android.** Rebuilt on Android Gradle Plugin 8.6 / Gradle 8.7 / JDK 17, migrated
  to **AndroidX**, and re-targeted to **Android 15 (targetSdk 35)** so it installs on Android 11–16
  devices. (The original targeted Android 7 and no longer built or installed on current phones.)
- **Material 3 UI** with automatic light/dark theming and edge-to-edge layout.
- **Live sparklines** on every tile showing the recent trend, not just the instant value.
- **Tap-to-expand detail sheets** with the per-core / per-mount / per-interface breakdown.
- **Settings screen**: refresh interval, °C/°F, bytes/bits network units, and theme.
- The JuiceSSH Plugin SDK is **vendored** (`Plugin/libs/`) so the build no longer depends on the
  long-dead jcenter/GitHub Maven host.

Building & installing
---------------------

Requires JDK 17 and the Android SDK (platform 35). From the repo root:

```
./gradlew :Plugin:assembleDebug
adb install -r Plugin/build/outputs/apk/debug/Plugin-debug.apk
```

There's no Play Store listing — install the APK by sideloading. Once installed, open **JuiceSSH**
(paid or free) and it will detect this as a plugin; grant the two JuiceSSH permissions, choose an
SSH connection, and press **Connect**.

How the metrics are gathered
----------------------------

Each metric has its own controller that runs one shell command per tick and parses the output:

- [CPU](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/CpuUsageController.java)
- [RAM](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/FreeRamController.java)
- [Temperature](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/TemperatureController.java)
- [Load average](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/LoadAverageController.java)
- [Network](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/NetworkUsageController.java)
- [Disk](Plugin/src/main/java/com/sonelli/juicessh/performancemonitor/controllers/DiskUsageController.java)

For details of the JuiceSSH Plugin SDK and how to write your own plugin, see the
[JuiceSSH FAQ](http://juicessh.com/faq).

License & attribution
---------------------

Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). This is a modified version of the
original [Sonelli/juicessh-performancemonitor](https://github.com/Sonelli/juicessh-performancemonitor);
thanks to Sonelli and to hwding for the earlier Material theme work. Contributions welcome.
