# Kapadokya Personel Android

Android WebView personel uygulaması.

## 1.0.4
- ERR_CONNECTION_ABORTED ve benzeri geçici ana sayfa bağlantı hatalarında otomatik retry.
- Art arda hata olursa WebView kendini yeniden oluşturur; cookie ve kalıcı oturum verisi silinmez.
- WebView renderer process kapanır/çökerse uygulama kendini toparlar.
- Wi-Fi / mobil veri geri geldiğinde otomatik tekrar bağlanır.
- Başarısız bağlantı tanılama bilgisi, sonraki başarılı ağ anında sunucuya gönderilir.
- SSL doğrulaması hiçbir zaman bypass edilmez.
- QR kamera izinleri ve güvenli origin kısıtları korunur.

Sunucu: https://personel.kapadokyaonline.com/

APK için:
Actions → Android APK Oluştur → son başarılı çalışma → Artifacts → Kapadokya-Personel-APK.
