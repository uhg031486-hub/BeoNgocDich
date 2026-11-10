var PRIMARY_HOST = "https://timo-ngocbeo.pages.dev";
var BACKUP_HOST = "https://distributor-warranty-statutes-hidden.trycloudflare.com";

function stripChinese(text) {
    if (!text) return "";
    return text.replace(/[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]+/g, "").replace(/\s+/g, " ").trim();
}

function execute(url, page) {
    if (!page) page = "1";
    var pageNum = parseInt(page, 10) || 1;
    var limit = 20;
    var offset = (pageNum - 1) * limit;

    var targetUrl = url;
    if (targetUrl.indexOf(BACKUP_HOST) === 0) {
        targetUrl = targetUrl.replace(BACKUP_HOST, PRIMARY_HOST);
    }
    var sep = targetUrl.indexOf("?") !== -1 ? "&" : "?";
    targetUrl = targetUrl + sep + "limit=" + limit + "&offset=" + offset;

    var res = null;
    try {
        res = fetch(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
        });
    } catch (e1) {}

    var json = null;
    if (res && res.ok) {
        try { json = res.json(); } catch (eJson) {
            try { json = JSON.parse(res.text()); } catch (eTxt) {}
        }
    }

    // Fallback nếu máy chủ chính bị gián đoạn
    if (!json || !json.success || !json.books) {
        try {
            var backupUrl = targetUrl.replace(PRIMARY_HOST, BACKUP_HOST);
            var res2 = fetch(backupUrl, {
                headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
            });
            if (res2 && res2.ok) {
                try { json = res2.json(); } catch (eJ2) {
                    try { json = JSON.parse(res2.text()); } catch (eT2) {}
                }
            }
        } catch (eFallback) {}
    }

    if (json && json.success && json.books) {
        var books = json.books;
        var data = [];

        for (var i = 0; i < books.length; i++) {
            var b = books[i];
            var bid = b.bid;
            var title = stripChinese(b.display_title || b.title || ("Truyện #" + bid));
            var author = stripChinese(b.display_author || b.author || "Vô Danh");
            var transCount = b.translated_chapters || 0;
            var totalCount = b.total_chapters_timo || transCount;
            var isFinished = b.is_finished || (b.status === "completed") || (transCount >= totalCount && totalCount > 0);
            var statusStr = isFinished ? "Hoàn thành" : "Đang ra";

            var cover = b.cover_url || ("/api/books/" + bid + "/cover");
            if (cover.indexOf("http") !== 0) {
                cover = PRIMARY_HOST + cover;
            }
            if (cover.indexOf("?") === -1) {
                cover = cover + "?v=30";
            } else {
                cover = cover + "&v=30";
            }

            var desc = stripChinese(b.display_desc || b.description || "");
            var descFormatted = "Tác giả: " + author + " • " + transCount + "/" + totalCount + " chương (" + statusStr + ")";
            if (desc) {
                descFormatted += "\n" + desc;
            }

            data.push({
                name: title,
                link: PRIMARY_HOST + "/api/books/" + bid,
                cover: cover,
                description: descFormatted,
                host: PRIMARY_HOST
            });
        }

        var total = json.total || 0;
        if (offset + books.length < total && books.length >= limit) {
            var nextPage = (pageNum + 1).toString();
            return Response.success(data, nextPage);
        }
        return Response.success(data);
    }

    return Response.error("Không thể kết nối kho dịch truyện. Vui lòng kiểm tra lại mạng.");
}
