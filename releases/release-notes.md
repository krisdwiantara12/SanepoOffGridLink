### Pembaruan Utama v1.0.1
- **Koneksi Otomatis Tanpa Wi-Fi (BLE GATT Full-Duplex):** Dua HP sekarang otomatis saling terhubung lewat Bluetooth LE GATT (`BT+`) begitu berdekatan dan Bluetooth + Lokasi aktif, tanpa butuh Wi-Fi router atau Hotspot. Chat cepat, koordinat GPS, peringatan SOS, dan suara PTT 8 kHz tetap berjalan via Bluetooth saja.
- **Perbaikan Suara Walkie-Talkie Tersendat-Sendat:**
  - Kompresi suara **4:1 IMA ADPCM** (`16 kHz ~8 KB/s` untuk Wi-Fi, `8 kHz ~4 KB/s` untuk Bluetooth) mengurangi beban airtime Wi-Fi secara drastis.
  - Penambahan **Jitter Buffer Ring Audio** (thread pemutar khusus prioritas audio + pre-buffer 80 ms) agar jeda antar paket Wi-Fi tidak langsung memutus suara.
  - Penambahan **`WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`)**, antrean pengirim UDP berurutan tunggal, serta cache IP tujuan agar chip Wi-Fi tidak masuk mode hemat daya di tengah pembicaraan.
- **Banner Bantuan Cepat Hardware & Pindai P2P Berkala:** Muncul tombol pintas **Nyalakan BT** / **Lokasi** bila ada radio HP yang masih mati, plus refresh pemindaian Wi-Fi Direct otomatis saat belum terhubung.
