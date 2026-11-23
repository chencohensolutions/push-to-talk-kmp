import numpy as np
exec(open('soft-tone-e-base.py').read().split('for name')[0])
f=329.6; dur=1.0
t=np.arange(int(SR*dur))/SR
dry=(np.sin(2*np.pi*f*t)+.15*np.sin(2*np.pi*2*f*t)*np.exp(-t*6))*np.exp(-t*7)
att=int(0.02*SR); dry[:att]*=0.5-0.5*np.cos(np.linspace(0,np.pi,att))   # smooth raised-cosine attack
rel=int(0.15*SR); dry[-rel:]*=0.5+0.5*np.cos(np.linspace(0,np.pi,rel)) # smooth release, no cut
x=diffuse(dry,0.45,0.35)
x=x[:int(SR*1.05)]
x-=np.mean(x)
x=x/np.max(np.abs(x))*0.4
f2=int(0.25*SR); x[-f2:]*=0.5+0.5*np.cos(np.linspace(0,np.pi,f2))
x[-200:]=0
import wave
pcm=np.round(x*32767).astype(np.int16)
w=wave.open('message-soft-tone-e.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes()); w.close()
print('peak',np.max(np.abs(x)),'first',x[:3],'last',x[-3:])
