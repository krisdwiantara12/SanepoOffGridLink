# DESIGN.md - Sanepo OffGrid Link

## Design Read
Reading this as: Dual-mode Tactical Field Transceiver & Compass Console for offline riders, hikers, and emergency teams, in a physical handheld-radio instrument aesthetic, dial ENERGY 2 / RHYTHM 2 / MOTION 1.

## Dials
Dial: ENERGY 2 / RHYTHM 2 / MOTION 1
- **ENERGY 2:** Clear hierarchy anchored by one primary transceiver surface (PTT dome on Radio tab, Compass dial on Radar tab) with high-legibility numeric readouts for outdoor use.
- **RHYTHM 2:** Varied section geometry across screens (circular radar bezel, linear telemetry readouts, asymmetric chat bubbles, dense node list) so pages do not flatten into uniform cards.
- **MOTION 1:** Motion strictly communicates real physical input or hardware telemetry (microphone PCM VU meter amplitude, live magnetic compass rotation toward peer azimuth, and SOS Morse lamp pulse). Zero decorative fade/bounce loops.

## Color Palette (3 Core + 1 Accent + Neutrals)
- **Dark Mode (Field Night - OLED Battery Saving, Zero Night Glare):**
  - Neutral Ground: `#0E110E`
  - Surface / Console: `#171C17`
  - Keycap / Raised Control: `#202720`
  - Border (`>= 3:1` non-text contrast): `#6A7A6A`
  - Primary Ink (`17.16:1`): `#F2F4EF`
  - Secondary Ink (`8.89:1`): `#B2BDB0`
  - Accent Fill: `#FFB300` with Ink `#111410` (`10.34:1`)
  - Accent Text/Icon (`9.63:1`): `#FFB300`
  - Connected / GPS Locked (`10.25:1`): `#5CE085`
  - SOS Alert (`4.83:1` / `6.34:1`): `#DC2626` fill (`#FFFFFF` text) / `#FF6E6E` text on dark
- **Light Mode (Daylight Sun - High-Visibility Outdoors):**
  - Neutral Ground: `#F4F2EC`
  - Surface / Console: `#FFFFFF`
  - Keycap / Raised Control: `#EAE6DC`
  - Border (`>= 3:1` non-text contrast): `#6E6B60`
  - Primary Ink (`16.14:1`): `#141714`
  - Secondary Ink (`8.59:1`): `#474E46`
  - Accent Fill: `#FFB300` with Ink `#141714` (`10.07:1`)
  - Accent Text/Icon (`6.61:1`): `#9A4300`
  - Connected / GPS Locked (`6.25:1`): `#136F3A`
  - SOS Alert (`6.47:1`): `#DC2626` fill (`#FFFFFF` text) / `#B91C1C` text on white

## Typography & Scales
- **Display & Callsigns:** `sans-serif-medium` / `sans-serif-condensed` for maximum legibility on bike handlebars or dim trails.
- **Telemetry Values Only:** `monospace` (`fontFeatureSettings="tnum"`) exclusively on live dBm, GPS coordinates, bearing degrees, distances, and transmission timers so rapidly changing digits never jitter sideways.
- **Radii Scale:** `6dp` status chips, `12dp` module cards and input fields, `20dp` primary transceiver dock, circular (`oval`) exclusively on the physical PTT button and Radar Compass reticle.
- **Identity Motif:** Engraved `1dp` borders, tabular frequency/channel header bar, circular azimuth dial, and two-tone vector hardware glyphs sized per slot (`>= 48dp` touch targets throughout).
