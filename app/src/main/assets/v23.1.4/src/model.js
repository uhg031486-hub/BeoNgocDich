function execute() {
    return Response.success([
        {
            id: "ag/gemini-3.8-flash",
            name: "Gemini 3.8 Flash (Mặc định Siêu Tốc 2-3s - Tắt suy nghĩ)",
            description: "Bản Flash thuần ép tắt suy nghĩ (reasoning disabled), nhả chữ tức thì sau 2-3s, siêu tốc đọc trực tiếp",
            isNetworkRequired: true
        },
        {
            id: "Kimi/kimi",
            name: "Kimi (Moonshot AI - Cứu hộ kiểm duyệt)",
            description: "Chuyên trị kiểm duyệt, văn phong Hán Việt mượt mà, sâu sắc",
            isNetworkRequired: true
        },
        {
            id: "glm/glm-4.5-flash",
            name: "GLM-4.5-Flash (Zhipu AI - Cứu hộ)",
            description: "Chuyên tiểu thuyết mạng Trung-Việt, độc lập không dính Safety Google",
            isNetworkRequired: true
        },
        {
            id: "cx/gpt-6-luna",
            name: "GPT-6 Luna Cứu Hộ (Siêu tốc)",
            description: "Văn phong đỉnh cao, vượt qua mọi cảnh nhạy cảm",
            isNetworkRequired: true
        },
        {
            id: "Kilo_Code_Free",
            name: "Kilo Code Free (Dự phòng miễn phí)",
            description: "Mô hình Kilo Code miễn phí tốc độ cao",
            isNetworkRequired: true
        },
        {
            id: "gemini/gemini-3.5-flash-lite",
            name: "Gemini 3.5 Flash Lite (Dự phòng tốc độ cao)",
            description: "Bản Flash Lite siêu tốc, bền bỉ, tiết kiệm tài nguyên",
            isNetworkRequired: true
        }
    ]);
}