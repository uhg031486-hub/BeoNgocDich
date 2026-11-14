function execute() {
    return Response.success([
        {
            id: "ag/gemini-3.8-flash",
            name: "Gemini 3.8 Flash (Mặc định Nhanh & Chuẩn - Low Think)",
            description: "Mặc định ổn định, 0 sót chữ Hán, văn phong tự nhiên (ép reasoning_effort low, ~10-15s)",
            isNetworkRequired: true
        },
        {
            id: "gemini/gemini-3.5-flash-lite",
            name: "Gemini 3.5 Flash Lite (Siêu Tốc 3-5s)",
            description: "Phản hồi siêu nhanh ~3-5s, đọc lướt mượt mà",
            isNetworkRequired: true
        },
        {
            id: "ag/gemini-3.8-flash-low",
            name: "Gemini 3.8 Flash Low (Ổn định & Ít tốn quota)",
            description: "Bản Gemini 3.8 Flash Low chuyên tốc độ cao, zero suy nghĩ thừa",
            isNetworkRequired: true
        },
        {
            id: "kc/baidu/ernie-5.5-flash-preview:free",
            name: "ERNIE 5.5 Flash Free (Cứu hộ kiểm duyệt - 21 Keys)",
            description: "Vượt cảnh nhạy cảm, sát phạt, am hiểu văn phong tiên hiệp Trung Quốc",
            isNetworkRequired: true
        },
        {
            id: "openrouter/cohere/north-mini-code:free",
            name: "Cohere North Free (Dự phòng độc lập)",
            description: "Mô hình Cohere qua OpenRouter độc lập, không dính bộ lọc Google",
            isNetworkRequired: true
        },
        {
            id: "oc/deepseek-v4.1-flash-free",
            name: "DeepSeek V4.1 Flash Free (Cứu hộ sâu)",
            description: "DeepSeek miễn phí qua OpenCode, vượt kiểm duyệt tốt",
            isNetworkRequired: true
        }
    ]);
}