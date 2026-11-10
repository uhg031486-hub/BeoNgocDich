var PRIMARY_HOST = "https://timo-ngocbeo.pages.dev";
var BACKUP_HOST = "https://distributor-warranty-statutes-hidden.trycloudflare.com";

function execute(url) {
    var targetUrl = url;
    if (targetUrl.indexOf(BACKUP_HOST) === 0) {
        targetUrl = targetUrl.replace(BACKUP_HOST, PRIMARY_HOST);
    }

    var res = null;
    try {
        res = fetch(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
        });
    } catch (e1) {}

    var json = null;
    if (res && res.ok) {
        try { json = res.json(); } catch (e) {
            try { json = JSON.parse(res.text()); } catch (e2) {}
        }
    }

    if (!json || !json.success || !json.content) {
        try {
            var backupUrl = targetUrl.replace(PRIMARY_HOST, BACKUP_HOST);
            var res2 = fetch(backupUrl, {
                headers: { "User-Agent": "Mozilla/5.0 vBook/TimoNgocBeoKhoDich" }
            });
            if (res2 && res2.ok) {
                try { json = res2.json(); } catch (e3) {
                    try { json = JSON.parse(res2.text()); } catch (e4) {}
                }
            }
        } catch (eFallback) {}
    }

    if (json && json.success && json.content) {
        var text = json.content;
        text = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
        text = text.replace(/[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]+/g, "");

        var lines = text.split("\n");
        var paragraphs = [];
        for (var i = 0; i < lines.length; i++) {
            var p = lines[i].trim();
            if (p) {
                paragraphs.push(p);
            }
        }

        // Tự động loại bỏ tiêu đề chương ở đầu nội dung vì vBook đã hiển thị tên chương ở thanh tiêu đề trên cùng
        var expectedTitle = (json.title || "").trim();
        var normExpected = expectedTitle.toLowerCase().replace(/[:\-\s]+/g, " ");

        while (paragraphs.length > 0) {
            var firstP = paragraphs[0].trim();
            var normFirst = firstP.toLowerCase().replace(/[:\-\s]+/g, " ");

            var isTitle = false;
            if (normExpected && (normFirst === normExpected || normFirst.indexOf(normExpected) === 0 || normExpected.indexOf(normFirst) === 0)) {
                isTitle = true;
            } else if (/^(chương|hồi|tiết|quyển|thứ)\s*([\divxlcdm]+|\d+)\b/i.test(firstP)) {
                isTitle = true;
            } else if (normExpected && normExpected.indexOf(normFirst) >= 0 && firstP.length < 80) {
                // Tiêu đề phụ nằm ở dòng riêng ngay sau Chương X
                isTitle = true;
            }

            if (isTitle) {
                paragraphs.shift();
            } else {
                break;
            }
        }

        var htmlContent = paragraphs.join("<br><br>");
        return Response.success(htmlContent);
    }

    return Response.error("Không thể tải nội dung chương. Vui lòng thử lại sau.");
}
