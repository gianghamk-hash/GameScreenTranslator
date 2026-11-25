# Hướng dẫn bật OTA tự động 1 chạm (GitHub Actions)

## Bước 1: Thiết lập chữ ký cố định (làm 1 lần duy nhất)
Để mọi bản APK build trên GitHub đều cài đè lên nhau không bị lỗi xung đột gói:

1. Mở PowerShell hoặc Terminal tạo 1 file keystore:
   ```powershell
   keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias gametranslator
   ```
2. Chuyển file `release.jks` sang chuỗi Base64:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
   ```
3. Vào repo GitHub → **Settings → Secrets and variables → Actions → New repository secret**, thêm 4 mục:
   - `KEYSTORE_BASE64`: dán chuỗi Base64 vừa copy
   - `KEYSTORE_PASSWORD`: mật khẩu keystore bạn đặt
   - `KEY_ALIAS`: `gametranslator`
   - `KEY_PASSWORD`: mật khẩu key bạn đặt

*(Nếu chưa thêm 4 Secret trên, GitHub Actions vẫn tự build được bản Debug APK bình thường, nhưng khi đổi máy hoặc cài đè bản khác chữ ký sẽ cần gỡ bản cũ trước.)*

## Bước 2: Phát hành bản mới cực nhanh (2 cách)
- **Cách 1 (Không cần gõ lệnh):** Lên GitHub → tab **Actions** → chọn **Build & Release OTA APK** → bấm **Run workflow** → gõ tên bản mới (vd `v2.3`) → bấm **Run**.
- **Cách 2 (Dùng Git):**
  ```bash
  git tag v2.3
  git push origin v2.3
  ```

Sau khoảng 2 phút, bản Release kèm file `.apk` sẽ tự xuất hiện trên GitHub. Khi mở app trên điện thoại/máy chơi game, app sẽ **tự động phát hiện bản mới và hiện thông báo cài ngay**.
