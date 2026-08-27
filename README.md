# NEXA FB PROXY V3

V3 cho phép chọn độc lập các app đi qua SOCKS5 bằng checkbox:

- Facebook thường — `com.facebook.katana`
- Facebook Lite — `com.facebook.lite`
- Messenger — `com.facebook.orca`

Bạn có thể chọn 1, 2 hoặc cả 3 app. Những app khác trên điện thoại vẫn dùng
Wi‑Fi/4G bình thường.

## Cấu hình SOCKS5 mặc định

- Gateway: `79.127.168.43`
- Port: `50101`
- Username/password: không hard-code trong source
- Exit IP từng test: `84.236.196.252` (có thể đổi theo nhà cung cấp)

## Chức năng

- Test SOCKS5 bằng username/password.
- Hiện exit IP qua `api.ipify.org`.
- Per-app VPN bằng `VpnService.Builder.addAllowedApplication(...)`.
- Kill Switch kiểu fail-closed cho đúng các app đã chọn.
- Không tạo route IPv6 cho app được đưa vào VPN nhằm tránh IPv6 bypass.
- User/pass được mã hóa bằng Android Keystore (AES-GCM).
- Lưu lại app nào đã được tick.
- Nút mở app đã chọn.

## Build APK

Yêu cầu:

- Android Studio hỗ trợ Android Gradle Plugin 9.1.x
- JDK 17
- Android SDK Platform 36
- Internet cho Gradle Sync lần đầu

Mở thư mục `NexaFbProxy_V3`:

1. Chờ Gradle Sync.
2. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.
3. APK debug:
   `app/build/outputs/apk/debug/app-debug.apk`

## Lưu ý

App chỉ định tuyến lưu lượng mạng. Nó không thay đổi thông tin thiết bị,
GPS, timezone, fingerprint hoặc cơ chế bảo mật của Facebook/Messenger.
