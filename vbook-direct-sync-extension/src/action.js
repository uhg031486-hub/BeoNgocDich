function execute() {
    var actions = {};
    try {
        var book = localBook.getInfo();
        if (book && book.id) {
            actions.sync_downloaded = {
                name: "Dịch & đồng bộ chương đã tải",
                description: "Dịch các chương gốc đã lưu/cached của truyện này, ghi cache V23, rồi đồng bộ tên và quan hệ lên server.",
                script: "sync_downloaded.js"
            };
        }
    } catch (error) {}
    return Response.success(actions);
}
