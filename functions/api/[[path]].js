export async function onRequest(context) {
  const req = context.request;

  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS, HEAD",
        "Access-Control-Allow-Headers": "*",
        "Access-Control-Max-Age": "86400"
      }
    });
  }

  const url = new URL(req.url);
  const targetBase = "https://distributor-warranty-statutes-hidden.trycloudflare.com";

  // =========================================================================
  // 1. STATIC FRONTEND ACCELERATION (Zero VPS Load, Edge Speed 2-20ms)
  // =========================================================================

  // A. Static Catalog Delivery: GET /api/books (default view without complex filters)
  if (url.pathname === "/api/books" && (!url.search || url.search === "?limit=1000") && (req.method === "GET" || req.method === "HEAD")) {
    try {
      const catResp = await fetch(`${url.origin}/catalog.json`);
      if (catResp.ok) {
        const catHeaders = new Headers(catResp.headers);
        catHeaders.set("Content-Type", "application/json; charset=utf-8");
        catHeaders.set("Access-Control-Allow-Origin", "*");
        catHeaders.set("Cache-Control", "public, max-age=60, s-maxage=300, stale-while-revalidate=600");
        catHeaders.set("CF-Edge-Cache", "HIT-STATIC-CATALOG");
        return new Response(req.method === "HEAD" ? null : catResp.body, {
          status: 200,
          headers: catHeaders
        });
      }
    } catch (eCat) {}
  }

  // B. Static Chapter Delivery: GET /api/books/<bid>/chapters/<ch_no>
  const chapMatch = url.pathname.match(/^\/api\/books\/(\d+)\/chapters\/(\d+)$/);
  if (chapMatch && (req.method === "GET" || req.method === "HEAD")) {
    const bid = chapMatch[1];
    const chNo = parseInt(chapMatch[2], 10);

    try {
      const bundleResp = await fetch(`${url.origin}/data/${bid}.json`);
      if (bundleResp.ok) {
        const bundle = await bundleResp.json();
        if (bundle && bundle.chapters && bundle.chapters[chNo]) {
          const chData = bundle.chapters[chNo];
          const chNums = Object.keys(bundle.chapters).map(Number).sort((a, b) => a - b);
          const idx = chNums.indexOf(chNo);
          const prevNo = idx > 0 ? chNums[idx - 1] : null;
          const nextNo = idx < chNums.length - 1 ? chNums[idx + 1] : null;

          const payload = {
            success: true,
            bid: bid,
            book_title: bundle.title || `Truyện #${bid}`,
            chapter_no: chNo,
            title: chData.title || `Chương ${chNo}`,
            content: chData.content || "",
            prev_no: prevNo,
            next_no: nextNo
          };

          return new Response(req.method === "HEAD" ? null : JSON.stringify(payload), {
            status: 200,
            headers: {
              "Content-Type": "application/json; charset=utf-8",
              "Access-Control-Allow-Origin": "*",
              "Cache-Control": "public, max-age=86400, s-maxage=2592000, stale-while-revalidate=86400",
              "CF-Edge-Cache": "HIT-STATIC-BUNDLE"
            }
          });
        }
      }
    } catch (eBundle) {}
  }

  // C. Static Book Details & Chapters List: GET /api/books/<bid>
  const bookMatch = url.pathname.match(/^\/api\/books\/(\d+)$/);
  if (bookMatch && (req.method === "GET" || req.method === "HEAD")) {
    const bid = bookMatch[1];
    try {
      const bundleResp = await fetch(`${url.origin}/data/${bid}.json`);
      if (bundleResp.ok) {
        const bundle = await bundleResp.json();
        if (bundle && bundle.chapters) {
          const chNums = Object.keys(bundle.chapters).map(Number).sort((a, b) => a - b);
          const chaptersList = chNums.map(no => ({
            chapter_no: no,
            title: (bundle.chapters[no] && bundle.chapters[no].title) ? bundle.chapters[no].title : `Chương ${no}`,
            status: "translated"
          }));

          const payload = {
            success: true,
            book: {
              bid: bid,
              display_title: bundle.title || `Truyện #${bid}`,
              display_author: bundle.author || "Vô Danh",
              translated_chapters: chNums.length,
              chapters: chaptersList
            }
          };

          return new Response(req.method === "HEAD" ? null : JSON.stringify(payload), {
            status: 200,
            headers: {
              "Content-Type": "application/json; charset=utf-8",
              "Access-Control-Allow-Origin": "*",
              "Cache-Control": "public, max-age=86400, s-maxage=2592000, stale-while-revalidate=86400",
              "CF-Edge-Cache": "HIT-STATIC-BOOK-DETAIL"
            }
          });
        }
      }
    } catch (eBook) {}
  }

  // D. Static Cover Delivery: GET /api/books/<bid>/cover
  const coverMatch = url.pathname.match(/^\/api\/books\/(\d+)\/cover$/);
  if (coverMatch && (req.method === "GET" || req.method === "HEAD")) {
    const bid = coverMatch[1];
    try {
      const coverResp = await fetch(`${url.origin}/covers/${bid}.jpg`);
      const ct = (coverResp.headers.get("content-type") || "").toLowerCase();
      if (coverResp.ok && ct.includes("image")) {
        const cHeaders = new Headers(coverResp.headers);
        cHeaders.set("Content-Type", "image/jpeg");
        cHeaders.set("Access-Control-Allow-Origin", "*");
        cHeaders.set("Cache-Control", "public, max-age=604800, s-maxage=2592000, stale-while-revalidate=86400");
        cHeaders.set("CF-Edge-Cache", "HIT-STATIC-COVER");
        return new Response(req.method === "HEAD" ? null : coverResp.body, {
          status: 200,
          headers: cHeaders
        });
      }
    } catch (eCov) {}
  }

  // =========================================================================
  // 2. CLOUDFLARE EDGE RAM CACHE (caches.default) FOR DYNAMIC REQUESTS
  // =========================================================================
  const cache = caches.default;
  const isCover = url.pathname.includes("/cover");
  const isFull = url.searchParams.has("full") || url.searchParams.has("raw");

  // Normalize cache key for covers: support versioning ?v=... to allow instant cache-busting
  const vParam = url.searchParams.get("v") || "";
  const cacheKeyUrl = isCover 
    ? (url.origin + url.pathname + (isFull ? "?full=1" : "") + (vParam ? `?v=${vParam}` : "")) 
    : req.url;
  const cacheKey = new Request(cacheKeyUrl, { method: "GET" });

  if (req.method === "GET" || req.method === "HEAD") {
    try {
      const cachedResponse = await cache.match(cacheKey);
      if (cachedResponse) {
        const hitHeaders = new Headers(cachedResponse.headers);
        hitHeaders.set("CF-Edge-Cache", "HIT");
        hitHeaders.set("Access-Control-Allow-Origin", "*");
        if (req.method === "HEAD") {
          return new Response(null, {
            status: cachedResponse.status,
            headers: hitHeaders
          });
        }
        return new Response(cachedResponse.body, {
          status: cachedResponse.status,
          headers: hitHeaders
        });
      }
    } catch (eCache) {}
  }

  // =========================================================================
  // 3. PROXY TO ORIGIN VPS VIA HTTP/2 TUNNEL
  // =========================================================================
  const targetUrl = targetBase + url.pathname + url.search;

  try {
    const fetchHeaders = new Headers();
    for (const [key, value] of req.headers.entries()) {
      if (!["host", "cf-connecting-ip", "cf-ray", "cf-ipcountry"].includes(key.toLowerCase())) {
        fetchHeaders.set(key, value);
      }
    }
    fetchHeaders.set("Accept-Encoding", "gzip");

    const fetchOptions = {
      method: req.method,
      headers: fetchHeaders
    };

    if (req.method !== "GET" && req.method !== "HEAD") {
      fetchOptions.body = await req.arrayBuffer();
    }

    let response;
    try {
      response = await fetch(targetUrl, fetchOptions);
      if (!response.ok && response.status >= 502) {
        throw new Error(`Tunnel status ${response.status}`);
      }
    } catch (eTunnel) {
      // Retry once after brief pause if tunnel hiccuped
      await new Promise(r => setTimeout(r, 400));
      response = await fetch(targetUrl, fetchOptions);
    }

    const resHeaders = new Headers(response.headers);
    resHeaders.set("Access-Control-Allow-Origin", "*");
    resHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
    resHeaders.set("Access-Control-Allow-Headers", "*");
    resHeaders.set("CF-Edge-Cache", "MISS");

    // Smart Caching TTL
    if (isCover) {
      resHeaders.set("Cache-Control", "public, max-age=604800, s-maxage=2592000, stale-while-revalidate=86400");
    } else if (url.pathname.includes("/chapters/")) {
      resHeaders.set("Cache-Control", "public, max-age=86400, s-maxage=604800, stale-while-revalidate=86400");
    } else if (url.pathname.includes("/epub") || url.pathname.includes("/download")) {
      // EPUB is on-demand, no long-term edge cache so user always gets latest
      resHeaders.set("Cache-Control", "private, no-cache");
    } else {
      resHeaders.set("Cache-Control", "public, max-age=60, s-maxage=300, stale-while-revalidate=300");
    }

    const finalResponse = new Response(req.method === "HEAD" ? null : response.body, {
      status: response.status,
      headers: resHeaders
    });

    // Save to Cloudflare Edge Cache if successful GET (excluding EPUB downloads)
    if (req.method === "GET" && response.status === 200 && !url.pathname.includes("/epub") && !url.pathname.includes("/download")) {
      try {
        context.waitUntil(cache.put(cacheKey, finalResponse.clone()));
      } catch (ePut) {}
    }

    return finalResponse;
  } catch (err) {
    return new Response(JSON.stringify({ success: false, error: "Lỗi kết nối VPS: " + err.message }), {
      status: 502,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Access-Control-Allow-Origin": "*"
      }
    });
  }
}
