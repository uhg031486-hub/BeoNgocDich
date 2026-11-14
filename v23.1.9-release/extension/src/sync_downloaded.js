load('translate.js');

var V231_SYNC_DOWNLOADED_TRANSLATE = execute;

function v231ActionResult(result) {
    if (result && typeof result === 'object' && result.code !== undefined) return result;
    return v231JSON(String(result || ''), null);
}

function v231ActionReply(ok, message) {
    if (typeof Response !== 'undefined' && Response) {
        if (ok && typeof Response.success === 'function') return Response.success(message);
        if (!ok && typeof Response.error === 'function') return Response.error(message);
    }
    return JSON.stringify({code: ok ? 0 : 1, data: message});
}

execute = function() {
    var book = v231Book();
    if (!book) return v231ActionReply(false, 'Mở trang chi tiết của truyện rồi chạy lại action này.');
    if (!getApiKey()) return v231ActionReply(false, 'Chưa có 9Router API key.');

    var toc = [];
    try { toc = localBook.getTableOfContent() || []; } catch (error) {}
    if (!toc.length) return v231ActionReply(false, 'Truyện chưa có mục lục để quét chương đã tải.');

    var start = Math.max(1, Math.floor(numberValue(valueOf('sync_downloaded_start', 1), 1)));
    var configuredEnd = Math.floor(numberValue(valueOf('sync_downloaded_end', 0), 0));
    var end = configuredEnd > 0 ? Math.min(toc.length, configuredEnd) : toc.length;
    if (start > end) return v231ActionReply(false, 'Khoảng chương không hợp lệ.');

    var translated = 0, skipped = 0, failed = 0, errors = [];
    for (var index = start - 1; index < end; index++) {
        var chapter = toc[index] || {}, saved = null, original = '';
        try { saved = localBook.getChapterContent(chapter.position); } catch (readError) {}
        try { original = saved && saved.content && saved.content.raw ? String(saved.content.raw) : ''; } catch (contentError) {}
        if (!original.trim()) {
            skipped++;
            Log.log('Bỏ qua ' + (index + 1) + '/' + end + ': chưa tải/cached.');
            continue;
        }

        Log.log('Dịch và đồng bộ ' + (index + 1) + '/' + end + '...');
        try {
            // This follows the normal V23 route: server/local cache lookup, name and
            // relationship sync, then archive the chapter. The raw VBook string keeps
            // the cache key identical to reader translation.
            var result = v231ActionResult(V231_SYNC_DOWNLOADED_TRANSLATE(original, 'auto', 'vi', 'chapterContent'));
            if (result && Number(result.code) === 0 && String(result.data || '').trim()) {
                translated++;
            } else {
                failed++;
                errors.push((index + 1) + ': ' + String(result && result.data || 'không có kết quả').slice(0, 120));
            }
        } catch (error) {
            failed++;
            errors.push((index + 1) + ': ' + String(error && error.message || error).slice(0, 120));
        }
    }
    v231Flush();
    var summary = 'Đã xử lý ' + translated + ' chương; bỏ qua ' + skipped + ' chương chưa tải; lỗi ' + failed + '.';
    if (errors.length) summary += ' Lỗi: ' + errors.slice(0, 3).join(' | ');
    return v231ActionReply(failed === 0, summary);
}
