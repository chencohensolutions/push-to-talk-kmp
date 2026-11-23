import numpy as np, wave, subprocess
SR=48000; rng=np.random.default_rng(3)
def place(buf,sig,at):
    i=int(at*SR); n=min(len(sig),len(buf)-i); buf[i:i+n]+=sig[:n]
def lp(x,a):  # one-pole lowpass
    y=np.empty_like(x); s=0.0
    for i,v in enumerate(x): s+=a*(v-s); y[i]=s
    return y
def strike(dur=0.01,a=0.3):
    n=int(SR*dur); return lp(rng.standard_normal(n),a)*np.exp(-np.linspace(0,8,n))
def modal(f0,ratios,amps,decays,dur,beat=0.0):
    t=np.arange(int(SR*dur))/SR; s=np.zeros_like(t)
    for r,a,d in zip(ratios,amps,decays):
        ph=rng.random()*6.28
        s+=a*np.exp(-t*d)*np.sin(2*np.pi*f0*r*t+ph)
        if beat: s+=a*0.8*np.exp(-t*d)*np.sin(2*np.pi*(f0*r+beat*r)*t+ph+1)
    att=np.minimum(1,t/0.002)
    return s*att
def room(x,mix=0.3):
    y=x.copy()
    taps=[(rng.uniform(0.011,0.14),rng.uniform(0.15,0.5)) for _ in range(28)]
    for d,g in taps:
        n=int(d*SR); y[n:]+=lp(x[:-n],0.35)*g*mix
    # tail
    tail=np.zeros_like(x); n=int(0.05*SR)
    for k in range(1,14): 
        m=n*k+int(rng.uniform(0,400))
        if m<len(x): tail[m:]+=lp(x[:-m],0.2)*(0.6**k)
    return y+tail*mix*0.6
def out(name,x,peak=0.3):
    x=x/np.max(np.abs(x))*peak
    f=int(0.08*SR); x[-f:]*=np.linspace(1,0,f)
    pcm=(x*32767).astype(np.int16)
    w=wave.open(f'{name}.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes()); w.close()
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',f'{name}.wav','-b:a','160k',f'/mnt/project-files/sounds/{name}.mp3'],check=True)
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',f'{name}.wav','-c:a','libvorbis','-q:a','5',f'/mnt/project-files/sounds/{name}.ogg'],check=True)
def diffuse(x,rt,mix,dark=0.15):
    n=int(SR*rt); t=np.arange(n)/SR
    ir=lp(rng.standard_normal(n),dark)*np.exp(-t*6.9/rt)
    ir[:int(.012*SR)]=0
    ir/=np.sqrt(np.sum(ir**2))
    L=len(x)+n
    y=np.fft.irfft(np.fft.rfft(x,L)*np.fft.rfft(ir,L),L)
    return np.concatenate([x,np.zeros(n)])+mix*y[:L]
def tone(dur,decay):
    t=np.arange(int(SR*dur))/SR
    return (np.sin(2*np.pi*220*t)+.15*np.sin(2*np.pi*440*t)*np.exp(-t*6))*np.exp(-t*decay)*np.minimum(1,t/0.012)
out('message-soft-tone-m2',diffuse(tone(0.45,7),0.45,0.35),0.32)
