# Özel Kapadokya Hastanesi – Personel Android

**Sürüm:** 1.0.1  
**Paket adı:** `com.kapadokyaonline.personel`  
**Sunucu:** `https://personel.kapadokyaonline.com/`

Bu proje personel tarafını Android uygulaması olarak çalıştırır.

## QR akışı

Bu uygulamada akış **V5.3 ile aynı ve doğru yöndedir**:

1. Hastane kiosku ekranda dinamik QR gösterir.
2. Personel Android uygulamasında **QR Giriş / Çıkış** ekranını açar.
3. Uygulama arka kameraya izin ister.
4. Personel telefonuyla kiosktaki QR'ı okutur.
5. Giriş / çıkış sunucuda kaydedilir ve sonuç personel ekranında görünür.

**Uygulama QR üretmez. Kiosk kamera açmaz.**

## Uygulamanın yaptığı işler

- Personel portalını uygulama içinde açar.
- Personel oturum çerezini korur; her açılışta tekrar giriş yapmak zorunda kalmayabilir.
- Web kamerayı yalnızca `https://personel.kapadokyaonline.com` origin'i için verir.
- Mikrofon izni vermez.
- HTTP / cleartext bağlantıyı kapatır.
- SSL sertifika hatasında bağlantıyı kesin olarak durdurur.
- Site dışı bağlantıları sistem tarayıcısında açar.
- `Fotoğraftan Oku` alanı için Android dosya/fotoğraf seçiciyi destekler.
- Personel panelinden indirilen dosyalarda oturum çerezini DownloadManager'a aktarır.
- Android WebView debug modunu kapatır.
- Kullanıcı adı veya şifre APK'nın içine gömülü değildir.
- Server tarafındaki personel portalı güncellendiğinde çoğu değişiklik için APK'yı yeniden yayınlamak gerekmez.

## Android Studio ile APK oluşturma

1. Android Studio'yu açın.
2. **Open** ile `KapadokyaPersonelAndroid` klasörünü seçin.
3. Gradle senkronizasyonunun tamamlanmasını bekleyin.
4. Üst menüden **Build → Build App Bundle(s) / APK(s) → Build APK(s)** seçin.
5. Debug APK:
   `app/build/outputs/apk/debug/app-debug.apk`

İlk test için debug APK yeterlidir.

## GitHub Actions ile APK oluşturma

Projeyi bir GitHub deposuna yüklediğinizde `.github/workflows/build-apk.yml` otomatik olarak hazırdır.

- GitHub → **Actions**
- **Android APK Oluştur**
- **Run workflow**
- İşlem bittiğinde `Kapadokya-Personel-APK` artifact'ını indirin.

Bu yöntem yerel bilgisayarda Android Studio kurmadan da APK üretir.

## Release APK

Gerçek kurum dağıtımında kendi imza anahtarınızla release APK üretin. Özel anahtarı kaynak ZIP'in içine koymayın ve GitHub'a yüklemeyin.

Android Studio:
**Build → Generate Signed App Bundle / APK → APK**

## Gereksinimler

- Android 8.0 (API 26) veya üzeri.
- Güncel Android System WebView / Chrome.
- HTTPS erişimi.
- QR okutma için kamera izni.

## Sunucuda ek SQL gerekir mi?

**Hayır.** Android uygulaması mevcut V5.3 personel portalını kullanır. Yeni tablo veya SQL gerekmez.

## Önemli güvenlik notu

Uygulama bir WebView kabuğu olsa da herkese açık genel WebView değildir. Yalnızca Kapadokya personel alanını uygulama içinde tutar; farklı alan adlarını sistem tarayıcısına gönderir. Kamera web izni de yalnızca kurumun HTTPS origin'ine verilir.


## V1.0.1 – Kalıcı personel oturumu

V5.4 sunucu patch'i ile birlikte personel bir kez giriş yaptıktan sonra uygulamayı kapatıp yeniden açsa bile tekrar giriş yapmak zorunda değildir.

Android uygulaması sunucudan gelen Secure/HttpOnly kalıcı cookie'yi Android CookieManager'a kaydeder ve `flush()` ile diske yazar.

Personel **Çıkış Yap** dediğinde sunucu kalıcı tokenı iptal eder ve cookie silinir.
