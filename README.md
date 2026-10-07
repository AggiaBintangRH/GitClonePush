# GitClonePush

Android Git client berbasis Kotlin, Jetpack Compose, Material 3, dan JGit. GitHub OAuth menggunakan Authorization Code + PKCE; pertukaran token dilakukan melalui backend Cloudflare Worker, bukan dengan client secret di APK.

## Cara pakai

1. Install APK release yang sudah ditandatangani oleh developer.
2. Pilih **Continue with GitHub**, lalu selesaikan otorisasi di browser. Pengguna tidak perlu memasukkan client ID, client secret, PAT, atau password GitHub ke dalam aplikasi.
3. Di halaman Repositories, tekan **+**, pilih repository GitHub, lalu **Clone**.
4. Berikan izin akses file jika diminta, pilih folder induk di penyimpanan lokal/SD card, lalu konfirmasi. Aplikasi membuat folder dengan nama repository; folder yang sudah berisi data tidak ditimpa.
5. Buka repository yang sudah di-clone. **Open in Files** membuka folder working tree yang tersimpan. File yang ditambah/diubah/dihapus di folder tersebut akan terbaca saat status diperbarui.
6. Stage perubahan yang ingin disertakan, isi pesan commit, lalu commit staged changes. Identitas author mengikuti akun GitHub yang login.
7. Gunakan **Fetch**, **Pull**, atau **Push** secara eksplisit. Pull bersifat fast-forward-only; divergence/konflik tidak diselesaikan otomatis dan tidak ada force push.

Workspace juga menyediakan file listing, staged/unstaged diff, commit history/detail, branch/tracking, merge, rebase, revert, cherry-pick, dan stash. Operasi lanjutan dapat mengubah sejarah atau working tree: baca konfirmasi dan jangan gunakan repository penting sebagai percobaan pertama.

Search/filter bekerja lokal; pull-to-refresh memuat ulang metadata GitHub tanpa menghapus state clone. **Remove clone** di pengaturan workspace menghapus clone lokal termasuk perubahan/commit yang belum dipush setelah konfirmasi, bukan repository GitHub.

## Persyaratan build

- Android Studio yang mendukung AGP 9.1.1, atau command line Android SDK.
- JDK 21 direkomendasikan: `gradle/gradle-daemon-jvm.properties` memilih daemon Java 21. Gradle dapat memperoleh toolchain melalui resolver Foojay bila belum tersedia.
- Android SDK Platform **36.1** (`platforms;android-36.1`) dan Build Tools **36.0.0**. Tidak perlu menaikkan compileSdk ke 37.
- Gradle Wrapper **9.3.1** sudah disertakan; jangan bergantung pada Gradle global versi lain.
- Internet untuk mengunduh dependency saat build pertama. Android minimum API 24; shared-folder clone didukung Android 7–9 dan 11+, dengan batas Android 10 dijelaskan di bawah.

## Clone dan konfigurasi developer

```bash
git clone https://github.com/AggiaBintangRH/GitClonePush.git
cd GitClonePush
```

Buka project di Android Studio dan pilih SDK yang sesuai. Buat `local.properties` dari `local.properties.example`, tambahkan lokasi SDK bila memakai command line:

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
github.clientId=YOUR_GITHUB_OAUTH_CLIENT_ID
oauth.backendBaseUrl=https://YOUR-WORKER.workers.dev
```

`local.properties` diabaikan Git. Client ID dan backend URL adalah konfigurasi aplikasi milik developer, bukan input end user. Jangan menaruh client secret, token pengguna, atau signing password di file source/build yang dikomit.

Untuk CI, gunakan `GITHUB_CLIENT_ID` dan `OAUTH_BACKEND_BASE_URL`, atau Gradle properties `github.clientId` dan `oauth.backendBaseUrl`. Tanpa konfigurasi OAuth, project tetap bisa dikompilasi untuk pengembangan/test, tetapi login tidak berfungsi. Nilai placeholder juga tidak menghasilkan login yang berfungsi.

### OAuth backend

Developer perlu mengonfigurasi OAuth App dan backend untuk build yang bisa login:

- Redirect GitHub: `https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html`
- Android handoff: `gitclonepush://oauth/callback`
- Backend: `POST /oauth/token` menerima `code` dan `code_verifier`.
- Client secret **hanya** berada di Cloudflare secret storage. Backend harus memakai client ID yang sama dengan build Android.
- Token pengguna disimpan terenkripsi dengan Android Keystore; credential Git disuplai hanya pada waktu operasi, bukan dalam remote URL atau `.git/config`.

Source backend disertakan di `oauth-backend/`. Lihat [setup OAuth](docs/github-auth-setup.md) dan [deployment Worker](oauth-backend/README.md). Token refresh belum diimplementasikan; build ini mengasumsikan expiring access tokens OAuth App dinonaktifkan.

## Build dan test

Windows PowerShell:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

Untuk tes perangkat, jalankan emulator/hubungkan perangkat dengan USB debugging, lalu jalankan `:app:connectedDebugAndroidTest` lewat wrapper. Unit tests tidak membutuhkan GitHub/token asli. Tes instrumentation memakai fixture lokal dan emulator/perangkat.

## APK release

Build unsigned release:

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Hasil: `app/build/outputs/apk/release/app-release-unsigned.apk`. **APK unsigned bukan APK siap install/distribusi.** Project tidak menyimpan release signing key atau password.

Untuk APK installable, di Android Studio pilih **Build → Generate Signed App Bundle or APK → APK → release**, lalu pilih/buat keystore developer di lokasi aman di luar repository. Gunakan signing key yang sama untuk update aplikasi yang sudah terpasang. Konfigurasi OAuth developer harus sudah tersedia saat build; pengguna ponsel tidak mengisi konfigurasi tersebut.

Opsional, jalankan pemeriksaan APK signed di Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-release.ps1 -ApkPath C:/path/to/signed-app.apk
```

Lihat [keamanan release](docs/release-security.md). Build release/signing tidak otomatis menghilangkan peringatan reputasi aplikasi saat sideload atau berarti aplikasi sudah disetujui Google Play.

## Arsitektur dan batasan

```text
app/src/main/java/com/threeastudio/gitclonepush/
├── app/                 dependency composition, navigation, OAuth callback
├── core/                shared models, security contracts, UI components
├── feature/             screens, immutable UI state, ViewModels
├── domain/              focused interfaces and orchestration use cases
├── data/                OAuth/GitHub, secure storage, JGit, files/documents
└── ui/theme/            centralized theme
app/src/test/            JVM regression tests
app/src/androidTest/     Android and Compose regression tests
oauth-backend/           server-side OAuth Worker
docs/                    setup, storage, security, audit notes
scripts/                 release validation
```

UI/ViewModels bergantung pada interface, bukan OkHttp/JGit atau token. Operasi blocking berjalan di IO; mutasi repository dikoordinasikan per repository. Fake service hanya untuk test/preview.

- Clone langsung ke shared storage membutuhkan izin storage yang sesuai. Android 10 belum mendukung clone baru ke selected shared folder pada target SDK ini; clone internal lama tetap dapat dibuka.
- Cloud/virtual document provider bukan tujuan clone langsung. File manager eksternal pada shared storage bisa melihat `.git`; jangan mengeditnya. DocumentsProvider aplikasi sendiri memblokir `.git`, traversal, dan symlink escape.
- Ada warning dependency untuk versi terbaru yang belum kompatibel dengan SDK/toolchain saat ini, warning kebijakan all-files access, dan dua trust-manager warning dalam dependency JGit. Build berhasil **bukan** klaim bebas warning atau audit keamanan menyeluruh. Lihat [compatibility review](docs/dependency-compatibility.md) dan [warning audit](docs/warning-audit.md).
- Validasi sertifikat JGit default aktif, tetapi audit menemukan belum ada pengaman eksplisit terhadap konfigurasi Git `http.sslVerify=false`. Jangan menonaktifkan validasi TLS. Upgrade JGit saja belum menghapus kelas trust-all dari library yang diaudit.
- Angka hasil test dalam dokumen audit adalah snapshot historis. Jalankan ulang test untuk perubahan berikutnya.

## Yang tidak dikomit

Cache/build output, APK/AAB, file konfigurasi lokal, `.env`/`.dev.vars`, token, client secret, signing key/password, serta repository hasil clone pengguna. Yang disertakan hanya source, resource, test, wrapper/config build publik, template konfigurasi, backend source, script, dan dokumentasi.

## Lisensi

Lihat [LICENSE](LICENSE).
