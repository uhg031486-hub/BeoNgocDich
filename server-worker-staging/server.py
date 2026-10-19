"""Smart TTS Engine prototype: real NCNN speech, model manager and 9Router batch bridge."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import argparse,base64,hashlib,hmac,io,json,logging,multiprocessing,os,re,secrets,subprocess,threading,time,urllib.parse,wave
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from models import ModelStore
from engine import Engine,wav_bytes
from tempo import VoiceTempo
from scheduler import RequestQueue
from hybrid import Hybrid
from nano_backend import NanoBackend
from translation_store import TranslationStore
from character_registry import CharacterRegistry
from audio_archive import DisabledAudioArchive
from translation_batch import TranslationBatch
from story_identity import StoryIdentity
from epub_jobs import EpubJobs

class Service:
    def __init__(self,data,token,threads=2):
        self.data=Path(data).resolve();self.store=ModelStore(self.data/'models');self.engine=Engine(self.store,threads);self.engine.max_resident=1;self.token=token;self.jobs={};self.busy=threading.Lock();self.requests=RequestQueue(parallel=1,max_waiting=4)
        self.tempo=VoiceTempo(self.data,self.store)
        self.nano=NanoBackend()
        self.hybrid=Hybrid(self)
        # Translation text is independent from TTS jobs. Keep it bounded and
        # evict oldest entries instead of allowing a long-running reader to
        # fill the voice server disk.
        self.translations=TranslationStore(self.data,max_bytes=20*1024**3,ttl_days=0)
        self.characters=CharacterRegistry(self.data/'characters.json')
        self.audio_archive=DisabledAudioArchive(self.data)
        self.story_identity=StoryIdentity(self.data)
        self.translation_batch=TranslationBatch(self.translations,self.data)
        self.epub_jobs=EpubJobs(self.data, self._translate_epub_chapter)
    def _translate_epub_chapter(self, text, model):
        return self._router_call({'model':model,'reasoning_effort':'minimal','stream':False,'max_tokens':8192,'messages':[{'role':'system','content':'Translate this XHTML chapter to Vietnamese. Preserve every HTML tag and attribute exactly. Return XHTML only.'},{'role':'user','content':text}]})
    def _router_call(self,payload):
        import urllib.request
        key=(self.data/'hybrid-router-key.txt').read_text('utf-8-sig').strip()
        request=urllib.request.Request('https://r4pqkqr.abc-tunnel.us/v1/chat/completions',data=json.dumps(payload,ensure_ascii=False).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key,'User-Agent':'Mozilla/5.0 vBook/1.0'})
        with urllib.request.urlopen(request,timeout=240) as response: raw=response.read(16*1024**2).decode('utf-8','replace')
        if raw.lstrip().startswith('data:'):
            return ''.join(json.loads(line[5:].strip()).get('choices',[{}])[0].get('delta',{}).get('content','') for line in raw.splitlines() if line.startswith('data:') and line[5:].strip()!='[DONE]')
        return json.loads(raw)['choices'][0]['message']['content']
    def match_tempo(self,pcm,rate,factor):
        if factor==1:return pcm
        import imageio_ffmpeg
        result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-f','s16le','-ar',str(rate),'-ac','1','-i','pipe:0','-af',f'atempo={factor:.8f}','-f','s16le','pipe:1'],input=pcm,capture_output=True,timeout=30)
        if result.returncode or len(result.stdout)<200:raise RuntimeError('Không cân được nhịp giọng')
        return result.stdout
    def microsoft(self,voice,text,speed):
        import asyncio,edge_tts,imageio_ffmpeg
        from text_normalization import normalize_text
        text=normalize_text(text)
        rate=f'{round((speed-1)*100):+d}%'
        async def create():
            chunks=[]
            async for part in edge_tts.Communicate(text,voice,rate=rate).stream():
                if part['type']=='audio':chunks.append(part['data'])
            return b''.join(chunks)
        try:audio=asyncio.run(asyncio.wait_for(create(),timeout=35))
        except Exception as e:raise RuntimeError('Microsoft trên server không tạo được âm thanh: '+type(e).__name__)
        result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-i','pipe:0','-f','s16le','-ar','22050','-ac','1','pipe:1'],input=audio,capture_output=True,timeout=20)
        if result.returncode or len(result.stdout)<200:raise RuntimeError('Microsoft trả âm thanh không hợp lệ')
        return result.stdout,22050,0
    def role_mix(self):
        path=self.data/'role-volume.json'
        try:
            result=json.loads(path.read_text(encoding='utf-8'))
            if all(isinstance(result.get(k),(int,float)) and not isinstance(result[k],bool) and 0<=result[k]<=150 for k in ['narrator','dialogue']):return result
        except (OSError,ValueError,TypeError):pass
        return {'narrator':100,'dialogue':100}
    def save_role_mix(self,body):
        values={k:body.get(k) for k in ['narrator','dialogue']}
        if any(not isinstance(v,(int,float)) or isinstance(v,bool) or not 0<=v<=150 for v in values.values()):raise ValueError('Âm lượng phải từ0 đến150%')
        with self.engine.lock:
            path=self.data/'role-volume.json';temp=path.with_suffix('.tmp');temp.write_text(json.dumps(values),encoding='utf-8');temp.replace(path)
        return values
    def balanced_pcm(self,pcm,sample_rate,mix_volume):
        # Match active speech RMS, not silence. Then apply the user's role gain.
        import numpy as np
        samples=np.frombuffer(pcm,dtype='<i2').astype(np.float64)
        if not samples.size:return pcm
        window=max(1,int(sample_rate*.02));usable=len(samples)//window*window
        frames=samples[:usable].reshape(-1,window) if usable else samples.reshape(1,-1)
        rms=np.sqrt(np.mean(frames*frames,axis=1));threshold=max(32768*10**(-45/20),float(rms.max())*.1)
        active=frames[rms>threshold]
        if not active.size:return bytes(len(pcm))
        speech_rms=float(np.sqrt(np.mean(active*active)));gain=min(3276.8/speech_rms,8.0)*mix_volume/100
        result=samples*gain
        # Soft limit only the highest peaks; do not turn up the other role.
        limit=32767*.9;above=np.abs(result)>limit
        result[above]=np.sign(result[above])*(limit+(32767-limit)*np.tanh((np.abs(result[above])-limit)/(32767-limit)))
        return np.rint(result).astype('<i2').tobytes()
    def emphasize_pcm(self,pcm,marks,sample_rate):
        import numpy as np
        a=np.frombuffer(pcm,dtype='<i2').astype(float);envelope=np.ones(len(a));fade=max(1,int(sample_rate*.012))
        for mark in marks:
            start=max(0,mark['start']);end=min(len(a),mark['end'])
            if start>=end:continue
            shape=np.ones(end-start);n=min(fade,len(shape)//2)
            if n:shape[:n]=np.linspace(0,1,n);shape[-n:]=np.linspace(1,0,n)
            gain=10**(mark['gain_db']/20) if 'gain_db' in mark else 1+mark.get('gain',0)/100
            # Later marks take precedence in overlap; never multiply overlapping gains.
            envelope[start:end]=1+shape*(gain-1)
        a*=envelope
        limit=32767*.9;above=np.abs(a)>limit
        a[above]=np.sign(a[above])*(limit+(32767-limit)*np.tanh((np.abs(a[above])-limit)/(32767-limit)))
        return np.rint(np.clip(a,-32767,32767)).astype('<i2').tobytes()
    def insert_pauses(self,pcm,words,plans,sample_rate):
        import numpy as np
        from text_normalization import normalize_text
        a=np.frombuffer(pcm,dtype='<i2').copy();tokens=[w['word'] for w in words];points={};applied=[]
        for plan in plans:
            target=re.findall(r'\w+',normalize_text(plan['after']).lower(),re.UNICODE)
            matches=[i for i in range(len(tokens)-len(target)+1) if target and tokens[i:i+len(target)]==target]
            if len(matches)<plan.get('occurrence',1):continue
            i=matches[plan.get('occurrence',1)-1];last=i+len(target)-1
            # End-of-utterance pause is supplied by pause_ms, never double it.
            if last==len(tokens)-1:continue
            position=max(0,min(len(a),words[last]['end']))
            points[position]=max(points.get(position,0),round(plan['ms']*sample_rate/1000))
            applied.append({**plan,'position':position})
        fade=max(1,round(sample_rate*.006))
        for position,n in sorted(points.items(),reverse=True):
            left=min(fade,position);right=min(fade,len(a)-position)
            if left:a[position-left:position]=(a[position-left:position]*np.linspace(1,0,left)).astype('<i2')
            if right:a[position:position+right]=(a[position:position+right]*np.linspace(0,1,right)).astype('<i2')
            a=np.concatenate((a[:position],np.zeros(n,dtype='<i2'),a[position:]))
        return a.tobytes(),applied,sum(points.values())
    def check_all(self):
        if not self.busy.acquire(False):raise ValueError('Đang có một công việc quản lý model')
        job={'status':'running','completed':0,'total':len(self.store.voices()),'results':[]};jobid=secrets.token_hex(8);self.jobs[jobid]=job
        def run():
            try:
                for v in self.store.voices():
                    result=self.engine.health_check(v['id']);job['results'].append({'voice':v['id'],**result});job['completed']+=1
                job['status']='complete'
            except Exception as e:job['status']='error';job['error']=str(e)
            finally:self.busy.release()
        threading.Thread(target=run,daemon=True).start();return jobid
    def import_async(self,path):
        if not self.busy.acquire(False):raise ValueError('Đang có một công việc quản lý model')
        job={'status':'running','phase':'import','completed':0,'total':1};jobid=secrets.token_hex(8);self.jobs[jobid]=job
        def run():
            try:
                with self.engine.lock:
                    self.engine.require_idle();job['model']=self.store.install(path)
                job['completed']=1;job['status']='complete'
            except Exception as e:job['status']='error';job['error']=str(e)
            finally:self.busy.release()
        threading.Thread(target=run,daemon=True).start();return jobid
    def encode(self,pcm,rate,fmt):
        data=wav_bytes(pcm,rate)
        if fmt in ['wav','json']:return data
        if fmt!='mp3':raise ValueError('Chỉ hỗ trợ wav/mp3/json')
        import imageio_ffmpeg
        result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-f','wav','-i','pipe:0','-f','mp3','-codec:a','libmp3lame','-b:a','96k','pipe:1'],input=data,capture_output=True,timeout=30)
        if result.returncode or len(result.stdout)<100:raise RuntimeError('Không mã hóa được MP3')
        return result.stdout
    def prosody(self,pcm,rate,pitch_percent=0,volume_percent=0,texture='none'):
        if not pitch_percent and not volume_percent and texture=='none':return pcm
        import imageio_ffmpeg
        pitch=max(.76,min(1.24,1+float(pitch_percent)/100));volume=max(.3,min(1.5,1+float(volume_percent)/100))
        filters=f'asetrate={rate*pitch},aresample={rate},atempo={1/pitch},volume={volume}'
        # Mild waveform effects approximate texture; they do not generate sobs/laughter.
        textures={
            'tearful':'tremolo=f=4.5:d=0.12,equalizer=f=2400:t=q:w=0.8:g=-1.5',
            'trembling':'tremolo=f=6:d=0.10',
            'smiling':'equalizer=f=2600:t=q:w=0.8:g=1.5',
            'hushed':'equalizer=f=3200:t=q:w=0.8:g=-3,volume=0.85',
        }
        if texture in textures:filters+=','+textures[texture]
        result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-f','s16le','-ar',str(rate),'-ac','1','-i','pipe:0','-af',filters,'-f','s16le','pipe:1'],input=pcm,capture_output=True,timeout=30)
        if result.returncode or len(result.stdout)<200:raise RuntimeError('Xử lý diễn xuất không tạo âm thanh')
        return result.stdout
    def voices(self,ready=False):
        return self.nano.voices()
    def active_local_voice(self,voice,segment=None):
        # Legacy saved NCNN choices are migrated in memory; archives stay untouched.
        if str(voice).startswith(('nano:','vi-VN-')):return voice
        if not voice or ':' not in str(voice) or str(voice).startswith('gemini:'):
            raise ValueError('Giọng không hỗ trợ')
        if voice not in [v['id'] for v in self.store.voices()]:raise ValueError('Giọng không hỗ trợ')
        s=segment or {}
        if s.get('kind')=='narration' or s.get('speaker')=='narrator' or not segment:return 'nano:Trúc Ly'
        ids=sorted(v['id'] for v in self.nano.voices())
        if not ids:raise RuntimeError('Nano chưa sẵn sàng')
        identity=str(s.get('characterId') or s.get('speaker') or voice)
        return ids[int(hashlib.sha256(identity.encode()).hexdigest()[:8],16)%len(ids)]
    def prepare_speech(self,body):
        model=body.get('model','smart-vbook');text=body.get('input');fmt=body.get('response_format','mp3')
        if not isinstance(text,str) or not text.strip():raise ValueError('Thiếu input')
        if fmt not in ['mp3','wav','json']:raise ValueError('response_format không hợp lệ')
        if model not in ['smart-vbook','v3nano',*self.store.models]:raise ValueError('Không có model: '+str(model))
        voice=body.get('voice');raw=text.strip();envelope=None
        if model=='smart-vbook' and raw.startswith('{'):
            envelope=json.loads(raw)
            if envelope.get('schema')!='smart-vbook.roles.v1':raise ValueError('Schema phân vai không hỗ trợ')
        segments=envelope.get('segments') if envelope else [{'text':text,'voice':voice if ':' in str(voice) or str(voice).startswith('vi-VN-') else model+':'+str(voice or '0'),'speed':body.get('speed',1)}]
        if not isinstance(segments,list) or not 1<=len(segments)<=120:raise ValueError('Batch phải có1–120 đoạn')
        if any(not isinstance(s,dict) for s in segments):raise ValueError('Đoạn thoại phải là JSON object')
        if sum(len(str(s.get('text',''))) for s in segments)>16000:raise ValueError('Batch vượt16000 ký tự')
        # Validate the full batch before generating anything: never return a partial chapter.
        for s in segments:
            s['voice']=self.active_local_voice(s.get('voice'),s)
            if not isinstance(s.get('normalize_tempo',True),bool):raise ValueError('normalize_tempo phải là boolean')
            if s.get('normalize_tempo',True) and not str(s.get('voice','')).startswith(('vi-VN-','nano:')):self.tempo.factor(s.get('voice',''))
            ps=s.get('pause_scale',1)
            if not isinstance(ps,(float,int)) or isinstance(ps,bool) or not .5<=ps<=1:raise ValueError('pause_scale phải trong0.5–1')
            emphasis=s.get('emphasis',[])
            if not isinstance(emphasis,list) or len(emphasis)>6:raise ValueError('Điểm nhấn không hợp lệ')
            for e in emphasis:
                if not isinstance(e,dict) or not isinstance(e.get('text'),str) or not e['text'].strip() or len(e['text'])>120:raise ValueError('Thiếu chữ cần nhấn')
                for key,low,high in [('gain',0,30),('gain_db',-6,8),('rate',-35,35),('occurrence',1,20)]:
                    value=e.get(key,1 if key=='occurrence' else 0)
                    if not isinstance(value,(int,float)) or isinstance(value,bool) or not low<=value<=high or key=='occurrence' and int(value)!=value:raise ValueError('Tham số điểm nhấn không hợp lệ')
            plans=s.get('pauses',[])
            if not isinstance(plans,list) or len(plans)>4:raise ValueError('Khoảng nghỉ không hợp lệ')
            for point in plans:
                if not isinstance(point,dict) or not isinstance(point.get('after'),str) or not point['after'].strip() or len(point['after'])>120:raise ValueError('Thiếu vị trí nghỉ')
                for key,low,high in [('ms',80,500),('occurrence',1,20)]:
                    value=point.get(key,1 if key=='occurrence' else 0)
                    if not isinstance(value,(int,float)) or isinstance(value,bool) or not low<=value<=high or key=='occurrence' and int(value)!=value:raise ValueError('Tham số nghỉ không hợp lệ')
            if not isinstance(s.get('balance_volume',False),bool):raise ValueError('balance_volume phải là boolean')
            value=s.get('mix_volume',100)
            if not isinstance(value,(int,float)) or isinstance(value,bool) or not 0<=value<=150:raise ValueError('mix_volume phải từ0 đến150')
            if s.get('texture','none') not in ['none','tearful','smiling','trembling','hushed']:raise ValueError('Texture không hỗ trợ')
            if not isinstance(s,dict) or not isinstance(s.get('text'),str) or not s['text'].strip():raise ValueError('Đoạn thoại trống/hỏng')
            if s.get('voice') not in [v['id'] for v in self.voices(True)]+['vi-VN-HoaiMyNeural','vi-VN-NamMinhNeural']:raise RuntimeError('Giọng không sẵn sàng: '+str(s.get('voice')))
            if not isinstance(s.get('speed',1),(float,int)) or not .5<=s.get('speed',1)<=2:raise ValueError('Tốc độ đoạn không hợp lệ')
            for field,bound in [('pitch',24),('volume',25),('pause_ms',1000)]:
                value=s.get(field,0)
                if not isinstance(value,(float,int)) or isinstance(value,bool) or not -bound<=value<=bound:raise ValueError('Giá trị diễn xuất không hợp lệ: '+field)
        return segments,fmt
    def speech(self,body):
        segments,fmt=self.prepare_speech(body)
        chunks=[];rate=None;timings=[]
        with self.requests.slot():
            for s in segments:
                t=time.perf_counter()
                aligned={'words':[],'emphasis':[]}
                if s['voice'].startswith('vi-VN-'):pcm,sr,_=self.microsoft(s['voice'],s['text'],s.get('speed',1))
                elif s['voice'].startswith('nano:'):pcm,sr=self.nano.synthesize(s);pcm=self.hybrid.resample(pcm,sr);sr=22050
                else:pcm,sr,_,aligned=self.engine.synthesize(s['voice'],s['text'],s.get('speed',1),pause_scale=s.get('pause_scale',1),with_timing=True,emphasis=s.get('emphasis',[]))
                if rate is not None and rate!=sr:raise RuntimeError('Chưa hỗ trợ ghép khác sample rate')
                factor=self.tempo.factor(s['voice']) if s.get('normalize_tempo',True) and not s['voice'].startswith(('vi-VN-','nano:')) else 1
                rate=sr;original_frames=len(pcm)//2;pcm=self.match_tempo(pcm,rate,factor);pcm=self.prosody(pcm,rate,s.get('pitch',0),s.get('volume',0),s.get('texture','none'))
                if s.get('balance_volume'):pcm=self.balanced_pcm(pcm,sr,s.get('mix_volume',self.role_mix()['narrator' if s.get('speaker')=='narrator' else 'dialogue']))
                ratio=(len(pcm)//2)/original_frames
                aligned['emphasis']=[{**v,'start':round(v['start']*ratio),'end':round(v['end']*ratio)} for v in aligned['emphasis']]
                aligned['words']=[{**v,'start':round(v['start']*ratio),'end':round(v['end']*ratio)} for v in aligned['words']]
                pcm=self.emphasize_pcm(pcm,aligned['emphasis'],sr)
                pcm,applied_pauses,added_samples=self.insert_pauses(pcm,aligned['words'],s.get('pauses',[]),sr)
                positions={}
                for point in applied_pauses:positions[point['position']]=max(positions.get(point['position'],0),round(point['ms']*sr/1000))
                def shifted(position):return position+sum(n for p,n in positions.items() if p<=position)
                aligned['emphasis']=[{**v,'start':shifted(v['start']),'end':v['end']+sum(n for p,n in positions.items() if p<v['end'])} for v in aligned['emphasis']]
                applied_pauses=[{**v,'position':v['position']+sum(n for p,n in positions.items() if p<v['position'])} for v in applied_pauses]
                if len(aligned['emphasis'])<len(s.get('emphasis',[])):logging.warning('Một số điểm nhấn không có timing âm vị; không áp vào từ khác')
                chunks.append(pcm);chunks.append(bytes(int(sr*max(0,s.get('pause_ms',160))/1000)*2));timings.append({'voice':s['voice'],'tempo_factor':factor,'normalize_tempo':s.get('normalize_tempo',True),'texture':s.get('texture','none'),'emphasis':aligned['emphasis'],'requested_emphasis':len(s.get('emphasis',[])),'pauses':applied_pauses,'requested_pauses':len(s.get('pauses',[])),'added_pause_samples':added_samples,'speed':s.get('speed',1),'pause_scale':s.get('pause_scale',1),'seconds':round(time.perf_counter()-t,3)})
        return self.encode(b''.join(chunks),rate,fmt),fmt,timings

def handler(service):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,fmt,*args):logging.info('%s %s',self.command,self.path.split('?')[0])
        def send(self,status,data,ctype='application/json'):
            if isinstance(data,(dict,list)):data=json.dumps(data,ensure_ascii=False).encode()
            elif isinstance(data,str):data=data.encode()
            try:
                self.send_response(status);self.send_header('Content-Type',ctype);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
            except (ConnectionAbortedError,ConnectionResetError,BrokenPipeError):
                logging.warning('tts_client_disconnected: response socket closed')
        def authorized(self):
            return hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+service.token)
        def do_GET(self):
            path=urllib.parse.urlsplit(self.path).path
            if path=='/local/session':
                host=self.headers.get('Host','').split(':')[0]
                origin=self.headers.get('Origin','')
                forwarded=any(self.headers.get(h) for h in ['Forwarded','X-Forwarded-For','CF-Connecting-IP'])
                if self.client_address[0] not in ['127.0.0.1','::1'] or host not in ['localhost','127.0.0.1'] or forwarded or origin and origin not in ['http://127.0.0.1:17861','http://localhost:17861']:
                    return self.send(403,{'error':{'message':'Chỉ mở khóa từ kho giọng trên máy này'}})
                return self.send(200,{'key':service.token})
            if path=='/health':return self.send(200,{'status':'ok','server_version':'vps-7.13.9-no-audio-storage','nano':service.nano.health(),'gemini_models':service.hybrid.model_status(),'hybrid_gemini_configured':service.hybrid.ready(),'hybrid_scheduling':service.hybrid.requests.stats(),'scheduling':service.requests.stats(),'runtime_slots':service.engine.max_resident,'threads_per_runtime':service.engine.threads,'ready_voices':len(service.voices(True)),'installed_voices':len(service.voices()),'local_voice_policy':'nano-only; no audio persistence','runtime':'VieNeu v3 Nano ONNX CPU','port':getattr(service,'port',17861),'capabilities':{'hybrid_gemini':True,'role_volume':True,'word_emphasis':True,'expressive_pacing':True,'voice_tempo':True,'translation_cache':True,'audio_archive':False,'voice_identity_age':True,'translation_batch_v31':True,'translation_sync_v231':True},'audio_archive':service.audio_archive.status(),'translation_cache':service.translations.status(),'tempo_reference':'ly:0','tempo_ready_voices':service.tempo.ready_count(),'role_volume':service.role_mix()})
            if path in ['/v1/voices','/v1/audio/voices']:return self.send(200,{'data':service.voices()})
            if path=='/v1/models':return self.send(200,{'data':[{'id':n,'object':'model'} for n in ['smart-vbook','v3nano']]})
            if path=='/':return self.send(200,{'service':'Smart TTS VPS','status':'ok','audio_storage':False,'health':'/health'})
            if not self.authorized():return self.send(401,{'error':{'message':'Thiếu/sai key server riêng'}})
            if path.startswith('/jobs/'):
                job=service.jobs.get(path.split('/')[-1]);return self.send(200,job) if job else self.send(404,{'error':{'message':'Không có job'}})
            if path.startswith('/samples/'):
                filename=path.split('/')[-1]
                if not re.fullmatch(r'[a-zA-Z0-9_-]+_\d+\.wav',filename):return self.send(400,{'error':{'message':'Tên sample không hợp lệ'}})
                p=service.data/'samples'/filename
                return self.send(200,p.read_bytes(),'audio/wav') if p.exists() else self.send(404,{'error':{'message':'Chưa có sample'}})
            if path=='/v1/characters/get':
                if not self.authorized():return self.send(401,{'error':{'message':'Thiếu/sai key server riêng'}})
                scope=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('scope',['default'])[0]
                return self.send(200,{'scope':scope,'characters':service.characters.get(scope)})
            if path=='/v1/translations/get':
                key=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('key',[''])[0]
                row=service.translations.get(key)
                return self.send(200,{'found':bool(row),'translation':row})
            if path=='/v1/epub/jobs':
                return self.send(200,{'jobs':service.epub_jobs.list()})
            if path.startswith('/v1/epub/jobs/') and not path.endswith('/download'):
                row=service.epub_jobs.get(path.split('/')[-1])
                return self.send(200,row) if row else self.send(404,{'error':{'message':'EPUB job not found'}})
            if path.startswith('/v1/epub/jobs/') and path.endswith('/download'):
                output=service.epub_jobs.output(path.split('/')[-2])
                return self.send(200,output.read_bytes(),'application/epub+zip') if output else self.send(404,{'error':{'message':'EPUB not found'}})
            return self.send(404,{'error':{'message':'Không có đường dẫn'}})
        def do_POST(self):
            if not self.authorized():return self.send(401,{'error':{'message':'Thiếu/sai key server riêng'}})
            try:
                length=int(self.headers.get('Content-Length','0'));path=urllib.parse.urlsplit(self.path).path
                if path=='/v1/epub/upload':
                    if not 0<length<=80*1024**2:raise ValueError('EPUB size is invalid')
                    name=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('name',['book.epub'])[0]
                    return self.send(202,service.epub_jobs.create(name,self.rfile.read(length),self.headers.get('X-Translation-Model','ag/gemini-3.8-flash')))
                if not 0<length<=4*1024**2:raise ValueError('Kích thước request không hợp lệ')
                body=json.loads(self.rfile.read(length))
                if not isinstance(body,dict):raise ValueError('Body phải là JSON object')
                if path=='/v1/translate/batch':return self.send(200,service.translation_batch.translate(body))
                if path=='/v1/audio/archive/get':
                    return self.send(410,{'error':{'message':'Audio storage is disabled on this VPS'}})
                if path=='/v1/stories/resolve':
                    return self.send(200,service.story_identity.resolve(body.get('text')))
                if path=='/v1/characters/resolve':
                    scope=body.get('scope','default')
                    characters=body.get('characters',[])
                    if not isinstance(scope,str) or not isinstance(characters,list) or len(characters)>200:raise ValueError('Danh sách nhân vật không hợp lệ')
                    voices=[v for v in service.voices(True) if isinstance(v,dict) and v.get('id')]
                    source_text=body.get('source_text','')
                    if not isinstance(source_text,str):raise ValueError('source_text phải là chuỗi')
                    resolved=service.characters.resolve(scope,characters,voices,source_text)
                    for incoming,outgoing in zip(characters,resolved):
                        if isinstance(incoming,dict) and incoming.get('edgeVoice') and outgoing.get('edgeVoice') is None:
                            outgoing['edgeVoice']=incoming['edgeVoice']
                        outgoing['serverVoice']=outgoing.get('voice','')
                    return self.send(200,{'scope':scope,'characters':resolved})
                if path=='/v1/translations/sync':return self.send(200,service.translations.sync(body.get('items')))
                if path in ['/v1/translations/put','/v1/translations/source']:
                    key=body.pop('key','')
                    if path.endswith('/source'):
                        body['kind']='source'
                        body['translated_text']=''
                    return self.send(200,service.translations.put(key,body))
                if path=='/settings/role-volume':return self.send(200,service.save_role_mix(body))
                if path in ['/v1/audio/speech','/v1/audio/hybrid']:
                    started=time.perf_counter()
                    logging.info('tts_request_started')
                    # Ignore archive descriptors from older clients; return transient audio only.
                    data,fmt,timings=service.hybrid.speech(body) if path.endswith('/hybrid') else service.speech(body)
                    logging.info('tts_request_finished seconds=%.3f bytes=%s segments=%s',time.perf_counter()-started,len(data),len(timings))
                    if fmt=='json':return self.send(200,{'audio':base64.b64encode(data).decode(),'format':'wav','timings':timings})
                    return self.send(200,data,'audio/wav' if fmt=='wav' else 'audio/mpeg')
                if path=='/models/import':return self.send(202,{'job':service.import_async(body.get('path',''))})
                if path=='/models/check':return self.send(202,{'job':service.check_all()})
                if path=='/models/remove':
                    if service.busy.locked():raise ValueError('Đang kiểm tra/nhập model; thử lại sau')
                    with service.engine.lock:service.engine.close_model(body['model']);service.store.remove(body['model'])
                    return self.send(200,{'removed':body['model']})
                if path=='/voices/gender':
                    name,sid=body['voice'].rsplit(':',1);gender=body['gender']
                    if gender not in ['male','female','unknown']:raise ValueError('Giới tính không hợp lệ')
                    if body['voice'] not in [v['id'] for v in service.store.voices()]:raise ValueError('Voice không có trong kho')
                    with service.engine.lock:service.store.models[name]['meta'].setdefault('gender',{})[sid]=gender;service.store.save_meta(name)
                    return self.send(200,{'saved':True})
                return self.send(404,{'error':{'message':'Không có đường dẫn'}})
            except (ConnectionAbortedError,ConnectionResetError,BrokenPipeError):
                logging.warning('tts_client_disconnected: response socket closed')
            except (ValueError,KeyError,TypeError) as e:self.send(400,{'error':{'message':str(e)}})
            except RuntimeError as e:self.send(503,{'error':{'message':str(e)}})
            except Exception as e:logging.exception('Request failure');self.send(500,{'error':{'message':'Server lỗi; xem log máy chủ'}})
    return Handler

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--data',default=str(Path(__file__).parent/'data'));parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=17861);parser.add_argument('--import-zip',action='append',default=[]);parser.add_argument('--import-dir');parser.add_argument('--check-all',action='store_true');parser.add_argument('--threads',type=int,default=2);args=parser.parse_args()
    data=Path(args.data);data.mkdir(parents=True,exist_ok=True);keyfile=data/'server-key.txt'
    if not keyfile.exists():keyfile.write_text(secrets.token_urlsafe(32),encoding='utf-8')
    token=os.environ.get('SMART_TTS_API_KEY') or keyfile.read_text(encoding='utf-8').strip();service=Service(data,token,args.threads);service.port=args.port
    for archive in args.import_zip:service.store.install(archive)
    if args.import_dir:
        catalog=json.loads((Path(__file__).parent/'models-manifest.json').read_text(encoding='utf-8-sig'))
        for entry in catalog:
            archive=Path(args.import_dir)/(entry['id']+'.zip')
            if not archive.exists():print('Thiếu ZIP: '+archive.name,flush=True);continue
            if hashlib.sha256(archive.read_bytes()).hexdigest()!=entry['checksum']:raise ValueError('Checksum ZIP không khớp: '+archive.name)
            print('Nhập '+entry['name'],flush=True);name=service.store.install(archive)
            if entry['num_speakers']==1 and not service.store.models[name]['meta'].get('gender'):
                service.store.models[name]['meta']['gender']={'0':entry['gender']};service.store.models[name]['meta']['gender_source']='official_catalog';service.store.save_meta(name)
    if args.check_all:
        for voice in service.store.voices():print(voice['id'],service.engine.health_check(voice['id']),flush=True)
    logging.basicConfig(level=logging.INFO,format='%(asctime)s %(message)s');http=ThreadingHTTPServer((args.host,args.port),handler(service));print(f'Smart TTS: http://{args.host}:{args.port} — installed={len(service.store.voices())}, ready={len(service.store.voices(True))}',flush=True)
    try:http.serve_forever()
    except KeyboardInterrupt:pass
    finally:http.server_close();service.engine.close()

if __name__=='__main__':multiprocessing.freeze_support();main()

