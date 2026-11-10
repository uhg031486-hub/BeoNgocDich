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

    if (!json || !json.success || !json.book || !json.book.chapters) {
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

    if (json && json.success && json.book && json.book.chapters) {
        var rawChapters = json.book.chapters;
        var chapters = [];

        for (var i = 0; i < rawChapters.length; i++) {
            var ch = rawChapters[i];
            var chNo = ch.chapter_no;
            var rawTitle = stripChinese(ch.title_vi || ch.title || ("Chương " + chNo));

            var chapUrl = PRIMARY_HOST + "/api/books/" + bid + "/chapters/" + chNo;

            chapters.push({
                name: rawTitle,
                url: chapUrl,
                host: PRIMARY_HOST
            });
        }

        return Response.success(chapters);
    }

    return Response.error("Không thể tải mục lục toàn thư.");
}
