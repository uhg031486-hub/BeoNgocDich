function execute() {
    return Response.success([
        {
            id: "ag/gemini-3.8-flash-low",
            name: "Gemini 3.8 Flash (Low Thinking)",
            description: "Mặc định V23.1.9: Siêu tốc 4.5s, không delay suy luận, văn phong hài bựa lầy lội",
            isNetworkRequired: true
        },
        {
            id: "cx/gpt-6-luna",
            name: "GPT-6 Luna Cứu Hộ",
            description: "Chuyên trị kiểm duyệt, văn phong đỉnh cao, vượt qua mọi cảnh nhạy cảm",
            isNetworkRequired: true
        },
        {
            id: "glm/glm-4.5-flash",
            name: "GLM-4.5-Flash (Zhipu AI)",
            description: "Chuyên tiểu thuyết mạng Trung-Việt, tốc độ cao, độc lập không dính Safety Google",
            isNetworkRequired: true
        },
        {
            id: "LauThapCam_Low",
            name: "Lẩu Thập Cẩm (Auto Redundant)",
            description: "Xoay tua tự động Luna và Flash",
            isNetworkRequired: true
        }
    ]);
}
