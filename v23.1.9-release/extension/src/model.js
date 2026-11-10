function execute() {
    return Response.success([
        {
            id: "ag/gemini-3.8-flash",
            name: "Gemini 3.8 Flash (Mặc định - Khuyên dùng)",
            description: "Bản Flash tiêu chuẩn siêu tốc 3-4s, ổn định cao, không timeout 504",
            isNetworkRequired: true
        },
        {
            id: "cx/gpt-6-luna",
            name: "GPT-6 Luna Cứu Hộ (Siêu tốc)",
            description: "Chuyên trị kiểm duyệt, văn phong đỉnh cao, vượt qua mọi cảnh nhạy cảm",
            isNetworkRequired: true
        },
        {
            id: "Kilo_Code_Free",
            name: "Kilo Code Free (Dự phòng miễn phí)",
            description: "Mô hình Kilo Code miễn phí tốc độ cao, không dính kiểm duyệt Google",
            isNetworkRequired: true
        },
        {
            id: "glm/glm-4.5-flash",
            name: "GLM-4.5-Flash (Zhipu AI)",
            description: "Chuyên tiểu thuyết mạng Trung-Việt, độc lập không dính Safety Google",
            isNetworkRequired: true
        },
        {
            id: "Kimi/kimi",
            name: "Kimi (Moonshot AI)",
            description: "Văn phong mượt mà sâu sắc",
            isNetworkRequired: true
        },
        {
            id: "gemini/gemini-3.5-flash-lite",
            name: "Gemini 3.5 Flash Lite (Dự phòng tốc độ cao)",
            description: "Bản Flash Lite siêu tốc, bền bỉ, tiết kiệm tài nguyên",
            isNetworkRequired: true
        },
        {
            id: "LauThapCam_High",
            name: "Lẩu Thập Cẩm Cao Cấp",
            description: "Xoay tua tự động các pool chất lượng cao",
            isNetworkRequired: true
        }
    ]);
}
