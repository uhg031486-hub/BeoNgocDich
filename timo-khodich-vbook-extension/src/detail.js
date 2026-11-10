var PRIMARY_HOST = "https://timo-ngocbeo.pages.dev";
var BACKUP_HOST = "https://distributor-warranty-statutes-hidden.trycloudflare.com";

function stripChinese(text) {
    if (!text) return "";
    return text.replace(/[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]+/g, "").replace(/\s+/g, " ").trim();
}

function execute(url) {
    var match = url.match(/\/(\d+)/);
    if (!match) return null;
    var bid = match[1];

    var apiUrl = PRIMARY_HOST + "/api/books/" + bid;
    var res = null;
    try {
        res = fetch(apiUrl, {
            headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
        });
    } catch (e1) {}

    var json = null;
    if (res && res.ok) {
        try { json = res.json(); } catch (e) {
            try { json = JSON.parse(res.text()); } catch (e2) {}
        }
    }

    if (!json || !json.success || !json.book) {
        try {
            var res2 = fetch(BACKUP_HOST + "/api/books/" + bid, {
                headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
            });
            if (res2 && res2.ok) {
                try { json = res2.json(); } catch (e3) {
                    try { json = JSON.parse(res2.text()); } catch (e4) {}
                }
            }
        } catch (eFallback) {}
    }

    if (json && json.success && json.book) {
        var b = json.book;
        var title = stripChinese(b.display_title || b.title || ("Truyện #" + bid));
        var author = stripChinese(b.display_author || b.author || "Vô Danh");
        var genre = stripChinese(b.genre || "Huyền Huyễn");
        var channel = stripChinese(b.channel || "Nam Sinh");
        var transCount = b.translated_chapters || (b.chapters ? b.chapters.length : 0);
        var totalCount = b.total_chapters_timo || transCount;
        var isFinished = b.is_finished || (b.status === "completed") || (transCount >= totalCount && totalCount > 0);
        var statusStr = isFinished ? ("Hoàn thành (Đã dịch " + transCount + " chương)") : ("Đang ra (" + transCount + "/" + totalCount + " chương)");

        var cover = b.cover_url || ("/api/books/" + bid + "/cover");
        if (cover.indexOf("http") !== 0) {
            cover = PRIMARY_HOST + cover;
        }
        if (cover.indexOf("?") === -1) {
            cover = cover + "?v=30";
        } else {
            cover = cover + "&v=30";
        }

        var detailParts = [];
        detailParts.push("Tác giả: " + author);
        detailParts.push("Thể loại: " + genre + " • " + channel);
        detailParts.push("Trạng thái: " + statusStr);
        if (b.translation_engine || b.model) {
            detailParts.push("Mô hình AI: " + stripChinese(b.translation_engine || b.model));
        }
        detailParts.push("Kho truyện: Server Timo Ngoc Beo (100% Thuan Viet)");

        var desc = stripChinese(b.display_desc || b.description || "");

        return Response.success({
            name: title,
            cover: cover,
            author: author,
            description: desc,
            detail: detailParts.join("<br>"),
            ongoing: !isFinished,
            host: PRIMARY_HOST
        });
    }

    return Response.error("Không tìm thấy thông tin linh quyển.");
}
