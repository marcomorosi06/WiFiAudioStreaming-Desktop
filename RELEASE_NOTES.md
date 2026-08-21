# 🚀 WiFi Audio Streaming v1.2-rebuild (Desktop & Android)

**WiFi Audio Streaming `v1.2-rebuild`** là phiên bản tùy biến và xây dựng lại dựa trên nền tảng mã nguồn gốc mới nhất `v1.2` của tác giả Marco Morosi, được thực hiện và hoàn thiện bởi **Ali_ (`alithw`)**. 

Phiên bản này mang đến khả năng bản địa hóa toàn cầu với **20 ngôn ngữ**, giải quyết triệt để lỗi ghi nhớ mật khẩu mã hóa trên cả Desktop và Android, khắc phục các sự cố treo giao diện trình phát khi tự động kết nối, cùng nhiều cải tiến về độ ổn định.

---

## 🌟 Những điểm mới & Cải tiến nổi bật (What's New)

### 🌐 1. Hỗ trợ 20 Ngôn ngữ Toàn cầu & Bộ chọn ngôn ngữ tức thì (Dynamic Localization)
- **Đa ngôn ngữ toàn diện**: Tích hợp gói bản dịch đầy đủ cho 20 ngôn ngữ phổ biến nhất thế giới:
  - 🇻🇳 **Tiếng Việt**, 🇬🇧 **English**, 🇮🇹 **Italiano**, 🇪🇸 **Español**, 🇫🇷 **Français**, 🇩🇪 **Deutsch**, 🇵🇹 **Português**, 🇷🇺 **Русский**, 🇯🇵 **日本語**, 🇰🇷 **한국어**, 🇨🇳 **简体中文**, 🇹🇼 **繁體中文**, 🇸🇦 **العربية**, 🇮🇳 **हिन्दी**, 🇮🇩 **Bahasa Indonesia**, 🇹🇷 **Türkçe**, 🇵🇱 **Polski**, 🇳🇱 **Nederlands**, 🇹🇭 **ไทย**, 🇺🇦 **Українська**.
- **Chuyển đổi thời gian thực**: Bổ sung bộ chọn ngôn ngữ `LanguageSelector` trong mục **Cài đặt -> Giao diện (Settings -> Appearance)**. Khi thay đổi ngôn ngữ, toàn bộ giao diện sẽ cập nhật tức thì (Reactive Compose) mà không cần khởi động lại ứng dụng.
- **Đồng bộ hóa**: Hỗ trợ đồng nhất trên cả ứng dụng **Desktop** và ứng dụng **Android**.

---

### 🔐 2. Hệ thống Ghi nhớ Mật khẩu Mã hóa & Tự động Đăng nhập (Encrypted Vault)
- **Bảo mật tuyệt đối cấp hệ điều hành**:
  - **Desktop**: Tích hợp `SecretVault` sử dụng **Windows DPAPI** (`CryptProtectData`), **macOS Keychain**, và **Linux SecretService**.
  - **Android**: Tích hợp `SecretStore` sử dụng **Android Keystore (AES256-GCM / TEE)** kết hợp `EncryptedSharedPreferences`.
- **Trải nghiệm kết nối liền mạch**:
  - Khi tick chọn *"Ghi nhớ mật khẩu"* lúc kết nối, khóa bí mật sẽ được lưu an toàn theo định danh server (`IP`, `hostname`, `IP:Port`).
  - Khi ngắt kết nối (`Disconnect`), mật khẩu **không bị mất**. Các lần kết nối tiếp theo sẽ tự động handshake xác thực HMAC-SHA256 mà không làm phiền người dùng.
- **Cơ chế Auto-Forget thông minh**:
  - Khi server thay đổi mật khẩu hoặc phản hồi `WFAS_UNAUTHORIZED`, client sẽ tự động xóa mật khẩu cũ trong Vault và hiển thị ngay hộp thoại để người dùng nhập mã mới.
- **Quản lý máy chủ đã lưu (Saved Servers)**:
  - Bổ sung tab quản lý thiết bị đã lưu trong **Cài đặt (Settings)**, cho phép xem danh sách và nhấn nút **Quên (Forget)** khi cần.

---

### 🛠️ 3. Khắc phục lỗi Trình phát & Luồng Tự động Kết nối (Auto-Connect & Player Fixes)
- **Sửa lỗi kẹt màn hình khi đánh dấu sao (`*`)**:
  - Trước đây, khi đánh dấu sao một thiết bị có đặt mật khẩu trong danh sách quét, hệ thống chuyển thẳng vào giao diện phát (`isStreaming = true`) nhưng lại chặn popup hỏi mật khẩu, khiến kết nối thất bại và người dùng bị kẹt trong màn hình phát, buộc phải ngắt kết nối thủ công.
  - **Đã sửa**: Luồng kết nối hiện kiểm tra Vault trước. Nếu chưa có mật khẩu hoặc mật khẩu sai, **hộp thoại nhập mật khẩu sẽ xuất hiện trực tiếp ngay lập tức**.
  - Bổ sung mã trạng thái `status_key_required` vào `clientDisconnectKeys` để khi người dùng nhấn Hủy (Cancel), trạng thái phát được giải phóng và ứng dụng quay về danh sách quét thiết bị bình thường.
- **Tự động kích hoạt Client Auto-Connect**:
  - Khi người dùng nhấn nút `*` trên một thiết bị trong danh sách quét, hệ thống tự động bật chế độ Auto-Connect để thiết bị được kết nối ngay khi tìm thấy.

---

### ♾️ 4. Chế độ Duy trì Máy chủ Unicast (Server Persist Mode)
- Khi phát trực tiếp ở chế độ 1-1 (Unicast), server sẽ tiếp tục chạy nền và lắng nghe kết nối mới kể cả khi client hiện tại ngắt kết nối, không bị tắt đột ngột.

---

## 📦 Danh sách các tệp phát hành (Release Assets)

### 💻 Dành cho Máy tính (Desktop - Windows / Linux / macOS)
- 🪟 **Windows Portable ZIP**: `WiFi-Audio-Streaming-1.2.0-windows-x86_64.zip` (`140.64 MB`)
- 🪟 **Windows Portable Tarball**: `WiFi-Audio-Streaming-1.2.0-windows-x86_64.tar.gz` (`140.43 MB`)
- 📁 **Thư mục Portable Windows**: `WiFi-Audio-Streaming-Portable` (Chạy trực tiếp `WiFi Audio Streaming.exe` hoặc CLI `wfas.cmd`)
- 🐧 **Linux (x86_64 & ARM64)**: `.AppImage`, `.deb`, `.rpm` *(Được tự động biên dịch qua GitHub Actions)*
- 🍏 **macOS (Apple Silicon & Intel)**: `.dmg` *(Được tự động biên dịch qua GitHub Actions)*

### 📱 Dành cho Android
- 📱 **Android Package**: `WiFi-Audio-Streaming-Android-v1.2-rebuild.apk` (`37.52 MB`)

---

## 📜 Thông tin Bản quyền & Đóng góp (Credits)
- **Dự án gốc (Original Author)**: [Marco Morosi](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop) (Bản quyền EUPL-1.2)
- **Bản dựng & Tùy biến (Rebuilt by)**: **Ali_ (`alithw`)**
  - Desktop Fork: [github.com/alithw/WiFiAudioStreaming-Desktop](https://github.com/alithw/WiFiAudioStreaming-Desktop)
  - Android Fork: [github.com/alithw/WiFiAudioStreaming-Android](https://github.com/alithw/WiFiAudioStreaming-Android)
