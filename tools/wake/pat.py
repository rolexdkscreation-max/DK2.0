import os, numpy as np, onnxruntime as ort
H=os.path.expanduser("~/wk"); FD=f"{H}/feat"
_v=np.load(f"{H}/data/validation_set_features.npy",mmap_mode="r"); val=np.asarray(_v[len(_v)//2:])
def score(s,x):
    return np.concatenate([s.run(None,{"input":np.asarray(x[i:i+4096],np.float32)})[0][:,0] for i in range(0,len(x),4096)])
win=np.lib.stride_tricks.sliding_window_view(val,(16,96))[:,0]; hours=len(val)*0.08/3600
for w in ("hey","hello"):
    s=ort.InferenceSession(f"{H}/out/{w}_dk.onnx",providers=["CPUExecutionProvider"])
    sc=score(s,win)
    # positives are single windows -> need streaming pos test; estimate via max over clips not available; print fp only
    for P in (1,2,3,4):
        for t in (0.5,0.7,0.8,0.9,0.95):
            ok=sc>=t
            if P>1:
                k=np.convolve(ok.astype(int),np.ones(P,int),"valid")>=P
            else: k=ok
            idx=np.where(k)[0]; c=0; last=-999
            for i in idx:
                if i-last>25: c+=1; last=i
            print(w,"P",P,"th",t,"fp/h",round(c/hours,2))
