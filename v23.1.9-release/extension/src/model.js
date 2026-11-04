function execute() {
    return Response.success([
        {
            id: "ag/gemini-3.8-flash-low",
            name: "Gemini 3.8 Flash (Low Thinking)",
            description: "Mặc định V23.1.9: Siêu tốc 4.5s, không delay suy luận, ổn định cao",
            isNetworkRequired: true
        },
        {
            id: "LauThapCam_Low",
            name: "Lẩu Thập Cẩm (Auto Redundant)",
            description: "Cụm AI tự động đảo model khi lỗi, tốc độ 3.5s",
            isNetworkRequired: true
        },
        {
            id: "gemini/gemini-3.5-flash-lite",
            name: "Gemini 3.5 Flash-Lite",
            description: "Bền bỉ, tiết kiệm",
            isNetworkRequired: true
        },
        {
            id: "cx/gpt-6-luna",
            name: "GPT-6 Luna Cứu Hộ",
            description: "Văn phong văn học cao cấp, vượt kiểm duyệt",
            isNetworkRequired: true
        }
    ]);
}
