# LightPlayer cho Android

Trình phát video nhẹ (Kotlin + Media3/ExoPlayer), bản đồng hành của LightPlayer desktop.

## Tính năng

- **Thư mục video riêng** `/storage/emulated/0/LightPlayer` (hoặc thư mục bạn chọn, kể cả thẻ SD).
  Nằm ngoài dữ liệu app nên **gỡ app / xóa dữ liệu app không mất video**.
- **Thêm video vào thư mục:** nút ＋ → chọn nhiều file → app chép vào thư mục, có thanh tiến trình,
  tùy chọn *xóa bản gốc sau khi chép xong*. Cũng có thể chép file thẳng vào thư mục bằng trình quản lý file.
- **Thumbnail:** ưu tiên ảnh bìa nhúng sẵn (MKV attachment, MP4/M4A `covr`, MP3 APIC); không có thì lấy khung hình ở ~10% thời lượng. Có cache.
- **Xem trước:** *giữ lâu* vào một video → **Xem trước**. Video tự chạy lần lượt các đoạn (10% → 30% → 50% → 70% → 90%, không tiếng),
  **thả tay ra vẫn tiếp tục chạy**. Chạm vào khung để mở phát đúng vị trí đó; chạm ra ngoài / Back để đóng.
- **Preview trên thanh tua:** kéo thanh tua trong trình phát sẽ hiện khung hình tại vị trí đó.
- **Ẩn khỏi thư viện (Gallery):** menu ⋮ → *Ẩn khỏi thư viện*. App thêm dấu `.` vào đầu tên file
  (`phim.mkv` → `.phim.mkv`, cả phụ đề `.srt/.vtt` cùng tên). Gallery bỏ qua file bắt đầu bằng `.`;
  **trong LightPlayer tên vẫn hiện bình thường, không có dấu chấm**. Video thêm mới sau đó cũng tự thêm dấu `.`.
  Tắt tùy chọn sẽ bỏ dấu `.` trả lại như cũ.
- **Trình phát:** chạm đúp trái/phải tua ∓10 giây, chạm đúp giữa = phát/tạm dừng, vuốt dọc bên trái = độ sáng,
  bên phải = âm lượng, **giữ để phát 2×** (thả ra về tốc độ cũ), nút ⟳ xoay màn hình, tự nhớ vị trí xem dở,
  tự nạp phụ đề `.srt/.vtt` cùng tên, chọn audio/phụ đề nhúng trong menu của trình phát.
- Mở video từ app khác (*Open with* → LightPlayer).

## Build bằng GitHub Actions

1. Tạo repo GitHub, đẩy **nội dung thư mục này** lên (thư mục `.github` phải nằm ở gốc repo).
2. Phát hành bản APK:
   - Vào tab **Actions → Build APK → Run workflow**, nhập phiên bản (vd `1.0.0`), hoặc
   - `git tag v1.0.0 && git push origin v1.0.0`
3. Chờ build xong, APK `LightPlayer-1.0.0.apk` nằm trong **Releases** của repo (và trong Artifacts của lần chạy).

### Ký APK ổn định (làm 1 lần, nên làm)

Nếu **không** cấu hình, mỗi lần build dùng keystore tạm khác nhau → cập nhật đè lên bản cũ sẽ báo
*"xung đột chữ ký"*, phải gỡ bản cũ. Để các bản cài đè lên nhau được:

```powershell
# 1) Tạo keystore (cần JDK; nhớ mật khẩu bạn đặt)
keytool -genkeypair -v -keystore lightplayer.jks -alias lightplayer -keyalg RSA -keysize 2048 -validity 36500

# 2) Mã hóa base64 (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("lightplayer.jks")) | Set-Clipboard
```

Vào repo → **Settings → Secrets and variables → Actions → New repository secret**, tạo 4 secret:

| Secret | Giá trị |
|---|---|
| `KEYSTORE_BASE64` | chuỗi base64 vừa copy |
| `KEYSTORE_PASSWORD` | mật khẩu keystore |
| `KEY_ALIAS` | `lightplayer` |
| `KEY_PASSWORD` | mật khẩu key (thường giống mật khẩu keystore) |

**Giữ file `lightplayer.jks` cẩn thận, đừng đưa lên repo** (đã có trong `.gitignore`). Mất file này là không cập nhật đè được nữa.

## Quyền truy cập

- **Android 11+:** lần đầu mở app bấm *Cấp quyền* → bật *Cho phép quản lý tất cả tệp* cho LightPlayer.
  Cần quyền này để app tự tạo `/LightPlayer`, đổi tên file (ẩn/hiện) và đọc video trực tiếp trong thư mục.
- **Android 7–10:** quyền lưu trữ thông thường (hộp thoại hệ thống).

## Lưu ý

- **AV1 / HEVC 10-bit:** phát được hay không tùy chip của máy (giải mã phần cứng, hoặc bộ giải mã phần mềm có sẵn từ Android 10).
  Nếu máy không giải mã được, app báo lỗi và nhảy sang video kế tiếp.
- Ẩn bằng dấu `.`: video **đã** được Gallery quét trước đó có thể còn hiện thêm một lúc; app đã yêu cầu quét lại để cập nhật nhanh hơn.
  Đây không phải bảo mật, trình quản lý file vẫn thấy file (nếu bật hiện file ẩn).
- Nhập video chỉ hỗ trợ chọn file (chọn được nhiều file một lúc), chưa có nhập cả thư mục.
