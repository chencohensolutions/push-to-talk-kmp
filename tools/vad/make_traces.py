import numpy as np, wave, sys, onnxruntime as ort
d=sys.argv[1]
def readwav(p):
    w=wave.open(p); x=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16).astype(np.float32)/32768; return x
rng=np.random.default_rng(3)
def car_noise(n):
    # brown noise (road) + engine hum harmonics + a little white (fan)
    w=rng.normal(0,1,n); brown=np.cumsum(w); brown-=np.convolve(brown,np.ones(800)/800,'same'); brown/=np.std(brown)+1e-9
    t=np.arange(n)/16000; hum=sum(np.sin(2*np.pi*f*t+rng.uniform(0,6))/(k+1) for k,f in enumerate([38,76,114,152]))
    hum/=np.std(hum); white=rng.normal(0,1,n)
    x=0.7*brown+0.5*hum+0.25*white; return x/np.std(x)
def mix(speech, snr, lead=0.5, tail=3.0, pause=None):
    sp=speech
    if pause is not None:  # a 0.8 s pause inside the sentence
        mid=len(sp)//2; sp=np.concatenate([sp[:mid],np.zeros(int(0.8*16000),np.float32),sp[mid:]])
    total=int(lead*16000)+len(sp)+int(tail*16000)
    n=car_noise(total)
    s=np.zeros(total,np.float32); s[int(lead*16000):int(lead*16000)+len(sp)]=sp
    ps=np.mean(sp[np.abs(sp)>0.01]**2); pn=np.mean(n**2)
    n*=np.sqrt(ps/(pn*10**(snr/10)))
    x=s+n; x/=max(1.0,np.max(np.abs(x))/0.9)
    return x, int(lead*16000)/16000, (int(lead*16000)+len(sp))/16000
sess=ort.InferenceSession(d+'/silero_vad.onnx')
def probs(x):
    state=np.zeros((2,1,128),np.float32); ctx=np.zeros(64,np.float32); out=[]
    for i in range(0,len(x)-512+1,512):
        chunk=x[i:i+512].astype(np.float32); inp=np.concatenate([ctx,chunk])[None,:]
        p,state=sess.run(None,{'input':inp,'state':state,'sr':np.array(16000,np.int64)}); out.append(float(p[0][0])); ctx=chunk[-64:]
    return out
cases=[]
for name,src,pause in [('sentence','speech1.wav',None),('pause','speech1.wav',True),('short','speech2.wav',None)]:
    sp=readwav(d+'/'+src)
    for snr in [0,5,10]:
        x,a,b=mix(sp,snr,pause=pause)
        p=probs(x)
        tag=f'{name}_{snr}db'
        w=wave.open(d+f'/{tag}.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes((x*32767).astype(np.int16).tobytes()); w.close()
        cases.append((tag,a,b,p))
        print(tag, 'speech %.2f-%.2f s'%(a,b), 'frames',len(p), 'max %.2f'%max(p))
# noise only
x=car_noise(6*16000)*0.2; p=probs(x.astype(np.float32)); cases.append(('noise_only',0,0,p)); print('noise_only max %.2f'%max(p))
with open(d+'/traces.txt','w') as f:
    for tag,a,b,p in cases:
        f.write(f'{tag} {a:.3f} {b:.3f} '+' '.join('%.3f'%v for v in p)+'\n')
