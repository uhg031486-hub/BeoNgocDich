"""V31 bounded two-worker translation, exact markers, ordered persistent results."""
import concurrent.futures,hashlib,json,re,threading,time,urllib.request,urllib.error
from pathlib import Path
from scheduler import RequestQueue

MARKER=re.compile(r'\[LUNA_(KEEP|FMT)_(\d+)\]')
def counts(text):
    result={}
    for match in MARKER.finditer(text):result[match.group()]=result.get(match.group(),0)+1
    return result

class TranslationBatch:
    def __init__(self,store,data,caller=None):
        self.store=store;self.data=Path(data);self.caller=caller
        self.pool=concurrent.futures.ThreadPoolExecutor(max_workers=2)
        self.requests=RequestQueue(parallel=2,max_waiting=16,timeout=140)
        self.guard=threading.Lock();self.locks={}
    def ready(self):return bool(self.caller or (self.data/'hybrid-router-key.txt').is_file())
    def complete(self,payload):
        if self.caller:return self.caller(payload)
        key=(self.data/'hybrid-router-key.txt').read_text('utf-8-sig').strip()
        if not key:raise RuntimeError('Server chưa có key9Router')
        req=urllib.request.Request('https://r4pqkqr.abc-tunnel.us/v1/chat/completions',data=json.dumps(payload,ensure_ascii=False).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key,'User-Agent':'Mozilla/5.0 vBook/1.0'})
        try:
            with urllib.request.urlopen(req,timeout=150) as response:
                raw=response.read(4*1024**2).decode('utf-8','replace')
            # Some 9Router backends stream SSE despite stream=false. Normalize it
            # here so the batch worker accepts both OpenAI JSON and SSE responses.
            if raw.lstrip().startswith('data:'):
                parts=[]
                for line in raw.splitlines():
                    if not line.startswith('data:'):continue
                    value=line[5:].strip()
                    if not value or value=='[DONE]':continue
                    item=json.loads(value)
                    delta=item.get('choices',[{}])[0].get('delta',{})
                    if delta.get('content'):parts.append(delta['content'])
                body={'choices':[{'message':{'content':''.join(parts)},'finish_reason':'stop'}]}
            else: body=json.loads(raw)
            choice=body['choices'][0]
            if choice.get('finish_reason') in ('length','content_filter'):raise ValueError('AI trả phần dịch chưa đầy đủ')
            value=choice['message']['content']
            if not isinstance(value,str) or not value.strip():raise ValueError('AI trả bản dịch trống')
            return re.sub(r'^```[^\n]*\n|\n```\s*$','',value.strip())
        except urllib.error.HTTPError as e:raise RuntimeError('9Router HTTP '+str(e.code)) from None
        except (urllib.error.URLError,TimeoutError,OSError):raise RuntimeError('Không kết nối được9Router') from None
    def validate(self,body):
        if body.get('policy')!='v31':raise ValueError('Cần policy v31')
        chunks=body.get('chunks');book=body.get('book');scope=body.get('scope');model=body.get('model')
        if not isinstance(book,dict) or not book.get('book_id') or not isinstance(scope,str) or not scope:raise ValueError('Thiếu mã truyện')
        if not isinstance(model,str) or len(model)>160 or not model:raise ValueError('Model không hợp lệ')
        if body.get('reasoning') not in ('minimal','none'):raise ValueError('Reasoning không hợp lệ')
        if not isinstance(chunks,list) or not 1<=len(chunks)<=64:raise ValueError('Số đoạn không hợp lệ')
        total=0
        for c in chunks:
            if not isinstance(c,dict) or not isinstance(c.get('text'),str) or not c['text'].strip() or not isinstance(c.get('protected_text'),str):raise ValueError('Đoạn không hợp lệ')
            total+=len(c['text'])
            if len(c['text'])>16000 or len(c['protected_text'])>40000:raise ValueError('Đoạn quá dài')
            if counts(c['protected_text'])!=c.get('marker_counts'):raise ValueError('Sai số marker')
            for group in ('names','formatting'):
                if not isinstance(c.get(group),list) or len(c[group])>1000:raise ValueError('Bảng khóa sai')
                for item in c[group]:
                    if not isinstance(item,dict) or not isinstance(item.get('source'),str) or not item['source'] or not isinstance(item.get('target'),str) or not item['target'] or 'LUNA_' in item['target']:raise ValueError('Tên khóa sai')
            if not isinstance(c.get('messages'),list) or len(c['messages'])!=2 or any(not isinstance(m,dict) or not isinstance(m.get('content'),str) or len(m['content'])>100000 for m in c['messages']):raise ValueError('Prompt sai')
        if total>100000:raise ValueError('Chương quá dài')
        if ''.join(c['text'] for c in chunks)!=book.get('text'):raise ValueError('Chương thiếu nội dung')
        return chunks
    def translate(self,body):
        chunks=self.validate(body)
        identity=hashlib.sha256(json.dumps(body,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        with self.guard:lock=self.locks.setdefault(identity,threading.Lock())
        with self.requests.slot(),lock:
            base={'kind':'parallel-chapter-v31','policy':'v31','text':body['book']['text'],'book':body['book'],'scope':body['scope'],'model':body['model'],'job_id':identity}
            saved=self.store.get('v31-parallel:'+identity)
            if saved and saved.get('status')=='complete':return {'translated_text':saved['translated_text'],'chunks':saved['chunks'],'cache_hit':True,'workers':2}
            self.store.put('v31-parallel:'+identity,dict(base,status='processing',translated_text=''))
            def work(index,c):
                key='v31-part:'+identity+':'+str(index);saved=self.store.get(key)
                if saved and saved.get('policy')=='v31':return saved['translated_text']
                payload={'model':body['model'],'reasoning_effort':body['reasoning'],'temperature':body.get('temperature',.2),'stream':False,'max_tokens':min(32000,max(2048,len(c['text'])*4+2048)),'messages':c['messages']}
                value=''
                for attempt in range(2):
                    value=self.complete(payload)
                    if counts(value)==c['marker_counts'] and not re.search(r'LUNA_(?:KEEP|FMT)_',MARKER.sub('',value),re.I):break
                    payload=dict(payload,messages=c['messages']+[{'role':'user','content':'Dịch lại đầy đủ: marker tên/định dạng phải đúng nguyên văn và đúng số lần trong đầu vào. Không thêm ngoặc hoặc số vào tên.'}])
                else:raise ValueError('AI làm sai marker; không lưu phần lỗi')
                def restore(m):
                    group='names' if m[1]=='KEEP' else 'formatting';n=int(m[2])
                    if n>=len(c[group]):raise ValueError('Marker không có trong bảng')
                    return c[group][n]['target']
                # Single regex pass prevents KEEP_1 matching KEEP_10 and recursive replacement.
                value=MARKER.sub(restore,value)
                value=re.match(r'^\s*',c['text'])[0]+value.strip()+re.search(r'\s*$',c['text'])[0]
                han=len(re.findall(r'[\u3400-\u9fff]',c['text']))
                if body.get('input_mode')!='converted' and han>=30 and (len(re.findall(r'[\u3400-\u9fff]',value))>max(10,han*.2) or len(value)<han*.7):raise ValueError('Bản dịch thiếu hoặc còn tiếng Trung')
                if not value.strip():raise ValueError('Bản dịch trống')
                self.store.put(key,{'kind':'parallel-part-v31','policy':'v31','text':c['text'],'translated_text':value,'chunk_index':index,'job_id':identity});return value
            futures=[self.pool.submit(work,i,c) for i,c in enumerate(chunks)]
            try:results=[f.result() for f in futures]
            except Exception:
                for f in futures:f.cancel()
                concurrent.futures.wait(futures)
                self.store.put('v31-parallel:'+identity,dict(base,status='failed',translated_text=''));raise
            text=''.join(results)
            self.store.put('v31-parallel:'+identity,dict(base,status='complete',translated_text=text,chunks=results))
            return {'translated_text':text,'chunks':results,'cache_hit':False,'workers':2}
