# Hiếu Dịch Truyện

Ứng dụng Android dịch truyện `.epub` (mặc định) hoặc `.txt` sang tiếng Việt qua 9Router và lưu lại cùng định dạng.

## Dùng ứng dụng

1. Mở app, chọn EPUB hoặc TXT và nhập `9Router API key`.
2. Chọn model; mặc định là `Gemini Flash 3.8 (minimal)` và 5 luồng dịch.
3. Nhấn dịch, đợi app quét tên/quan hệ toàn truyện rồi dịch mỗi request một chương. Chương vượt context sẽ được tách kỹ thuật.
4. Chọn nơi lưu tệp kết quả khi Android hỏi.

Khóa API và khóa server (nếu server yêu cầu) chỉ lưu mã hóa bằng Android Keystore. URL 9Router, Tailscale và Funnel đã được điền theo extension V23.1.4.

## Đồng bộ VBook

Mỗi chương được xử lý theo thứ tự: cập nhật bộ nhớ tên/quan hệ, đồng bộ bộ nhớ lên server (Tailscale trước, Funnel dự phòng), sau đó mới lưu bản gốc/bản dịch của chương. Bản ghi lỗi sẽ ở hàng đợi cục bộ để gửi lại lần sau.

Để VBook dùng lại cache từ APK, mở **Cài đặt nâng cao** và nhập cả **Mã truyện VBook** và **Nguồn truyện VBook** đúng như `localBook.getInfo()` của VBook. Khi cả hai khớp, app dùng khóa cache `v231-chapter:*` và scope bộ nhớ `v231-story-v2:*` giống extension. Cache chỉ được VBook phát lại nếu văn bản chương gốc và các thiết lập ảnh hưởng cache (model, thể loại, từ điển, ghi chú) trùng khớp. Nếu server đòi bearer token, điền một lần vào `Khóa server`.

## Build

Yêu cầu Android SDK 35 và JDK 17:

```powershell
./gradlew.bat :app:assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.
