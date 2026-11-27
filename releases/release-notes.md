### Pembaruan Utama v1.0.2 (Perbaikan Updater OTA)
- **Penggantian Total Unduhan OTA (`HttpURLConnection` Langsung):** Menghapus ketergantungan pada sistem `DownloadManager` Android (yang sebelumnya gagal menulis ke `cacheDir` aplikasi / memunculkan pesan *"Layanan DownloadManager tidak tersedia di perangkat"*). Unduhan pembaruan sekarang dialirkan langsung via HTTPS dengan dukungan pengalihan CDN GitHub (`301/302/307/308`).
- **Validasi Integritas APK Berlapis:** Pemeriksaan header ZIP/APK (`PK`), ukuran minimum, progres MB real-time, serta verifikasi checksum `SHA-256` sebelum installer APK otomatis dibuka lewat `FileProvider`.

### Riwayat Rilis v1.0.1
- Koneksi otomatis Bluetooth LE GATT tanpa Wi-Fi (`BT+`), kompresi suara 4:1 IMA ADPCM (`16 kHz` Wi-Fi / `8 kHz` BLE), Ring Jitter Buffer audio, `WifiLock`, dan banner tombol cepat Bluetooth/Lokasi.
