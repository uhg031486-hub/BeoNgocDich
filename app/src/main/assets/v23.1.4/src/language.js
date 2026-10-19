var V231_PRIVATE={router_key:'',server_key:''};
function valueOf(name, fallback) {
    try {
        if (typeof localConfig !== "undefined" && localConfig && typeof localConfig.getItem === "function") {
            var configured = localConfig.getItem(name);
            if (configured !== undefined && configured !== null && String(configured) !== "") return configured;
        }
    } catch (eLocalConfig) {}
    try {
        if (name === "api_key" && typeof api_key !== "undefined") return api_key;
        if (name === "apiKey" && typeof apiKey !== "undefined") return apiKey;
        if (name === "API_KEY" && typeof API_KEY !== "undefined") return API_KEY;
        if (name === "key" && typeof key !== "undefined") return key;
        if (name === "token" && typeof token !== "undefined") return token;
        if (name === "router_api_key" && typeof router_api_key !== "undefined") return router_api_key;
        if (name === "9router_api_key" && typeof _9router_api_key !== "undefined") return _9router_api_key;
        if (name === "endpoint_url" && typeof endpoint_url !== "undefined") return endpoint_url;
        if (name === "endpoint" && typeof endpoint !== "undefined") return endpoint;
        if (name === "base_url" && typeof base_url !== "undefined") return base_url;
        if (name === "baseUrl" && typeof baseUrl !== "undefined") return baseUrl;
        if (name === "model" && typeof model !== "undefined") return model;
        if (name === "reasoning_effort" && typeof reasoning_effort !== "undefined") return reasoning_effort;
        if (name === "temperature" && typeof temperature !== "undefined") return temperature;
        if (name === "api_page_chars" && typeof api_page_chars !== "undefined") return api_page_chars;
        if (name === "cache_pages" && typeof cache_pages !== "undefined") return cache_pages;
        if (name === "preserve_names" && typeof preserve_names !== "undefined") return preserve_names;
        if (name === "genre" && typeof genre !== "undefined") return genre;
        if (name === "custom_glossary" && typeof custom_glossary !== "undefined") return custom_glossary;
        if (name === "style_notes" && typeof style_notes !== "undefined") return style_notes;
        if (name === "input_mode" && typeof input_mode !== "undefined") return input_mode;
        if (name === "reading_mode" && typeof reading_mode !== "undefined") return reading_mode;
        if (name === "smart_chapter_switch" && typeof smart_chapter_switch !== "undefined") return smart_chapter_switch;
        if (name === "initial_chapter_model" && typeof initial_chapter_model !== "undefined") return initial_chapter_model;
        if (name === "next_chapter_model" && typeof next_chapter_model !== "undefined") return next_chapter_model;
        if (name === "offline_model" && typeof offline_model !== "undefined") return offline_model;
        if (name === "initial_chapter_chars" && typeof initial_chapter_chars !== "undefined") return initial_chapter_chars;
        if (name === "next_chapter_chars" && typeof next_chapter_chars !== "undefined") return next_chapter_chars;
        if (name === "session_timeout_minutes" && typeof session_timeout_minutes !== "undefined") return session_timeout_minutes;
    } catch (eBindings) {}
    try {
        if (typeof globalThis !== "undefined" && globalThis[name] !== undefined && globalThis[name] !== null) return globalThis[name];
    } catch (eGlobal) {}
    return fallback;
}
function boolValue(value, fallback) {
    if (value === true || value === false) return value;
    if (value === undefined || value === null || value === "") return fallback;
    var normalized = String(value).toLowerCase();
    if (normalized === "true" || normalized === "1" || normalized === "yes" || normalized === "on") return true;
    if (normalized === "false" || normalized === "0" || normalized === "no" || normalized === "off") return false;
    return fallback;
}
function storageGet(key) {
    try {
        if (typeof localStorage !== "undefined" && localStorage && typeof localStorage.getItem === "function") {
            var value = localStorage.getItem(key);
            return value === undefined || value === null ? "" : String(value);
        }
    } catch (eStorageGet) {}
    return "";
}
function storageSet(key, value) {
    try {
        if (typeof localStorage !== "undefined" && localStorage && typeof localStorage.setItem === "function") {
            localStorage.setItem(key, String(value));
            return true;
        }
    } catch (eStorageSet) {}
    return false;
}
function normalizeForCache(text) {
    return String(text || "").trim().replace(/\r\n/g, "\n");
}
function textContentHash(text) {
    var str = normalizeForCache(text);
    var hash1 = 2166136261;
    var hash2 = 0x811c9dc5;
    for (var i = 0; i < str.length; i++) {
        var code = str.charCodeAt(i);
        hash1 = ((hash1 ^ code) * 16777619) >>> 0;
        hash2 = ((hash2 * 31) + code) >>> 0;
    }
    return ("00000000" + hash1.toString(16)).slice(-8) + ("00000000" + hash2.toString(16)).slice(-8) + "_" + str.length;
}
function v231JSON(value,fallback){try{return JSON.parse(String(value||''));}catch(e){return fallback;}}
function v231Scope(book){return book?'v231-story-v2:'+textContentHash(JSON.stringify([book.book_id,book.source.toLowerCase()])):'';}
function v231CanonicalNames(first,second){
    var result=[],seen={},all=(first||[]).concat(second||[]);
    for(var i=0;i<all.length;i++){
        var row=all[i]||{},a=String(row.source||'').trim(),b=String(row.target||'').trim();
        if(!a||!b||a.length>80||b.length>120||/[\[\]【】]|LUNA_(?:KEEP|FMT)_/i.test(a+b)||seen['$'+a])continue;
        seen['$'+a]=true;result.push({source:a,target:b});
    }
    result.sort(function(a,b){return a.source<b.source?-1:a.source>b.source?1:0;});return result;
}
function v231Relations(first,second){
    var out=[],seen={},all=(first||[]).concat(second||[]);
    for(var i=0;i<all.length;i++){
        var r=all[i]||{};
        if(!r.a||!r.b||!r.relation||!r.evidence)continue;
        var row={a:String(r.a).slice(0,120),b:String(r.b).slice(0,120),relation:String(r.relation).slice(0,240),address:String(r.address||'').slice(0,240),evidence:String(r.evidence).slice(0,500),context:String(r.context||'').slice(0,160)};
        var id=JSON.stringify(row);if(seen['$'+id])continue;seen['$'+id]=true;out.push(row);
    }
    return out;
}
function v231ServerKey(){return String(valueOf('translation_server_key','')||V231_PRIVATE.server_key||'').trim();}
function v231Routes(){
    var tailscale=String(valueOf('server_tailscale_url','http://smart-tts.tail4cee0d.ts.net')||'').replace(/\/+$/,''),tunnel=String(valueOf('server_tunnel_url','https://smart-tts.tail4cee0d.ts.net')||'').replace(/\/+$/,'');
    var list=[],preferred=storageGet('v231-good-server'),bad=v231JSON(storageGet('v231-bad-server'),{}),now=Date.now();
    for(var i=0;i<2;i++){var url=i===0?tailscale:tunnel;if(!url||list.indexOf(url)>=0||bad[url]>now)continue;list.push(url);}
    if(preferred&&list.indexOf(preferred)>0){list.splice(list.indexOf(preferred),1);list.unshift(preferred);}return list;
}
function v231Server(path,body){
    var key=v231ServerKey();if(!key)return null;
    var urls=v231Routes();
    for(var i=0;i<urls.length;i++){
        var url=urls[i],options={method:body?'POST':'GET',headers:{Authorization:'Bearer '+key},timeout:url===String(valueOf('server_tailscale_url','http://smart-tts.tail4cee0d.ts.net')).replace(/\/+$/,'')?1800:7000};
        if(body){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
        try{
            var response=fetch(url+path,options);
            if(response&&response.ok){var data=parseResponse(response);if(data){storageSet('v231-good-server',url);return data;}}
            // A client without Tailscale can reach an unrelated HTTP gateway
            // on port 80. Any failure there must still try the fixed tunnel.
        }catch(e){}
        var bad=v231JSON(storageGet('v231-bad-server'),{});bad[url]=Date.now()+60000;storageSet('v231-bad-server',JSON.stringify(bad));
    }
    return null;
}
function v231Get(key){var data=v231Server('/v1/translations/get?key='+encodeURIComponent(key));return data&&data.found&&data.translation?data.translation:null;}
function v231ReadMemory(scope){
    var local=v231JSON(storageGet(scope+':local'),{entries:[],relations:[]});
    return {entries:v231CanonicalNames(local.entries,[]),relations:v231Relations(local.relations,[]),dirty:!!local.dirty};
}
function v231WriteMemory(scope,memory){
    if(!scope)return;
    var current=v231ReadMemory(scope);
    memory.entries=v231CanonicalNames(memory.entries,current.entries);memory.relations=v231Relations(memory.relations,current.relations);
    storageSet(scope+':local',JSON.stringify(memory));
}
function v231SyncMemory(book,memory){
    var scope=v231Scope(book);if(!scope)return memory;
    var current=v231ReadMemory(scope);
    memory={entries:v231CanonicalNames(memory.entries,current.entries),relations:v231Relations(memory.relations,current.relations),dirty:memory.dirty||current.dirty};
    if(!boolValue(valueOf('sync_story_memory',true),true)){v231WriteMemory(scope,memory);return memory;}
    var names=v231Get(scope+':names'),relations=v231Get(scope+':relations');
    var remoteNames=v231JSON(names&&(names.translated_text||names.text),{}),remoteRelations=v231JSON(relations&&(relations.translated_text||relations.text),{});
    var loaded={entries:v231CanonicalNames(remoteNames.entries,memory.entries),relations:v231Relations(remoteRelations.relations,memory.relations),dirty:memory.dirty};
    var hasLocalOnly=loaded.entries.length!==(remoteNames.entries||[]).length||loaded.relations.length!==(remoteRelations.relations||[]).length;
    if(loaded.dirty||hasLocalOnly){
        var a=JSON.stringify({entries:loaded.entries}),b=JSON.stringify({relations:loaded.relations}),okNames=!loaded.entries.length,okRelations=!loaded.relations.length;
        if(loaded.entries.length){var savedNames=v231Server('/v1/translations/put',{key:scope+':names',kind:'character-glossary',book_id:book.book_id,source:book.source,text:a,translated_text:a,merge:true});okNames=!!(savedNames&&savedNames.stored);}
        if(loaded.relations.length){var savedRelations=v231Server('/v1/translations/put',{key:scope+':relations',kind:'relationship-memory',book_id:book.book_id,source:book.source,text:b,translated_text:b,merge:true});okRelations=!!(savedRelations&&savedRelations.stored);}
        loaded.dirty=!(okNames&&okRelations);
        // Read the atomic server merge back: another chapter may have added data.
        if(okNames&&okRelations){
            var finalNames=v231Get(scope+':names'),finalRelations=v231Get(scope+':relations');
            var fn=v231JSON(finalNames&&(finalNames.translated_text||finalNames.text),{}),fr=v231JSON(finalRelations&&(finalRelations.translated_text||finalRelations.text),{});
            loaded.entries=v231CanonicalNames(fn.entries,loaded.entries);loaded.relations=v231Relations(fr.relations,loaded.relations);
        }
    }
    v231WriteMemory(scope,loaded);return loaded;
}
function v231Upload(item){
    if(!item)return false;
    var saved=v231Server(item.kind==='source'?'/v1/translations/source':'/v1/translations/put',item);
    return !!(saved&&saved.stored);
}
function v231SyncAllMemory(){
    if(!boolValue(valueOf('sync_story_memory',true),true))return;
    var stories=v231JSON(storageGet('v231-stories'),[]);
    for(var i=0;i<stories.length;i++){
        var memory=v231ReadMemory(v231Scope(stories[i]));
        if(memory.dirty)v231SyncMemory(stories[i],memory);
    }
}
function v231Flush(){
    v231SyncAllMemory();
    if(!boolValue(valueOf('archive_translations',true),true))return;
    var list=v231JSON(storageGet('v231-outbox'),[]),done={};
    // Drain every pending chapter on reconnect; batched to avoid one HTTP per item.
    for(var i=0;i<list.length;){
        var batch=[],chars=0;
        while(i<list.length&&batch.length<20){
            var item=v231JSON(storageGet('v231-pending:'+list[i]),null);i++;
            if(!item){done['$'+list[i-1]]=true;continue;}
            var size=JSON.stringify(item).length;if(batch.length&&chars+size>1000000){i--;break;}
            batch.push(item);chars+=size;
        }
        if(!batch.length)continue;
        var saved=v231Server('/v1/translations/sync',{items:batch}),stored=saved&&saved.stored_keys||[];
        if(!saved){
            // Older servers can still accept one item at a time. Stop on failure,
            // retaining all remaining payloads for the next connection.
            var failed=false;
            for(var b=0;b<batch.length;b++){if(v231Upload(batch[b]))stored.push(batch[b].key);else {failed=true;break;}}
            for(var s=0;s<stored.length;s++)done['$'+stored[s]]=true;
            if(failed)break;
        }else{
            for(var n=0;n<stored.length;n++)done['$'+stored[n]]=true;
            if(stored.length!==batch.length)break;
        }
    }
    var latest=v231JSON(storageGet('v231-outbox'),[]),keep=[];
    for(var n=0;n<latest.length;n++)if(!done['$'+latest[n]])keep.push(latest[n]);else storageSet('v231-pending:'+latest[n],'');
    storageSet('v231-outbox',JSON.stringify(keep));
}
function parseResponse(response){
    try{var text=String(response.text());return JSON.parse(text);}catch(e){}
    try{return response.json();}catch(e){return null;}
}
// Menu rendering must never wait for a server. Chapter execution drains the durable outbox.
execute=function(){return Response.success([
    {id:"converted",name:"Truyện đã converted (VP)",type:"from"},
    {id:"vi",name:"Việt (VP)",type:"to"}
]);}
