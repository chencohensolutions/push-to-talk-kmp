import numpy as np, sys
exec(open('soft-tone-m2.py').read().split('# G singing')[0])
def diffuse(x,rt,mix,dark=0.15):
    n=int(SR*rt); t=np.arange(n)/SR
    ir=lp(rng.standard_normal(n),dark)*np.exp(-t*6.9/rt); ir[:int(.012*SR)]=0; ir/=np.sqrt(np.sum(ir**2))
    L=len(x)+n; y=np.fft.irfft(np.fft.rfft(x,L)*np.fft.rfft(ir,L),L)
    return np.concatenate([x,np.zeros(n)])+mix*y[:L]
def tone(f,dur,decay):
    t=np.arange(int(SR*dur))/SR
    return (np.sin(2*np.pi*f*t)+.15*np.sin(2*np.pi*2*f*t)*np.exp(-t*6))*np.exp(-t*decay)*np.minimum(1,t/0.012)
for name,f in [('m2-c',261.6),('m2-d',293.7),('m2-e',329.6)]:
    out(f'message-soft-tone-{name}',diffuse(tone(f,0.45,7),0.45,0.35),0.32)
