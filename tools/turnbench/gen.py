import asyncio, edge_tts, json, os, subprocess, numpy as np, random
random.seed(7); np.random.seed(7)
V={'hi':'hi-IN-SwaraNeural','hi2':'hi-IN-MadhurNeural','en':'en-IN-NeerjaNeural','en2':'en-IN-PrabhatNeural'}
# (lang, voice, phrases, pauses_after_each_phrase_except_last (ms))
U=[
 ('hi','hi',["आज मौसम कैसा है?"],[]),
 ('hi','hi2',["मुझे कल सुबह छह बजे","उठा देना"],[380]),
 ('hi','hi',["मैं कल बाज़ार गया और","वहाँ मुझे राहुल मिला"],[1100]),
 ('hi','hi2',["ये बताओ कि","दिल्ली से मुंबई कितनी दूर है?"],[900]),
 ('hi','hi',["मेरे फ़ोन की","ब्राइटनेस थोड़ी कम कर दो"],[420]),
 ('hi','hi2',["मुझे लगता है कि","शायद हमें कल चलना चाहिए"],[1400]),
 ('en','en',["What is the weather today?"],[]),
 ('en','en2',["Set an alarm for","seven thirty tomorrow morning"],[400]),
 ('en','en',["I was thinking and","maybe you can remind me to call mom"],[1200]),
 ('en','en2',["Tell me something about","the history of Nashik"],[700]),
 ('en','en',["Open YouTube and","play some old Hindi songs"],[850]),
 ('hg','hi',["DK, mera WhatsApp open karo"],[]),
 ('hg','hi2',["Yaar mujhe ek","reminder set karna hai"],[450]),
 ('hg','hi',["Kal meeting hai to","mujhe subah jaldi uthana"],[1000]),
 ('hg','hi2',["Matlab","mujhe samajh nahi aaya, phir se batao"],[800]),
 ('hg','hi',["Google Maps pe Sagar Nursery search karo aur","navigation start karo"],[650]),
 ('hg','hi2',["Ek baat batao","ye phone ki battery jaldi kyun khatam hoti hai?"],[1300]),
 ('hi','hi',["अच्छा सुनो","आज का प्लान क्या है?"],[1000]),
 ('en','en2',["Can you","translate this into Marathi?"],[350]),
 ('hg','hi',["Torch on karo"],[]),
]
RATES=[('slow','-20%'),('normal','+0%'),('fast','+25%')]
async def tts(text,voice,rate,path):
    c=edge_tts.Communicate(text,voice,rate=rate,boundary='WordBoundary'); words=[]
    with open(path+'.mp3','wb') as f:
        async for ch in c.stream():
            if ch['type']=='audio': f.write(ch['data'])
            elif ch['type']=='WordBoundary': words.append((ch['offset']/1e4, (ch['offset']+ch['duration'])/1e4, ch['text']))
    subprocess.run(['ffmpeg','-loglevel','error','-y','-i',path+'.mp3','-ar','16000','-ac','1','-f','s16le',path+'.raw'],check=True)
    a=np.fromfile(path+'.raw',dtype=np.int16).astype(np.float32)
    return a,words
def trim(a):
    thr=200; idx=np.where(np.abs(a)>thr)[0]
    if len(idx)==0: return a,0
    s=max(0,idx[0]-160); e=min(len(a),idx[-1]+160); return a[s:e], s/16
async def main():
    os.makedirs('raw',exist_ok=True); out=[]
    for ui,(lang,vk,phr,pauses) in enumerate(U):
        for rn,rate in RATES:
            sig=[np.zeros(16000*1,np.float32)]; t=1000.0; words=[]; segs=[]
            for pi,p in enumerate(phr):
                a,w=await tts(p,V[vk],rate,f'raw/u{ui}_{rn}_{pi}')
                a,off=trim(a)
                a=a/ (np.sqrt(np.mean(a**2))+1e-6) * 2600.0   # typical near-mic speech RMS
                for (ws,we,tx) in w: words.append((t+we-off, tx))
                segs.append((t, t+len(a)/16)); sig.append(a); t+=len(a)/16
                if pi<len(pauses):
                    pz=pauses[pi]*(1.25 if rn=='slow' else 0.8 if rn=='fast' else 1.0)
                    sig.append(np.zeros(int(16*pz),np.float32)); t+=pz
            end=t; sig.append(np.zeros(16000*3,np.float32))
            x=np.concatenate(sig)
            out.append(dict(id=f'u{ui}_{rn}',lang=lang,rate=rn,end=end,segs=segs,words=words,phr=phr,pauses=pauses))
            np.save(f'raw/u{ui}_{rn}.npy',x)
            print(out[-1]['id'],round(end),len(words),flush=True)
    json.dump(out,open('meta.json','w'),ensure_ascii=False)
asyncio.run(main())
