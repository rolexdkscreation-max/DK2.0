import json, numpy as np
rng=np.random.default_rng(11)
M=json.load(open('meta.json'))
# background speech (TV) = all clips concatenated, low level
tv=np.concatenate([np.load(f"raw/{m['id']}.npy") for m in M[:12]])
def brown(n):
    w=rng.standard_normal(n).cumsum(); w-=np.convolve(w,np.ones(400)/400,'same'); return w/ (w.std()+1e-6)
COND={'clean':None,'fan':'fan','tv':'tv','gaps':'gaps','soft':'soft'}
out=[]
for m in M:
    x0=np.load(f"raw/{m['id']}.npy")
    for c in COND:
        x=x0.copy(); n=len(x)
        x+=rng.standard_normal(n)*60      # mic self-noise
        if c=='fan': x+=brown(n)*350
        if c=='tv':
            s=rng.integers(0,len(tv)-n); x+=tv[s:s+n]*0.16
        if c=='soft': x*=0.4
        f=n//640; rms=np.sqrt(np.mean(x[:f*640].reshape(f,640)**2,axis=1))
        if c=='gaps':
            # 3 mic gaps of 80-200ms inside speech (zero samples, like a dropped/blocked read)
            for _ in range(3):
                s0,s1=m['segs'][rng.integers(0,len(m['segs']))]
                a=int(rng.uniform(s0+200,max(s0+250,s1-250))/40); L=int(rng.integers(2,6))
                rms[a:a+L]=0.0
        lag=400+rng.integers(0,200)
        caps=[[round(t+lag),w] for t,w in m['words']]
        out.append(dict(id=m['id']+'_'+c,lang=m['lang'],rate=m['rate'],cond=c,end=m['end'],
          pauses=[[m['segs'][i][1],m['segs'][i+1][0]] for i in range(len(m['segs'])-1)],
          rms=[round(float(v),1) for v in rms],caps=caps))
json.dump(out,open('cases.json','w'),ensure_ascii=False)
print(len(out))
