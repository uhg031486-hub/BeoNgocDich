function execute() {
    return Response.success([
        {
            id: "gemini/gemini-3.5-flash-lite",
            name: "Gemini 3.5 Flash Lite (Mặc định - Khuyên dùng)",
            description: "Bản Flash Lite siêu tốc, bền bỉ, tiết kiệm tài nguyên, tự động dự phòng Flash 3.8 Low / Luna",
            isNetworkRequired: true
        },
        {
            id: "ag/gemini-3.8-flash-low",
            name: "Gemini 3.8 Flash Low (Dự phòng)",
            description: "Bản Flash 3.8 Low dự phòng hoạt động ổn định trên 9Router",
            isNetworkRequired: true
        },
        {
            id: "cx/gpt-6-luna",
            name: "GPT-6 Luna Cứu Hộ (Siêu tốc)",
            description: "Chuyên trị kiểm duyệt, văn phong đỉnh cao, vượt qua mọi cảnh nhạy cảm",
            isNetworkRequired: true
        },
        {
            id: "glm/glm-4.5-flash",
            name: "GLM-4.5-Flash (Zhipu AI)",
            description: "Chuyên tiểu thuyết mạng Trung-Việt, độc lập không dính Safety Google",
            isNetworkRequired: true
        },
        {
            id: "LauThapCam_High",
            name: "Lẩu Thập Cẩm Cao Cấp",
            description: "Xoay tua tự động Luna và Flash chất lượng cao",
            isNetworkRequired: true
        },
        {
            id: "Kimi/kimi",
            name: "Kimi (Moonshot AI)",
            description: "Văn phong mượt mà sâu sắc",
            isNetworkRequired: true
        },
        {
            id: "ag/gemini-3.8-flash",
            name: "Gemini 3.8 Flash (Upstream chậm)",
            description: "Bản Flash tiêu chuẩn (có thể bị nghẽn upstream)",
            isNetworkRequired: true
        }
    ]);
}
