### Pembaruan Utama v1.0.3 (Penyempurnaan Audio & Koneksi)
- **Perbaikan Jalur Suara Walkie-Talkie PTT:**
  - Mengubah sumber mikrofon ke `MediaRecorder.AudioSource.MIC` langsung dengan penguatan suara bersih `1,65x` (menghindari pemblokiran mic senyap VoIP `VOICE_COMMUNICATION` & `AcousticEchoCanceler` bawaan beberapa merk HP).
  - Menambahkan jalur pengiriman suara cadangan lewat **TCP `noDelay` (`47880`)** bila paket **UDP `47882`** belum terkonfirmasi lolos pada router/hotspot, lengkap deduplikasi sekuens paket agar tidak bergema.
  - Menambahkan **2-byte sync header (`0xAB 0x5A`)** & perbaikan token pengawas antrean pada **BLE GATT (`MTU 517` + fallback service discovery 1,5 detik)** sehingga suara Bluetooth tidak macet di tengah jalan.
- **Alat Bantu Cek & Kontrol Lapangan:**
  - Tombol baru **Tes Mic & Speaker Lokal (2 Detik)** untuk menguji mic, codec ADPCM, dan speaker HP secara langsung sebelum mulai komunikasi.
  - Penghitung **Paket TX / RX** serta indikator jalur aktif (`Wi-Fi HD` / `Bluetooth LE`) langsung di layar Radio + pengunci sentuhan agar tombol PTT tidak terputus saat jari bergeser di `ScrollView`.
  - Peringatan Bluetooth/Lokasi otomatis disembunyikan begitu unit sudah terhubung lewat Wi-Fi/Hotspot atau Bluetooth, disertai penjelasan jujur aturan Android OS.
- **Pembaruan OTA Tanpa `DownloadManager` (`v1.0.2`):** Unduhan update langsung lewat streaming HTTPS + verifikasi header APK & `SHA-256`.
